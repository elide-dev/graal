/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.test.aot;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.options.OptionValues;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.AOTSupport;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.Introspection;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Test language for the AOT tier. A source "add:N" parses to a root computing {@code arg0 + N}
 * with a DSL node that is AOT-preparable; "refuse" parses to a root that refuses AOT; "throw"
 * throws; "recurse" calls itself {@code arg0} times; "call" calls, through a direct call, a trivial
 * callee that is executed while parsing (so, during pre-initialization, it is a live, initialized
 * target); "assume" returns 1 while the assumption {@link #STABLE} is valid, and 2 afterwards;
 * "buildinval" depends on {@link #BUILD_STABLE}, which the test image invalidates during the build
 * ({@link AOTTestBuildTimeInvalidationFeature}); "spin" loops until its assumption is found invalid
 * (see {@link SpinRoot}).
 */
@TruffleLanguage.Registration(id = AOTTestLanguage.ID, name = AOTTestLanguage.ID, contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class AOTTestLanguage extends TruffleLanguage<Object> {
    /* Not "AOTTestLanguage": that id is taken by the AOTTutorial language in truffle-test. */
    public static final String ID = "AOTTierTestLanguage";

    private static final LanguageReference<AOTTestLanguage> REFERENCE = LanguageReference.create(AOTTestLanguage.class);

    /**
     * Test hook: the callee of the last parsed "call" source. Static rather than per language
     * instance, so that it is found in the image regardless of which engine adopted the
     * pre-initialized one.
     */
    public static volatile CalleeRoot lastCallee;

    /**
     * Test hook: the assumption the "assume" root depends on. Created when the language class is
     * initialized, i.e., in the image builder for the test image, so it is in the image heap.
     */
    static final Assumption STABLE = Truffle.getRuntime().createAssumption("aot-test");

    /**
     * Test hook: the assumption the "buildinval" root depends on, invalidated during the image
     * build after the root was compiled ({@link AOTTestBuildTimeInvalidationFeature}).
     */
    static final Assumption BUILD_STABLE = Truffle.getRuntime().createAssumption("aot-build-test");

    /** Test hook: the assumption the "spin" root depends on. */
    static final Assumption SPIN_STABLE = Truffle.getRuntime().createAssumption("aot-spin-test");

    /** Invalidates {@link #SPIN_STABLE}. */
    public static void invalidateSpinStable() {
        SPIN_STABLE.invalidate();
    }

    /** Invalidates {@link #STABLE}. */
    public static void invalidateStable() {
        STABLE.invalidate();
    }

    /** Invalidates {@link #BUILD_STABLE}. */
    public static void invalidateBuildStable() {
        BUILD_STABLE.invalidate();
    }

    @Override
    protected Object createContext(Env env) {
        return env;
    }

    @Override
    protected boolean patchContext(Object context, Env newEnv) {
        return true;
    }

    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return true;
    }

    /* A context is used by several threads in AOTTierNativeTest. */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return parseTarget(this, request.getSource().getCharacters().toString());
    }

    /** The language of the current context; the caller must be entered in a context. */
    public static AOTTestLanguage get() {
        return REFERENCE.get(null);
    }

    /**
     * Parses {@code code} to a fresh call target, exactly as {@link #parse(ParsingRequest)} does.
     * The caller must be entered in a context.
     */
    public static CallTarget parseTarget(AOTTestLanguage language, String code) {
        String trimmed = code.trim();
        RootNode root;
        if (trimmed.startsWith("add:")) {
            root = new AddRoot(language, Integer.parseInt(trimmed.substring(4)));
        } else if (trimmed.equals("refuse")) {
            root = new RefusingRoot(language);
        } else if (trimmed.equals("throw")) {
            root = new ThrowRoot(language);
        } else if (trimmed.equals("recurse")) {
            root = new RecurseRoot(language);
        } else if (trimmed.equals("assume")) {
            root = new AssumeRoot(language, "assume", STABLE, 1, 2);
        } else if (trimmed.equals("buildinval")) {
            root = new AssumeRoot(language, "buildinval", BUILD_STABLE, 7, 7);
        } else if (trimmed.equals("spin")) {
            root = new SpinRoot(language);
        } else if (trimmed.equals("call")) {
            CalleeRoot callee = new CalleeRoot(language);
            callee.getCallTarget().call(0);
            lastCallee = callee;
            root = new CallRoot(language, callee.getCallTarget());
        } else {
            throw new IllegalArgumentException(trimmed);
        }
        return root.getCallTarget();
    }

    /*
     * doLong deliberately does not replace doInt: AOT preparation activates every specialization
     * that is not replaced, so int arguments stay on the prepared fast path instead of deoptimizing
     * the build-time compiled code on the first call. doIntOverflow is the int specialization after
     * doInt overflowed (rewriteOn), which is a deoptimization out of the compiled code.
     */
    @GenerateAOT
    @Introspectable
    @SuppressWarnings("truffle-inlining")
    public abstract static class AddNode extends Node {
        abstract Object execute(Object left, int right);

        @Specialization(rewriteOn = ArithmeticException.class)
        static int doInt(int left, int right) {
            return Math.addExact(left, right);
        }

        @Specialization
        static long doIntOverflow(int left, int right) {
            return (long) left + right;
        }

        @Specialization
        static long doLong(long left, int right) {
            return left + right;
        }

        @Specialization
        static String doString(String left, int right) {
            return concat(left, right);
        }

        /* String concatenation cannot be runtime compiled in a native image. */
        @TruffleBoundary
        static String concat(String left, int right) {
            return left + right;
        }
    }

    public static final class AddRoot extends RootNode {
        final int constant;
        @Child AddNode add = AOTTestLanguageFactory.AddNodeGen.create();

        AddRoot(TruffleLanguage<?> language, int constant) {
            super(language, new FrameDescriptor());
            this.constant = constant;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return add.execute(frame.getArguments()[0], constant);
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected boolean isCloneUninitializedSupported() {
            return true;
        }

        @Override
        protected RootNode cloneUninitialized() {
            return new AddRoot(getLanguage(AOTTestLanguage.class), constant);
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            AOTSupport.prepareForAOT(this);
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "add" + constant;
        }

        /** Test hook: the DSL state of the add node, to prove the live AST is untouched. */
        public boolean isUninitialized() {
            return Introspection.getSpecializations(add).stream().noneMatch(Introspection.SpecializationInfo::isActive);
        }
    }

    /**
     * Returns {@code valid} while {@code assumption} is valid, and {@code invalid} afterwards.
     * Compiled code folds the check, relying on the assumption.
     */
    public static final class AssumeRoot extends RootNode {
        private final String name;
        private final Assumption assumption;
        private final int valid;
        private final int invalid;

        AssumeRoot(TruffleLanguage<?> language, String name, Assumption assumption, int valid, int invalid) {
            super(language);
            this.name = name;
            this.assumption = assumption;
            this.valid = valid;
            this.invalid = invalid;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (assumption.isValid()) {
                return valid;
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return invalid;
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /**
     * Loops until {@link AOTTestLanguage#SPIN_STABLE} is invalid, and returns -1; or, if
     * {@link #stop} is set first, returns the number of iterations. Compiled code folds the check
     * of the assumption, so its loop only ends with {@link #stop} unless the activation is
     * deoptimized: then the loop continues in the deoptimization target, which sees the invalid
     * assumption.
     */
    public static final class SpinRoot extends RootNode {
        /** Test hook: set when an execution entered the loop. */
        public static volatile boolean started;
        /** Test hook: ends the loop regardless of the assumption. */
        public static volatile boolean stop;

        SpinRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            started = true;
            long iterations = 0;
            while (!stop) {
                if (!SPIN_STABLE.isValid()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    return -1L;
                }
                iterations++;
            }
            return iterations;
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "spin";
        }
    }

    public static final class RefusingRoot extends RootNode {
        RefusingRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return 0;
        }

        @Override
        protected RootNode cloneForAOT() {
            return null;
        }

        @Override
        public String getName() {
            return "refuse";
        }
    }

    public static final class ThrowRoot extends RootNode {
        /* A constant: string concatenation cannot be runtime compiled in a native image. */
        static final String MESSAGE = "thrown by throw";

        ThrowRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            throw new AOTTestException(MESSAGE, this);
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "throw";
        }
    }

    public static final class RecurseRoot extends RootNode {
        /**
         * Test hook: the number of executions of any "recurse" root in compiled code, e.g., in
         * the AOT tier code of the live target (but not in the interpreter or a deoptimization
         * target).
         */
        public static int compiledExecutions;

        /**
         * Test hook: if set, the innermost execution records, for each guest frame on the stack
         * (innermost first), whether it has a call node.
         */
        public static volatile boolean captureFrames;
        public static volatile List<Boolean> capturedFramesHaveCallNode;

        @Child IndirectCallNode call = IndirectCallNode.create();

        RecurseRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            int n = (Integer) frame.getArguments()[0];
            if (n <= 0) {
                if (captureFrames) {
                    captureFrames();
                }
                return 0;
            }
            return 1 + (Integer) call.call(getCallTarget(), n - 1);
        }

        @TruffleBoundary
        private static void captureFrames() {
            List<Boolean> haveCallNode = new ArrayList<>();
            Truffle.getRuntime().iterateFrames(f -> {
                haveCallNode.add(f.getCallNode() != null);
                return null;
            });
            capturedFramesHaveCallNode = haveCallNode;
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "recurse";
        }
    }

    public static final class CallRoot extends RootNode {
        @Child DirectCallNode call;

        CallRoot(TruffleLanguage<?> language, CallTarget callee) {
            super(language);
            this.call = DirectCallNode.create(callee);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return call.call(frame.getArguments());
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "call";
        }
    }

    /** A trivial root, so that first-tier partial evaluation of a caller tries to inline it. */
    public static final class CalleeRoot extends RootNode {
        /** Test hook: whether this root was prepared for compilation in the image builder. */
        public volatile boolean preparedAtBuildTime;

        CalleeRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return 42;
        }

        @Override
        protected boolean isTrivial() {
            return true;
        }

        @Override
        protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
            if (ImageInfo.inImageBuildtimeCode()) {
                preparedAtBuildTime = true;
            }
            return true;
        }

        @Override
        public String getName() {
            return "callee";
        }
    }

    @SuppressWarnings("serial")
    public static final class AOTTestException extends AbstractTruffleException {
        AOTTestException(String message, Node location) {
            super(message, location);
        }
    }
}
