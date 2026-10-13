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
package com.oracle.svm.interpreter;

import static com.oracle.svm.interpreter.metadata.Bytecodes.ANEWARRAY;
import static com.oracle.svm.interpreter.metadata.Bytecodes.CHECKCAST;
import static com.oracle.svm.interpreter.metadata.Bytecodes.GETFIELD;
import static com.oracle.svm.interpreter.metadata.Bytecodes.GETSTATIC;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INSTANCEOF;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INVOKEDYNAMIC;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INVOKEINTERFACE;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INVOKESPECIAL;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INVOKESTATIC;
import static com.oracle.svm.interpreter.metadata.Bytecodes.INVOKEVIRTUAL;
import static com.oracle.svm.interpreter.metadata.Bytecodes.JSR;
import static com.oracle.svm.interpreter.metadata.Bytecodes.JSR_W;
import static com.oracle.svm.interpreter.metadata.Bytecodes.LDC;
import static com.oracle.svm.interpreter.metadata.Bytecodes.LDC2_W;
import static com.oracle.svm.interpreter.metadata.Bytecodes.LDC_W;
import static com.oracle.svm.interpreter.metadata.Bytecodes.MULTIANEWARRAY;
import static com.oracle.svm.interpreter.metadata.Bytecodes.NEW;
import static com.oracle.svm.interpreter.metadata.Bytecodes.PUTFIELD;
import static com.oracle.svm.interpreter.metadata.Bytecodes.PUTSTATIC;
import static com.oracle.svm.interpreter.metadata.Bytecodes.RET;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.graal.pointsto.ObjectScanner.OtherReason;
import com.oracle.graal.pointsto.ObjectScanner.ScanReason;
import com.oracle.graal.pointsto.heap.ImageHeapScanner;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.substitute.SubstitutionMethod;
import com.oracle.svm.interpreter.metadata.BytecodeStream;
import com.oracle.svm.interpreter.metadata.InterpreterConstantPool;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaField;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaType;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType;
import com.oracle.svm.interpreter.metadata.ReferenceConstant;
import com.oracle.svm.util.GuestAccess;
import com.oracle.svm.util.JVMCIReflectionUtil;

import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.ExceptionHandler;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaField;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Lets Ristretto inline AOT-compiled methods (elide-dev/graal#103).
 * <p>
 * Ristretto parses the bytecodes of a callee to inline it, and AOT methods normally keep no
 * bytecodes and their classes no constant pool. The methods of the classes named by
 * {@link InterpreterOptions#RistrettoInlinableAOTClasses} keep their bytecodes (unless
 * {@link InterpreterOptions#RistrettoInlineAOTMethods} is off)
 * ({@link #isCandidate}). After compilation, {@link #prepare} drops them again for every method
 * that Ristretto could not use as it is, and builds a constant pool for the classes of the
 * remaining ones. The interpreter can then also execute those methods, which deoptimization of
 * Ristretto code that inlined them requires.
 */
@Platforms(Platform.HOSTED_ONLY.class)
final class RistrettoInlinableAOTMethods {

    private static final String METHOD_HANDLE = "Ljava/lang/invoke/MethodHandle;";
    private static final String VAR_HANDLE = "Ljava/lang/invoke/VarHandle;";

    private RistrettoInlinableAOTMethods() {
    }

    static boolean isEnabled() {
        return SubstrateOptions.useRistretto() && InterpreterOptions.RistrettoInlineAOTMethods.getValue() && !classPrefixes().isEmpty();
    }

    private static List<String> classPrefixes() {
        return InterpreterOptions.RistrettoInlinableAOTClasses.getValue().values().stream().filter(prefix -> !prefix.isEmpty()).toList();
    }

    /**
     * Constant pools and the method metadata that {@link BuildTimeConstantPool} fills in are
     * created after analysis, so their types must be known to be in the image heap. Crema
     * otherwise creates them only at run time.
     */
    static void registerHeapTypes(FeatureImpl.BeforeAnalysisAccessImpl access) {
        for (Class<?> c : new Class<?>[]{InterpreterConstantPool.class, ReferenceConstant.class, PrimitiveConstant.class, ExceptionHandler.class, ExceptionHandler[].class,
                        LocalVariableTable.class, Local.class, Local[].class, LineNumberTable.class, Object[].class, String.class}) {
            access.registerAsInHeap(c, "Ristretto inlining of AOT methods (build-time constant pools)");
        }
    }

    private static final Set<AnalysisMethod> scannedCandidates = ConcurrentHashMap.newKeySet();
    private static final Set<AnalysisMethod> pendingCallees = ConcurrentHashMap.newKeySet();

    /**
     * Small methods such as {@code ConcurrentHashMap.spread} are inlined wherever they are called,
     * and Unsafe accessors are intrinsified, so they normally have no compiled code of their own. A
     * candidate that calls one could not be inlined by Ristretto: Ristretto code may still call it
     * when it does not inline it in turn, and when Ristretto code that inlined a candidate
     * deoptimizes, the interpreter resumes the candidate and must call it. So the statically bound
     * callees of reachable candidates are compiled on their own. Virtual callees are dispatched
     * through the vtable, whose entries are always compiled. A callee is compiled once the analysis
     * links it: instance methods only when their class is instantiated.
     */
    static void registerCallees(FeatureImpl.DuringAnalysisAccessImpl access) {
        boolean added = false;
        for (AnalysisMethod method : access.getUniverse().getMethods()) {
            if (!method.isReachable() || !isCandidate(method) || !scannedCandidates.add(method)) {
                continue;
            }
            byte[] code = method.getCode();
            ConstantPool cp = method.getConstantPool();
            for (int bci = 0; bci < BytecodeStream.endBCI(code); bci = BytecodeStream.nextBCI(code, bci)) {
                int bytecode = BytecodeStream.currentBC(code, bci);
                if (bytecode != INVOKEVIRTUAL && bytecode != INVOKESPECIAL && bytecode != INVOKESTATIC) {
                    continue;
                }
                JavaMethod callee;
                try {
                    callee = cp.lookupMethod(BytecodeStream.readCPI(code, bci), bytecode);
                } catch (LinkageError | RuntimeException | AnalysisError e) {
                    continue;
                }
                if (callee instanceof AnalysisMethod aCallee && (bytecode != INVOKEVIRTUAL || aCallee.canBeStaticallyBound()) && !aCallee.isNative() && !aCallee.isAbstract() &&
                                aCallee.getCode() != null) {
                    if (!aCallee.isDirectRootMethod()) {
                        access.registerAsRoot(aCallee, true, "Callee of an AOT method that Ristretto may inline");
                        added = true;
                    }
                    pendingCallees.add(aCallee);
                }
            }
        }
        for (AnalysisMethod callee : pendingCallees) {
            if (callee.isImplementationInvoked()) {
                SubstrateCompilationDirectives.singleton().registerForcedCompilation(callee);
                pendingCallees.remove(callee);
            }
        }
        if (added) {
            access.requireAnalysisIteration();
        }
    }

    /** Whether {@code method} keeps its bytecodes until {@link #prepare} decides. */
    static boolean isCandidate(AnalysisMethod method) {
        if (!isEnabled() || method.isNative() || method.isAbstract() || method.getCode() == null) {
            return false;
        }
        /* The AOT code of a substituted method is not its bytecodes. */
        if (method.wrapped instanceof SubstitutionMethod || !InterpreterFeature.executableByInterpreter(method)) {
            return false;
        }
        String className = method.getDeclaringClass().toJavaName();
        for (String prefix : classPrefixes()) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the bytecodes of the candidates that Ristretto can inline and drops the others, then
     * builds the constant pools of their classes. Runs after compilation because it checks that
     * the callees were compiled.
     */
    static void prepare(HostedUniverse hUniverse) {
        BuildTimeInterpreterUniverse iUniverse = BuildTimeInterpreterUniverse.singleton();
        OptionValues options = hUniverse.getBigBang().getOptions();
        Set<InterpreterResolvedObjectType> types = new LinkedHashSet<>();
        Set<InterpreterResolvedJavaMethod> changed = new LinkedHashSet<>();
        int kept = 0;
        int dropped = 0;
        long keptBytecodeBytes = 0;
        for (InterpreterResolvedJavaMethod method : iUniverse.getMethods()) {
            if (method.getInterpretedCode() == null || !isCandidate(method.getOriginalMethod())) {
                continue;
            }
            changed.add(method);
            String reason = rejectionReason(hUniverse, iUniverse, options, method.getOriginalMethod());
            if (reason == null) {
                method.needMethodBody = true;
                types.add(method.getDeclaringClass());
                kept++;
                keptBytecodeBytes += method.getInterpretedCode().length;
            } else {
                InterpreterUtil.log("[ristretto-inline-aot] dropping %s: %s", method, reason);
                method.setCode(null);
                dropped++;
            }
        }
        iUniverse.createConstantPools(hUniverse, types);
        for (InterpreterResolvedObjectType type : types) {
            /* The constant pools filled in the metadata of every declared method. */
            changed.addAll(iUniverse.allDeclaredMethods(type));
        }
        rescan(hUniverse, types, changed);
        long constantPoolEntries = 0;
        for (InterpreterResolvedObjectType type : types) {
            constantPoolEntries += type.getConstantPool().length();
        }
        InterpreterUtil.log("[ristretto-inline-aot] %s methods in %s classes keep bytecodes, %s dropped", kept, types.size(), dropped);
        InterpreterUtil.log("[ristretto-inline-aot] %s bytes of bytecodes, %s constant pool entries", keptBytecodeBytes, constantPoolEntries);
    }

    /**
     * The interpreter metadata is already in the image heap, which holds snapshots of its fields:
     * patch the fields that changed after analysis. Bytecodes are patched in place by the constant
     * pool builder, so they get a new array, which the snapshot of an unchanged reference would
     * not pick up.
     */
    private static void rescan(HostedUniverse hUniverse, Set<InterpreterResolvedObjectType> types, Set<InterpreterResolvedJavaMethod> methods) {
        ImageHeapScanner scanner = hUniverse.getBigBang().getUniverse().getHeapScanner();
        ScanReason reason = new OtherReason("Ristretto inlining of AOT methods: metadata changed after analysis");
        ResolvedJavaType methodType = GuestAccess.get().lookupType(InterpreterResolvedJavaMethod.class);
        ResolvedJavaField[] methodFields = {
                        JVMCIReflectionUtil.getUniqueDeclaredField(methodType, "interpretedCode"),
                        JVMCIReflectionUtil.getUniqueDeclaredField(methodType, "exceptionHandlers"),
                        JVMCIReflectionUtil.getUniqueDeclaredField(methodType, "lineNumberTable"),
                        JVMCIReflectionUtil.getUniqueDeclaredField(methodType, "localVariableTable"),
                        JVMCIReflectionUtil.getUniqueDeclaredField(methodType, "nativeEntryPoint")};
        for (InterpreterResolvedJavaMethod method : methods) {
            byte[] code = method.getInterpretedCode();
            if (code != null) {
                method.setCode(code.clone());
            }
            for (ResolvedJavaField field : methodFields) {
                scanner.rescanField(method, field, reason);
            }
        }
        ResolvedJavaField constantPoolField = JVMCIReflectionUtil.getUniqueDeclaredField(GuestAccess.get().lookupType(InterpreterResolvedObjectType.class), "constantPool");
        for (InterpreterResolvedObjectType type : types) {
            scanner.rescanField(type, constantPoolField, reason);
        }
    }

    /** Returns why Ristretto cannot use the bytecodes of {@code method}, or null if it can. */
    private static String rejectionReason(HostedUniverse hUniverse, BuildTimeInterpreterUniverse iUniverse, OptionValues options, AnalysisMethod method) {
        if (hUniverse.getBigBang().getProviders(method).getGraphBuilderPlugins().getInvocationPlugins().lookupInvocation(method, options) != null) {
            return "intrinsified by an invocation plugin";
        }
        try {
            for (ExceptionHandler handler : method.getExceptionHandlers()) {
                if (!handler.isCatchAll() && !(iUniverse.typeOrUnresolved(handler.getCatchType()) instanceof InterpreterResolvedJavaType)) {
                    return "catch type not in the image";
                }
            }
        } catch (LinkageError | RuntimeException | AnalysisError e) {
            return "cannot resolve a catch type: " + e;
        }
        byte[] code = method.getCode();
        ConstantPool cp = method.getConstantPool();
        for (int bci = 0; bci < BytecodeStream.endBCI(code); bci = BytecodeStream.nextBCI(code, bci)) {
            int bytecode = BytecodeStream.currentBC(code, bci);
            try {
                switch (bytecode) {
                    case INVOKEDYNAMIC, JSR, JSR_W, RET -> {
                        return "uses bytecode " + bytecode;
                    }
                    case INVOKEVIRTUAL, INVOKEINTERFACE, INVOKESPECIAL, INVOKESTATIC -> {
                        if (bytecode == INVOKEINTERFACE) {
                            /*
                             * Inlining these (HashMap.forEach, AtomicLong.getAndAccumulate and other
                             * methods that call a functional interface) into OSR code can resume
                             * with a null after deoptimization (elide-dev/graal#123).
                             */
                            return "invokeinterface at bci " + bci;
                        }
                        JavaMethod callee = cp.lookupMethod(BytecodeStream.readCPI(code, bci), bytecode);
                        if (!(callee instanceof ResolvedJavaMethod resolvedCallee)) {
                            return "unresolved callee at bci " + bci;
                        }
                        /*
                         * The builder JVM may already have linked a signature-polymorphic site, in
                         * which case the lookup returns its invokehandle adapter (for example the
                         * static VarHandleGuards.guard_LI_L for VarHandle.getVolatile), so check the
                         * referenced holder too (elide-dev/graal#113).
                         */
                        String holder = resolvedCallee.getDeclaringClass().getName();
                        String referencedHolder = cp.lookupReferencedType(BytecodeStream.readCPI(code, bci), bytecode).getName();
                        if (holder.equals(METHOD_HANDLE) || holder.equals(VAR_HANDLE) || referencedHolder.equals(METHOD_HANDLE) || referencedHolder.equals(VAR_HANDLE)) {
                            return "signature-polymorphic call at bci " + bci;
                        }
                        if (resolvedCallee.isStatic() != (bytecode == INVOKESTATIC)) {
                            return "callee does not match the invoke kind at bci " + bci + ": " + resolvedCallee.format("%H.%n(%p)");
                        }
                        if (!(iUniverse.methodOrUnresolved(callee) instanceof InterpreterResolvedJavaMethod)) {
                            return "callee not in the interpreter metadata at bci " + bci + ": " + resolvedCallee.format("%H.%n(%p)");
                        }
                        if (!isCallable(hUniverse, resolvedCallee, bytecode)) {
                            return "callee neither compiled nor dispatchable at bci " + bci + ": " + resolvedCallee.format("%H.%n(%p)");
                        }
                    }
                    case GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC -> {
                        JavaField field = cp.lookupField(BytecodeStream.readCPI(code, bci), method, bytecode);
                        if (!(iUniverse.fieldOrUnresolved(field) instanceof InterpreterResolvedJavaField)) {
                            return "field not in the image at bci " + bci;
                        }
                        /*
                         * Constant-folded static fields and fields that are never read have no
                         * location, which neither Ristretto nor the interpreter can access.
                         */
                        HostedField hField = hUniverse.optionalLookup(field);
                        if (hField == null || !hField.hasLocation()) {
                            return "field without a location at bci " + bci + ": " + field.format("%H.%n");
                        }
                    }
                    case NEW, ANEWARRAY, MULTIANEWARRAY, CHECKCAST, INSTANCEOF -> {
                        JavaType type = cp.lookupType(BytecodeStream.readCPI(code, bci), bytecode);
                        if (!(iUniverse.typeOrUnresolved(type) instanceof InterpreterResolvedJavaType)) {
                            return "type not in the image at bci " + bci;
                        }
                    }
                    case LDC, LDC_W, LDC2_W -> {
                        Object constant = cp.lookupConstant(BytecodeStream.readCPI(code, bci));
                        if (constant instanceof JavaType type) {
                            if (!(iUniverse.typeOrUnresolved(type) instanceof InterpreterResolvedJavaType)) {
                                return "class constant not in the image at bci " + bci;
                            }
                        } else if (!(constant instanceof JavaConstant)) {
                            return "unsupported constant at bci " + bci;
                        }
                    }
                    default -> {
                    }
                }
            } catch (LinkageError | RuntimeException | AnalysisError e) {
                /* For example, a type that the analysis never reached. */
                return "cannot resolve the constant pool entry at bci " + bci + ": " + e;
            }
        }
        return null;
    }

    /** Whether Ristretto code (or the interpreter) can call {@code callee} without inlining it. */
    private static boolean isCallable(HostedUniverse hUniverse, ResolvedJavaMethod callee, int bytecode) {
        HostedMethod hCallee = hUniverse.optionalLookup(callee);
        if (hCallee == null) {
            return false;
        }
        if (hCallee.isCompiled()) {
            return true;
        }
        if ((bytecode == INVOKEVIRTUAL || bytecode == INVOKEINTERFACE) && hCallee.hasVTableIndex()) {
            return true;
        }
        HostedMethod[] implementations = hCallee.getImplementations();
        return implementations.length == 1 && implementations[0].isCompiled();
    }
}
