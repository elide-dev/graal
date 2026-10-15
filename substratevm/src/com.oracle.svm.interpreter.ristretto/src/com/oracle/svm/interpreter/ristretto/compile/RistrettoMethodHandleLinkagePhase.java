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

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.oracle.svm.core.graal.nodes.DirectCallEntryMethodCallTargetNode;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.methodhandles.MethodHandleInterpreterUtils;
import com.oracle.svm.espresso.shared.meta.SignaturePolymorphicIntrinsic;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaType;
import com.oracle.svm.interpreter.ristretto.RistrettoOptions;
import com.oracle.svm.interpreter.ristretto.meta.RistrettoMethod;
import com.oracle.svm.interpreter.ristretto.meta.RistrettoType;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.replacements.nodes.MacroInvokable;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Lets calls to {@code MethodHandle.invokeBasic}, {@code linkToStatic} and {@code linkToSpecial}
 * that Ristretto could not resolve at compile time call their target directly (elide-dev/graal#112).
 * <p>
 * Such a call goes to the interpreter's enter stub for the intrinsic, which resolves the target
 * from the member name ({@code linkTo*}) or from the handle's lambda form ({@code invokeBasic}),
 * boxes the arguments and dispatches through {@code InterpreterToVM.dispatchInvocation}, even when
 * the target has compiled code: a lambda form chain makes several such calls per handle
 * invocation. This phase gives each such call the interpreter method its member name resolved to,
 * read from the member name when the call is made ({@link DirectCallEntryMethodCallTargetNode}).
 * Lowering then calls that method's direct call entry, which Ristretto publishes for the methods
 * it compiles and the interpreter for image methods it resolves, and the stub otherwise. The
 * member name is the last argument of a {@code linkTo*} call; the callee does not read it.
 * <p>
 * {@code linkToVirtual} and {@code linkToInterface} select their target from the receiver, and
 * stay with the stub. A {@code linkToStatic} target is called without a class initialization
 * check, as on HotSpot: the lambda forms of direct handles to methods of classes that are not
 * initialized initialize them first.
 */
public final class RistrettoMethodHandleLinkagePhase extends BasePhase<CoreProviders> {

    /** A member name that is not resolved, which stands in for a missing lambda form entry. */
    private static final Object UNRESOLVED_MEMBER_NAME = MethodHandleInterpreterUtils.newUnresolvedMemberName();

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        if (!RistrettoOptions.JITFastMethodHandleLinkage.getValue()) {
            return;
        }
        List<Node> calls = new ArrayList<>();
        for (Node node : graph.getNodes()) {
            if (node instanceof MacroInvokable macro && linkageIntrinsic(macro.getTargetMethod()) != null) {
                calls.add(node);
            } else if (node instanceof Invoke invoke && invoke.callTarget() instanceof MethodCallTargetNode callTarget &&
                            !(callTarget instanceof DirectCallEntryMethodCallTargetNode) && linkageIntrinsic(callTarget.targetMethod()) != null) {
                calls.add(node);
            }
        }
        if (calls.isEmpty()) {
            return;
        }
        Fields fields = Fields.get();
        for (Node node : calls) {
            Invoke invoke = node instanceof MacroInvokable macro ? macro.replaceWithInvoke() : (Invoke) node;
            if (invoke != null && invoke.asFixedNode().isAlive() && invoke.callTarget() instanceof MethodCallTargetNode callTarget) {
                link(graph, context, fields, invoke, callTarget);
            }
        }
    }

    private static void link(StructuredGraph graph, CoreProviders context, Fields fields, Invoke invoke, MethodCallTargetNode callTarget) {
        RistrettoMethod intrinsicMethod = (RistrettoMethod) callTarget.targetMethod();
        SignaturePolymorphicIntrinsic intrinsic = linkageIntrinsic(intrinsicMethod);
        ValueNode[] arguments = callTarget.arguments().toArray(ValueNode.EMPTY_ARRAY);
        if (arguments.length == 0) {
            return;
        }
        FixedNode before = invoke.asFixedNode();
        ValueNode memberName;
        if (intrinsic == SignaturePolymorphicIntrinsic.InvokeBasic) {
            ValueNode form = addLoad(graph, before, arguments[0], fields.form);
            ValueNode entry = addLoad(graph, before, form, fields.vmentry);
            /* The entry of a lambda form in the image heap is resolved on first use. */
            ConstantNode unresolved = ConstantNode.forConstant(context.getSnippetReflection().forObject(UNRESOLVED_MEMBER_NAME), context.getMetaAccess(), graph);
            memberName = conditional(graph, isNull(graph, entry), unresolved, entry);
        } else {
            memberName = arguments[arguments.length - 1];
        }
        ValueNode resolved = addLoad(graph, before, memberName, fields.resolved);
        /* The intrinsic itself never has a direct call entry, so the call takes the stub. */
        ConstantNode stub = ConstantNode.forConstant(context.getSnippetReflection().forObject(intrinsicMethod.getInterpreterMethod()), context.getMetaAccess(), graph);
        ValueNode entryHolder = conditional(graph, isNull(graph, resolved), stub, resolved);
        if (intrinsic == SignaturePolymorphicIntrinsic.LinkToSpecial) {
            /* A null receiver is left to the stub, which throws the NullPointerException. */
            entryHolder = conditional(graph, isNull(graph, arguments[0]), stub, entryHolder);
        }
        DirectCallEntryMethodCallTargetNode linked = graph.add(new DirectCallEntryMethodCallTargetNode(callTarget.invokeKind(), callTarget.targetMethod(), arguments, callTarget.returnStamp(),
                        callTarget.getTypeProfile(), entryHolder));
        callTarget.replaceAndDelete(linked);
    }

    /** Returns the intrinsic of {@code method} if this phase handles calls to it, or null. */
    private static SignaturePolymorphicIntrinsic linkageIntrinsic(Object method) {
        if (method instanceof RistrettoMethod ristrettoMethod) {
            SignaturePolymorphicIntrinsic intrinsic = ristrettoMethod.getInterpreterMethod().getSignaturePolymorphicIntrinsic();
            if (intrinsic == SignaturePolymorphicIntrinsic.InvokeBasic || intrinsic == SignaturePolymorphicIntrinsic.LinkToStatic || intrinsic == SignaturePolymorphicIntrinsic.LinkToSpecial) {
                return intrinsic;
            }
        }
        return null;
    }

    private static ValueNode addLoad(StructuredGraph graph, FixedNode before, ValueNode object, ResolvedJavaField field) {
        LoadFieldNode load = graph.add(LoadFieldNode.create(graph.getAssumptions(), object, field));
        graph.addBeforeFixed(before, load);
        return load;
    }

    private static LogicNode isNull(StructuredGraph graph, ValueNode value) {
        return graph.addOrUniqueWithInputs(IsNullNode.create(value));
    }

    private static ValueNode conditional(StructuredGraph graph, LogicNode condition, ValueNode trueValue, ValueNode falseValue) {
        return graph.addOrUniqueWithInputs(new ConditionalNode(condition, trueValue, falseValue));
    }

    /** The fields the linkage reads: {@code MethodHandle.form}, {@code LambdaForm.vmentry}, {@code MemberName.resolved}. */
    private record Fields(ResolvedJavaField form, ResolvedJavaField vmentry, ResolvedJavaField resolved) {

        private static volatile Fields fields;

        static Fields get() {
            Fields result = fields;
            if (result == null) {
                ResolvedJavaField form = instanceField(typeOf(MethodHandle.class), "form");
                ResolvedJavaField vmentry = instanceField((ResolvedJavaType) form.getType(), "vmentry");
                ResolvedJavaField resolved = instanceField(typeOf(UNRESOLVED_MEMBER_NAME.getClass()), "resolved");
                result = new Fields(form, vmentry, resolved);
                fields = result;
            }
            return result;
        }

        private static ResolvedJavaType typeOf(Class<?> c) {
            return RistrettoType.getOrCreate((InterpreterResolvedJavaType) DynamicHub.fromClass(c).getInterpreterType());
        }

        private static ResolvedJavaField instanceField(ResolvedJavaType type, String name) {
            for (ResolvedJavaField field : type.getInstanceFields(true)) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
            throw VMError.shouldNotReachHere("Missing field " + type.toJavaName() + "." + name);
        }
    }
}
