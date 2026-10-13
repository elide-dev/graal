/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.interpreter.ristretto.compile;

import static jdk.graal.compiler.core.common.GraalOptions.MaximumRecursiveInlining;

import java.util.Arrays;

import com.oracle.svm.core.invoke.Target_java_lang_invoke_MemberName;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.core.methodhandles.Target_java_lang_invoke_MethodHandleNatives_Constants;
import com.oracle.svm.interpreter.ristretto.RistrettoOptions;
import com.oracle.svm.interpreter.ristretto.meta.RistrettoMethodHandleAccessProvider;

import jdk.graal.compiler.java.BytecodeParser;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.NewInstanceNode;
import jdk.graal.compiler.nodes.java.NewInstanceWithExceptionNode;
import jdk.graal.compiler.nodes.util.ConstantFoldUtil;
import jdk.graal.compiler.replacements.MethodHandleWithExceptionPlugin;
import jdk.graal.compiler.replacements.nodes.MacroInvokable;
import jdk.graal.compiler.replacements.nodes.MethodHandleNode;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MethodHandleAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Ristretto-specific method-handle parser support.
 * <p>
 * The interpreter links a runtime-loaded {@code MethodHandle.invokeExact(...)} or
 * {@code invoke(...)} site to a small JDK linker adapter, {@code invokeExact_MT(mh, args...,
 * MethodType)} or {@code invoke_MT(...)}, which checks or adapts the handle's type and calls
 * {@code mh.invokeBasic(args...)}. The adapter is an AOT method of {@code Invokers$Holder} or a
 * lambda form spun at run time; its bytecodes are not available to Ristretto, so without help the
 * compiled code calls the adapter, which runs the handle's lambda forms in the interpreter.
 * <p>
 * When the handle and the method type are compiler-visible constants and the types are the same
 * (so the adapter's check passes, or its {@code asType} is the identity), and the handle is a
 * direct method handle, the plugin replaces the adapter by a call to the handle's member: a direct
 * method handle invokes its member through a {@code linkTo*} intrinsic whatever lambda form it
 * carries, so no lambda form bytecodes are needed. The call is an ordinary invoke at the guest's
 * bytecode index, which deoptimization treats like any other invoke, and which Ristretto's later
 * phases may inline. The same replacement serves the {@code invokedynamic} adapter
 * {@code Invokers$Holder.linkToTargetMethod(args..., appendix)} and the constructor adapter
 * {@code DirectMethodHandle$Holder.newInvokeSpecial(mh, args...)} when their handle is constant.
 * A constructor handle (the call site target of a capturing lambda, for one) becomes an allocation
 * and the inlined constructor, since a constructor call returns nothing while the adapter returns
 * the new instance (see {@link #tryEmitNewInvokeSpecial}). Other handles (bound handles, type
 * adapters, field accessors) keep the generic plugin's behavior.
 * {@link RistrettoOptions#JITInlineConstantMethodHandles} turns the replacement off.
 */
public final class RistrettoMethodHandleWithExceptionPlugin extends MethodHandleWithExceptionPlugin {
    /** JDK holder for direct method-handle adapters. */
    private static final String DIRECT_METHOD_HANDLE_HOLDER = "java.lang.invoke.DirectMethodHandle$Holder";
    /** JDK holder for linked-invoker adapters. */
    private static final String INVOKERS_HOLDER = "java.lang.invoke.Invokers$Holder";
    /** Name prefix of the hidden classes that the JDK spins for lambda forms at run time. */
    private static final String LAMBDA_FORM_CLASS_PREFIX = "java.lang.invoke.LambdaForm$MH";
    /** Linked-invoker adapter replaced when its appendix is constant. */
    private static final String LINK_TO_TARGET_METHOD = "linkToTargetMethod";
    /** Direct-method-handle adapter that allocates and invokes a constructor. */
    private static final String NEW_INVOKE_SPECIAL = "newInvokeSpecial";
    /** Linker adapter of {@code MethodHandle.invokeExact} ({@code LambdaForm.Kind.EXACT_LINKER}). */
    private static final String INVOKE_EXACT_MT = "invokeExact_MT";
    /** Linker adapter of {@code MethodHandle.invoke} ({@code LambdaForm.Kind.GENERIC_LINKER}). */
    private static final String INVOKE_MT = "invoke_MT";

    /** Creates a Ristretto method-handle plugin with the generic plugin's deoptimization policy. */
    public RistrettoMethodHandleWithExceptionPlugin(MethodHandleAccessProvider methodHandleAccess, boolean safeForDeoptimization) {
        super(methodHandleAccess, safeForDeoptimization);
    }

    /** Handles Ristretto-specific linker, linked-invoker and constructor adapters before generic parsing. */
    @Override
    public boolean handleInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        if (tryHandleConstantInvoker(b, method, args)) {
            return true;
        }
        if (tryHandleLinkedTargetMethod(b, method, args)) {
            return true;
        }
        if (tryHandleNewInvokeSpecial(b, method, args)) {
            return true;
        }
        return super.handleInvoke(b, method, args);
    }

    /**
     * Replaces the linker adapter of a {@code MethodHandle.invokeExact} or {@code invoke} site whose
     * handle is a constant direct method handle of the call site's method type with a call to the
     * handle's member.
     */
    private boolean tryHandleConstantInvoker(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        if (!RistrettoOptions.JITInlineConstantMethodHandles.getValue() || args.length < 2 || !isInvokerLinker(method)) {
            return false;
        }
        if (!(methodHandleAccess instanceof RistrettoMethodHandleAccessProvider access)) {
            return false;
        }
        JavaConstant handle = asConstant(b, args[0]);
        JavaConstant methodType = asConstant(b, args[args.length - 1]);
        if (handle == null || methodType == null) {
            return trace(b, method, handle != null ? "the method type is not constant" : "the handle is not constant");
        }
        /*
         * Invokers.checkExactType throws WrongMethodTypeException unless the handle's type is the
         * call site's, and Invokers.checkGenericType adapts the handle with asType unless it is: both
         * are left to the adapter.
         */
        if (!access.isHandleOfType(handle, methodType)) {
            return trace(b, method, "the handle does not have the call site's type");
        }
        ValueNode[] invokeArgs = Arrays.copyOfRange(args, 1, args.length - 1);
        return tryInvokeDirectMember(b, method, access, handle, invokeArgs);
    }

    /**
     * Returns the constant value of {@code node}: its own, or that of the static final field of an
     * initialized class it loads, which the parser only folds after parsing. A {@code static final}
     * handle is the common case of a constant handle.
     */
    private static JavaConstant asConstant(GraphBuilderContext b, ValueNode node) {
        if (node.isConstant()) {
            return node.asJavaConstant();
        }
        if (node instanceof LoadFieldNode load && load.isStatic()) {
            ConstantNode constant = ConstantFoldUtil.tryConstantFold(b.getConstantFieldProvider(), b.getConstantReflection(), b.getMetaAccess(), load.field(), null, b.getOptions(),
                            load.getNodeSourcePosition());
            if (constant != null) {
                return constant.asJavaConstant();
            }
        }
        return null;
    }

    /**
     * Emits the call that the constant direct method handle {@code handle} makes with
     * {@code args}, in place of the adapter {@code adapter} that would run its lambda form.
     * Returns false, leaving the graph unchanged, if the handle is not a direct method handle or
     * the replacement would not preserve its semantics.
     */
    private static boolean tryInvokeDirectMember(GraphBuilderContext b, ResolvedJavaMethod adapter, RistrettoMethodHandleAccessProvider access, JavaConstant handle, ValueNode[] args) {
        Target_java_lang_invoke_MemberName member = access.resolveDirectMemberName(handle);
        if (member == null) {
            return trace(b, adapter, "the handle is not a direct method handle");
        }
        ResolvedJavaMethod target = access.resolveMemberTarget(member);
        if (target == null) {
            return trace(b, adapter, "the member has no method");
        }
        byte referenceKind = member.getReferenceKind();
        if (referenceKind == Target_java_lang_invoke_MethodHandleNatives_Constants.REF_newInvokeSpecial) {
            return tryEmitNewInvokeSpecial(b, adapter, target, args);
        }
        InvokeKind invokeKind;
        if (referenceKind == Target_java_lang_invoke_MethodHandleNatives_Constants.REF_invokeStatic) {
            invokeKind = InvokeKind.Static;
        } else if (referenceKind == Target_java_lang_invoke_MethodHandleNatives_Constants.REF_invokeVirtual) {
            invokeKind = InvokeKind.Virtual;
        } else if (referenceKind == Target_java_lang_invoke_MethodHandleNatives_Constants.REF_invokeInterface) {
            invokeKind = InvokeKind.Interface;
        } else if (referenceKind == Target_java_lang_invoke_MethodHandleNatives_Constants.REF_invokeSpecial) {
            invokeKind = InvokeKind.Special;
        } else {
            return trace(b, adapter, "the member has reference kind " + referenceKind);
        }
        if (target.isConstructor() || target.isStatic() != (invokeKind == InvokeKind.Static)) {
            return trace(b, adapter, "the member does not match its reference kind");
        }
        if (args.length != target.getSignature().getParameterCount(!target.isStatic())) {
            return trace(b, adapter, "the argument count does not match");
        }
        if (invokeKind == InvokeKind.Static && !target.getDeclaringClass().isInitialized()) {
            /* DirectMethodHandle.invokeStaticInit runs the class initializer first. */
            return trace(b, adapter, "the class of the static target is not initialized");
        }
        if (invokeKind == InvokeKind.Special && b.getMethod().getDeclaringClass().isInterface()) {
            /* The parser adds the receiver check of invokespecial in an interface to such calls. */
            return trace(b, adapter, "the caller is an interface");
        }
        if (invokeKind == InvokeKind.Interface) {
            if (!(b instanceof BytecodeParser parser)) {
                return trace(b, adapter, "the parser cannot emit interface calls");
            }
            /* The parser emits the IncompatibleClassChangeError check of the interface receiver. */
            maybeCastInvokeBasicArguments(b, target, args, false);
            parser.handleReplacedInvoke(invokeKind, target, args, false, target.getDeclaringClass());
        } else {
            maybeCastInvokeBasicArguments(b, target, args, true);
            b.handleReplacedInvoke(invokeKind, target, args, false);
        }
        adjustReturnKind(b, adapter, target);
        if (RistrettoOptions.JITTraceCompilation.getValue()) {
            Log.log().string("[Ristretto Compiler] Replaced ").string(adapter.format("%H.%n(%p)")).string(" in ").string(b.getMethod().format("%H.%n(%p)")).string(" with a ")
                            .string(invokeKind.toString()).string(" call to ").string(target.format("%H.%n(%p)")).newline();
        }
        return true;
    }

    /** Logs why a call through a constant handle was left to its adapter when tracing compilation; returns false. */
    private static boolean trace(GraphBuilderContext b, ResolvedJavaMethod adapter, String reason) {
        if (RistrettoOptions.JITTraceCompilation.getValue()) {
            Log.log().string("[Ristretto Compiler] Left ").string(adapter.format("%H.%n(%p)")).string(" in ").string(b.getMethod().format("%H.%n(%p)")).string(" alone: ").string(reason).newline();
        }
        return false;
    }

    /**
     * Replaces a linked target-method adapter with a direct invoke when its method-handle appendix
     * is a compiler-visible constant.
     */
    private boolean tryHandleLinkedTargetMethod(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        if (!isLinkedTargetMethod(method) || args.length == 0) {
            return false;
        }

        ValueNode appendix = args[args.length - 1];
        if (!appendix.isConstant()) {
            return false;
        }

        if (RistrettoOptions.JITInlineConstantMethodHandles.getValue() && methodHandleAccess instanceof RistrettoMethodHandleAccessProvider access &&
                        tryInvokeDirectMember(b, method, access, appendix.asJavaConstant(), Arrays.copyOf(args, args.length - 1))) {
            return true;
        }

        ResolvedJavaMethod target = methodHandleAccess.resolveInvokeBasicTarget(appendix.asJavaConstant(), false);
        if (target == null || target.equals(method)) {
            return false;
        }

        ValueNode[] replacementArgs = toInvokeBasicArguments(args);
        if (replacementArgs.length != target.getSignature().getParameterCount(!target.isStatic())) {
            return false;
        }
        if (!canForceInline(b, target)) {
            return false;
        }

        maybeCastInvokeBasicArguments(b, target, replacementArgs, true);
        InvokeKind invokeKind = target.isStatic() ? InvokeKind.Static : InvokeKind.Special;
        ensureForcedInlineSucceeded(b, target, b.handleReplacedInvoke(invokeKind, target, replacementArgs, true));
        adjustReturnKind(b, method, target);
        return true;
    }

    /**
     * Replaces a constant {@code newInvokeSpecial} adapter with allocation and a direct constructor
     * invoke.
     */
    private boolean tryHandleNewInvokeSpecial(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        if (!isNewInvokeSpecial(method) || args.length == 0 || !(methodHandleAccess instanceof RistrettoMethodHandleAccessProvider ristrettoMethodHandleAccess)) {
            return false;
        }

        JavaConstant methodHandle = asConstant(b, args[0]);
        if (methodHandle == null) {
            return false;
        }

        ResolvedJavaMethod constructor = ristrettoMethodHandleAccess.resolveInternalMemberTarget(methodHandle);
        if (constructor == null) {
            return false;
        }
        return tryEmitNewInvokeSpecial(b, method, constructor, Arrays.copyOfRange(args, 1, args.length));
    }

    /**
     * Emits the allocation and inlined constructor call of a direct method handle to
     * {@code constructor}, called with the constructor arguments {@code args}, in place of
     * {@code adapter}, and pushes the new instance as the adapter's result.
     * <p>
     * The constructor is inlined at once rather than emitted as a call: the adapter returns the
     * new instance, but the constructor returns nothing, and the parser creates the frame state
     * of a call with its own result on the operand stack, before this plugin can push the
     * instance. The state is that of the bytecode after the adapter's: when that bytecode
     * consumes the result (an {@code astore}, say), the parser rejects it as a stack underflow
     * (elide-dev/graal#131); and a lazy deoptimization while the constructor is on the stack would
     * take the result from the return register, which a constructor does not set. With the
     * constructor inlined, the replacement has no call to attach a state to.
     */
    private static boolean tryEmitNewInvokeSpecial(GraphBuilderContext b, ResolvedJavaMethod adapter, ResolvedJavaMethod constructor, ValueNode[] args) {
        if (!constructor.isConstructor()) {
            return trace(b, adapter, "the member is not a constructor");
        }
        ResolvedJavaType instanceType = constructor.getDeclaringClass();
        if (!instanceType.isInitialized() || instanceType.isAbstract() || instanceType.isInterface()) {
            return trace(b, adapter, "the class of the constructor cannot be instantiated");
        }
        if (args.length != constructor.getSignature().getParameterCount(false)) {
            return trace(b, adapter, "the argument count does not match");
        }
        if (adapter.getSignature().getReturnKind().getStackKind() != JavaKind.Object) {
            return trace(b, adapter, "the adapter does not return an object");
        }
        if (!canForceInline(b, constructor)) {
            return trace(b, adapter, "the constructor cannot be inlined");
        }
        ValueNode[] replacementArgs = new ValueNode[args.length + 1];
        replacementArgs[0] = b.add(createConstructorAllocation(instanceType, b.currentBlockCatchesOOME()));
        System.arraycopy(args, 0, replacementArgs, 1, args.length);

        maybeCastInvokeBasicArguments(b, constructor, replacementArgs, true);
        ValueNode newInstance = replacementArgs[0];
        ensureForcedInlineSucceeded(b, constructor, b.handleReplacedInvoke(InvokeKind.Special, constructor, replacementArgs, true));
        if (!b.hasParseTerminated()) {
            /* The inlined constructor always throws otherwise, and the rest of the block is dead. */
            b.addPush(JavaKind.Object, newInstance);
        }
        if (RistrettoOptions.JITTraceCompilation.getValue()) {
            Log.log().string("[Ristretto Compiler] Replaced ").string(adapter.format("%H.%n(%p)")).string(" in ").string(b.getMethod().format("%H.%n(%p)"))
                            .string(" with an allocation and the inlined constructor ").string(constructor.format("%H.%n(%p)")).newline();
        }
        return true;
    }

    /** Checks the prerequisites for a forced inline or a Ristretto-eliminated adapter. */
    private static boolean canForceInline(GraphBuilderContext b, ResolvedJavaMethod target) {
        if (!target.hasBytecodes() && !b.getReplacements().hasSubstitution(target, b.getOptions()) && !isNewInvokeSpecial(target)) {
            return false;
        }
        return b.recursiveInliningDepth(target) <= MaximumRecursiveInlining.getValue(b.getOptions());
    }

    /** A failed forced inline has already changed parser state and must not be compiled as a call. */
    private static void ensureForcedInlineSucceeded(GraphBuilderContext b, ResolvedJavaMethod target, Invokable replacement) {
        if (replacement instanceof Invoke invoke && invoke.asFixedNode().isAlive()) {
            throw b.bailout("Could not force-inline Ristretto method-handle target " + target.format("%H.%n(%p)"));
        }
        assert replacement == null || replacement instanceof MacroInvokable : replacement;
    }

    /**
     * Returns whether {@code method} is the linker adapter of {@code MethodHandle.invokeExact} or
     * {@code invoke}: a pregenerated one of {@code Invokers$Holder} or one spun at run time, which
     * the JDK names after its lambda form kind.
     */
    private static boolean isInvokerLinker(ResolvedJavaMethod method) {
        if (!method.isStatic()) {
            return false;
        }
        String name = method.getName();
        if (!INVOKE_EXACT_MT.equals(name) && !INVOKE_MT.equals(name)) {
            return false;
        }
        String holder = method.getDeclaringClass().toJavaName(true);
        return INVOKERS_HOLDER.equals(holder) || holder.startsWith(LAMBDA_FORM_CLASS_PREFIX);
    }

    /** Returns whether {@code method} is the JDK linked target-method adapter. */
    private static boolean isLinkedTargetMethod(ResolvedJavaMethod method) {
        return method.isStatic() &&
                        LINK_TO_TARGET_METHOD.equals(method.getName()) &&
                        INVOKERS_HOLDER.equals(method.getDeclaringClass().toJavaName(true));
    }

    /** Returns whether {@code method} is the JDK direct-constructor adapter. */
    private static boolean isNewInvokeSpecial(ResolvedJavaMethod method) {
        return method.isStatic() &&
                        NEW_INVOKE_SPECIAL.equals(method.getName()) &&
                        DIRECT_METHOD_HANDLE_HOLDER.equals(method.getDeclaringClass().toJavaName(true));
    }

    /** Moves the linked-invoker appendix into the invoke-basic receiver position. */
    private static ValueNode[] toInvokeBasicArguments(ValueNode[] linkedInvokerArgs) {
        ValueNode[] replacementArgs = new ValueNode[linkedInvokerArgs.length];
        replacementArgs[0] = linkedInvokerArgs[linkedInvokerArgs.length - 1];
        System.arraycopy(linkedInvokerArgs, 0, replacementArgs, 1, linkedInvokerArgs.length - 1);
        return replacementArgs;
    }

    private static ValueNode createConstructorAllocation(ResolvedJavaType instanceType, boolean catchesOOME) {
        return catchesOOME ? new NewInstanceWithExceptionNode(instanceType, true) : new NewInstanceNode(instanceType, true);
    }

    /**
     * Applies the same argument adaptation used by
     * {@link jdk.graal.compiler.replacements.MethodHandlePlugin} after resolving an invoke-basic
     * target: lambda forms erase reference types to Object, so the arguments are cast to the
     * target's parameter types. The receiver is cast to the declaring class only if
     * {@code castReceiver}: an interface receiver is checked by the parser instead.
     */
    private static void maybeCastInvokeBasicArguments(GraphBuilderContext b, ResolvedJavaMethod target, ValueNode[] args, boolean castReceiver) {
        MethodHandleNode.GraphAdder adder = new MethodHandleNode.GraphAdder(b.getGraph()) {
            @Override
            public <T extends ValueNode> T add(T node) {
                return b.add(node);
            }
        };

        if (!target.isStatic() && castReceiver) {
            MethodHandleNode.maybeCastArgument(adder, args, 0, target.getDeclaringClass());
        }

        Signature signature = target.getSignature();
        int receiverSkip = target.isStatic() ? 0 : 1;
        for (int index = 0; index < signature.getParameterCount(false); index++) {
            JavaType parameterType = signature.getParameterType(index, target.getDeclaringClass());
            MethodHandleNode.maybeCastArgument(adder, args, receiverSkip + index, parameterType);
        }
    }

    /**
     * Reconciles the direct target return kind with the bypassed adapter signature: the target's
     * result is on the operand stack in place of the adapter's. A result the adapter discards is
     * popped; any other difference cannot be compiled.
     */
    private static void adjustReturnKind(GraphBuilderContext b, ResolvedJavaMethod adapter, ResolvedJavaMethod target) {
        if (b.hasParseTerminated()) {
            /* The inlined target always throws, and the rest of the block is dead. */
            return;
        }

        JavaKind adapterReturnKind = adapter.getSignature().getReturnKind().getStackKind();
        JavaKind targetReturnKind = target.getSignature().getReturnKind().getStackKind();
        if (adapterReturnKind == targetReturnKind) {
            return;
        }
        if (adapterReturnKind != JavaKind.Void) {
            throw b.bailout("Cannot bypass method-handle adapter with a different return kind");
        }
        b.pop(targetReturnKind);
    }
}
