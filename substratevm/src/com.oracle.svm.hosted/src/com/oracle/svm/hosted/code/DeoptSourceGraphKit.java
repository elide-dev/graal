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
package com.oracle.svm.hosted.code;

import com.oracle.svm.common.meta.MethodVariant;
import com.oracle.svm.core.graal.nodes.DeoptEntryBeginNode;
import com.oracle.svm.core.graal.nodes.DeoptEntryNode;
import com.oracle.svm.core.graal.nodes.DeoptProxyAnchorNode;
import com.oracle.svm.core.graal.nodes.LoweredDeadEndNode;
import com.oracle.svm.hosted.nodes.DeoptProxyNode;
import com.oracle.svm.hosted.phases.HostedGraphKit;

import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.UnreachableBeginNode;
import jdk.graal.compiler.nodes.UnwindNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.ExceptionObjectNode;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Builds manually constructed ({@link HostedGraphKit}) graphs that are safe for compilation as a
 * {@linkplain SubstrateCompilationDirectives#DEOPT_TARGET_METHOD deoptimization target}: when a
 * {@link DeoptInfoProvider} is given, deoptimization entries are inserted wherever the
 * deoptimization sources of the method registered them, following the conventions of
 * {@link com.oracle.svm.hosted.phases.SharedGraphBuilderPhase}. Without a {@link DeoptInfoProvider}
 * (i.e., for all other variants), the same graph is built without entries.
 * <p>
 * The graph must use values only from the kit's frame state after each step during which a
 * deoptimization can occur, since the entries insert proxies for all frame state values.
 */
public final class DeoptSourceGraphKit {

    private DeoptSourceGraphKit() {
    }

    /** Answers where the deoptimization target being built has registered entries. */
    public static class DeoptInfoProvider {
        final MethodVariant method;

        public DeoptInfoProvider(MethodVariant method) {
            this.method = method;
        }

        boolean isDeoptEntry(int bci, FrameState.StackState stackState) {
            return SubstrateCompilationDirectives.singleton().isDeoptEntry(method, bci, stackState);
        }
    }

    private static boolean shouldInsertDeoptEntry(DeoptInfoProvider deoptInfo, int bci, FrameState.StackState stackState) {
        if (deoptInfo != null) {
            return deoptInfo.isDeoptEntry(bci, stackState);
        }
        return false;
    }

    /**
     * Inserts a deoptimization entry at the start of the method if one is registered.
     *
     * @return the next deoptimization index
     */
    public static int startMethod(HostedGraphKit kit, DeoptInfoProvider deoptInfo, int nextDeoptIndex) {
        if (deoptInfo != null) {
            FrameState initialState = kit.getGraph().start().stateAfter();
            if (shouldInsertDeoptEntry(deoptInfo, initialState.bci, FrameState.StackState.BeforePop)) {
                return appendDeoptWithExceptionUnwind(kit, initialState, initialState.bci, nextDeoptIndex, DeoptEntryNode.create());
            }
        }
        return nextDeoptIndex;
    }

    /**
     * Appends an invoke whose exception is unwound, with the deoptimization entries registered for
     * it. A non-void result is pushed on the kit's frame state.
     *
     * @return the next deoptimization index
     * @see com.oracle.svm.hosted.phases.SharedGraphBuilderPhase
     */
    public static int invokeWithDeoptAndExceptionUnwind(HostedGraphKit kit, DeoptInfoProvider deoptInfo, int initialNextDeoptIndex, ResolvedJavaMethod target, InvokeKind invokeKind,
                    ValueNode... args) {
        int bci = kit.bci();
        InvokeWithExceptionNode invoke = kit.startInvokeWithException(target, invokeKind, kit.getFrameState(), bci, args);
        invoke.setNodeSourcePosition(NodeSourcePosition.placeholder(kit.getGraph().method(), bci));
        if (invoke.getStackKind() != JavaKind.Void) {
            /*
             * As after an invoke bytecode: the result is on the stack of the state after the
             * invoke, so the deoptimization entries below proxy it too.
             */
            kit.getFrameState().push(invoke.getStackKind(), invoke);
        }
        kit.exceptionPart();
        ExceptionObjectNode exception = kit.exceptionObject();

        if (deoptInfo == null) {
            kit.append(new UnwindNode(exception));
            kit.endInvokeWithException();
            return initialNextDeoptIndex;
        }

        int nextDeoptIndex = initialNextDeoptIndex;

        if (shouldInsertDeoptEntry(deoptInfo, bci, FrameState.StackState.Rethrow)) {
            // Exception during invoke

            var exceptionDeopt = kit.add(DeoptEntryNode.create(invoke.bci()));
            exceptionDeopt.setStateAfter(exception.stateAfter().duplicate());
            var exceptionDeoptBegin = kit.add(new DeoptEntryBeginNode());
            int exceptionDeoptIndex = nextDeoptIndex++;
            ValueNode exceptionProxy = createDeoptProxy(kit, exceptionDeoptIndex, exceptionDeopt, exception);
            var unwind = kit.append(new UnwindNode(exceptionProxy));
            exception.setNext(exceptionDeopt);
            exceptionDeopt.setNext(exceptionDeoptBegin);
            exceptionDeoptBegin.setNext(unwind);

            var exceptionDeoptExceptionEdge = kit.add(new UnreachableBeginNode());
            exceptionDeoptExceptionEdge.setNext(kit.add(new LoweredDeadEndNode()));
            exceptionDeopt.setExceptionEdge(exceptionDeoptExceptionEdge);
        } else {
            kit.append(new UnwindNode(exception));
        }

        boolean needDeoptEntry = shouldInsertDeoptEntry(deoptInfo, invoke.stateAfter().bci, FrameState.StackState.BeforePop);
        boolean needDeoptProxy = shouldInsertDeoptEntry(deoptInfo, bci, FrameState.StackState.AfterPop);
        if (needDeoptEntry || needDeoptProxy) {
            kit.noExceptionPart();
            if (needDeoptEntry) {
                // Deopt entry after invoke without exception
                nextDeoptIndex = appendDeoptWithExceptionUnwind(kit, invoke.stateAfter(), invoke.stateAfter().bci, nextDeoptIndex, DeoptEntryNode.create(invoke.bci()));
            } else {
                // Only a proxy is needed
                nextDeoptIndex = appendDeoptProxyAnchorNode(kit, invoke.stateAfter(), nextDeoptIndex, invoke.bci());
            }
        }
        kit.endInvokeWithException();

        return nextDeoptIndex;
    }

    /** @see com.oracle.svm.hosted.phases.SharedGraphBuilderPhase */
    private static int appendDeoptWithExceptionUnwind(HostedGraphKit kit, FrameState state, int exceptionBci, int nextDeoptIndex, DeoptEntryNode deoptEntryNode) {
        var entry = kit.add(deoptEntryNode);
        entry.setStateAfter(state.duplicate());
        var begin = kit.append(new DeoptEntryBeginNode());
        ((FixedWithNextNode) begin.predecessor()).setNext(entry);
        entry.setNext(begin);

        ExceptionObjectNode exception = kit.add(new ExceptionObjectNode(kit.getMetaAccess()));
        entry.setExceptionEdge(exception);
        var exState = kit.getFrameState().copy();
        exState.clearStack();
        exState.push(JavaKind.Object, exception);
        exState.setRethrowException(true);
        exception.setStateAfter(exState.create(exceptionBci, exception));
        exception.setNext(kit.add(new UnwindNode(exception)));

        // Ensure later nodes see values from potential deoptimization
        kit.getFrameState().insertProxies(value -> createDeoptProxy(kit, nextDeoptIndex, entry, value));
        return nextDeoptIndex + 1;
    }

    /** @see com.oracle.svm.hosted.phases.SharedGraphBuilderPhase */
    private static int appendDeoptProxyAnchorNode(HostedGraphKit kit, FrameState state, int nextDeoptIndex, int invokeBci) {
        var anchor = kit.append(new DeoptProxyAnchorNode(invokeBci));
        anchor.setStateAfter(state.duplicate());

        // Ensure later nodes see values from potential deoptimization
        kit.getFrameState().insertProxies(value -> createDeoptProxy(kit, nextDeoptIndex, anchor, value));
        return nextDeoptIndex + 1;
    }

    private static ValueNode createDeoptProxy(HostedGraphKit kit, int nextDeoptIndex, FixedNode deoptTarget, ValueNode value) {
        return kit.getGraph().addOrUniqueWithInputs(DeoptProxyNode.create(value, deoptTarget, nextDeoptIndex));
    }
}
