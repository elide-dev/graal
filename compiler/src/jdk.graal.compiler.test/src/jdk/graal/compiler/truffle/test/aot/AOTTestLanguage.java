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
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionValues;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.dsl.AOTSupport;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.dsl.Introspectable;
import com.oracle.truffle.api.dsl.Introspection;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Test language for the AOT tier. A source "add:N" parses to a root computing {@code arg0 + N}
 * with a DSL node that is AOT-preparable; "refuse" parses to a root that refuses AOT; "throw"
 * throws; "recurse" calls itself {@code arg0} times; "call" calls, through a direct call, a trivial
 * callee that is executed while parsing (so, during pre-initialization, it is a live, initialized
 * target); "assume" returns 1 while the assumption {@link #STABLE} is valid, and 2 afterwards;
 * "buildinval" depends on {@link #BUILD_STABLE}, which the test image invalidates during the build
 * ({@link AOTTestBuildTimeInvalidationFeature}); "spin" loops until its assumption is found invalid
 * (see {@link SpinRoot}). The other sources are described with their roots: "throwcall"
 * ({@link ThrowCallRoot}), "deopt" ({@link DeoptRoot}), "drecurse" ({@link DirectRecurseRoot}),
 * "branch" ({@link BranchRoot}), "pollspin" ({@link PollSpinRoot}), "instr..." ({@link StatementRoot}),
 * "assumeloop" ({@link AssumeLoopRoot}), "jitstatic:a|b" ({@link StaticAssumeRoot}), "jitfield:a|b"
 * ({@link FieldAssumeRoot}) and "throwinval" ({@link ThrowAssumeRoot}).
 */
@TruffleLanguage.Registration(id = AOTTestLanguage.ID, name = AOTTestLanguage.ID, contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
@ProvidedTags(StandardTags.StatementTag.class)
public final class AOTTestLanguage extends TruffleLanguage<Object> {
    /* Not "AOTTestLanguage": that id is taken by the AOTTutorial language in truffle-test. */
    public static final String ID = "AOTTierTestLanguage";

    private static final LanguageReference<AOTTestLanguage> REFERENCE = LanguageReference.create(AOTTestLanguage.class);
    private static final ContextReference<Object> CONTEXT = ContextReference.create(AOTTestLanguage.class);

    /**
     * A context whose mode differs from the mode of the pre-initialized context (the default) has
     * options incompatible with it ({@link #areOptionsCompatible}), so it cannot use it.
     */
    @Option(help = "The mode of the context; contexts of different modes have incompatible options.", category = OptionCategory.USER) //
    static final OptionKey<String> Mode = new OptionKey<>("");

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

    /** Test hook: the assumption the "pollspin" root depends on. */
    static final Assumption POLL_STABLE = Truffle.getRuntime().createAssumption("aot-poll-test");

    /** Test hook: the assumption the "assumeloop" root depends on. */
    static final Assumption LOOP_STABLE = Truffle.getRuntime().createAssumption("aot-loop-test");

    /**
     * Test hooks: the static final assumptions of the "jitstatic:a" and "jitstatic:b" roots, read
     * directly by {@link StaticAssumeRoot#execute}, so the image builder sees them as constants.
     */
    static final Assumption JIT_STATIC_A = Truffle.getRuntime().createAssumption("aot-jit-static-a");
    static final Assumption JIT_STATIC_B = Truffle.getRuntime().createAssumption("aot-jit-static-b");

    /** Test hooks: the assumptions of the "jitfield:a" and "jitfield:b" roots, in instance fields. */
    static final Assumption JIT_FIELD_A = Truffle.getRuntime().createAssumption("aot-jit-field-a");
    static final Assumption JIT_FIELD_B = Truffle.getRuntime().createAssumption("aot-jit-field-b");

    /** Test hook: the assumption the "throwinval" root depends on. */
    static final Assumption THROW_STABLE = Truffle.getRuntime().createAssumption("aot-throw-test");

    /** Invalidates {@link #THROW_STABLE}. */
    public static void invalidateThrowStable() {
        THROW_STABLE.invalidate();
    }

    /** Invalidates {@link #POLL_STABLE}. */
    public static void invalidatePollStable() {
        POLL_STABLE.invalidate();
    }

    /** Invalidates {@link #LOOP_STABLE}. */
    public static void invalidateLoopStable() {
        LOOP_STABLE.invalidate();
    }

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
    protected OptionDescriptors getOptionDescriptors() {
        return new AOTTestLanguageOptionDescriptors();
    }

    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return firstOptions.get(Mode).equals(newOptions.get(Mode));
    }

    /* A context is used by several threads in AOTTierNativeTest. */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return parseTarget(this, request.getSource().getCharacters().toString(), request.getSource());
    }

    /**
     * Submits {@code action} to {@code thread}, a thread of the current context; the caller must be
     * entered in it.
     */
    public static void submitThreadLocal(Thread thread, ThreadLocalAction action) {
        ((Env) CONTEXT.get(null)).submitThreadLocal(new Thread[]{thread}, action);
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
        return parseTarget(language, code, null);
    }

    /**
     * Parses {@code code}, the characters of {@code source}, if any: the roots of some sources have
     * source sections, for guest stack traces.
     */
    static CallTarget parseTarget(AOTTestLanguage language, String code, Source source) {
        String trimmed = code.trim();
        SourceSection section = source == null ? null : source.createSection(0, source.getLength());
        RootNode root;
        if (trimmed.startsWith("add:")) {
            root = new AddRoot(language, Integer.parseInt(trimmed.substring(4)));
        } else if (trimmed.equals("refuse")) {
            root = new RefusingRoot(language);
        } else if (trimmed.equals("throw")) {
            root = new ThrowRoot(language, "throw", section);
        } else if (trimmed.equals("throwcall")) {
            /* The callee is not executed: it is compiled at build time too. */
            ThrowRoot callee = new ThrowRoot(language, "thrower", source == null ? null : source.createSection(0, "throw".length()));
            root = new ThrowCallRoot(language, callee.getCallTarget(), section);
        } else if (trimmed.equals("throwinval")) {
            ThrowRoot callee = new ThrowRoot(language, "throwinvalcallee", source == null ? null : source.createSection(0, "throw".length()));
            root = new ThrowAssumeRoot(language, callee.getCallTarget(), THROW_STABLE, section);
        } else if (trimmed.equals("deopt")) {
            root = new DeoptRoot(language, section);
        } else if (trimmed.equals("drecurse")) {
            DirectRecurseRoot recurse = new DirectRecurseRoot(language);
            recurse.self = recurse.insert(DirectCallNode.create(recurse.getCallTarget()));
            root = recurse;
        } else if (trimmed.equals("branch")) {
            root = new BranchRoot(language);
        } else if (trimmed.equals("pollspin")) {
            root = new PollSpinRoot(language);
        } else if (trimmed.startsWith("instr")) {
            root = new StatementRoot(language, trimmed, section);
        } else if (trimmed.equals("assumeloop")) {
            root = new AssumeLoopRoot(language);
        } else if (trimmed.equals("jitstatic:a") || trimmed.equals("jitstatic:b")) {
            root = new StaticAssumeRoot(language, trimmed.endsWith("b"));
        } else if (trimmed.equals("jitfield:a") || trimmed.equals("jitfield:b")) {
            root = new FieldAssumeRoot(language, trimmed.endsWith("b") ? JIT_FIELD_B : JIT_FIELD_A);
        } else if (trimmed.equals("loop")) {
            root = new LoopRoot(language);
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

    /** Throws a guest exception, located at this root, whose source section is the whole source. */
    public static final class ThrowRoot extends RootNode {
        /* A constant: string concatenation cannot be runtime compiled in a native image. */
        static final String MESSAGE = "thrown by throw";

        /** Test hook: the number of executions of any throwing root in compiled code. */
        public static volatile int compiledExecutions;

        private final String name;
        private final SourceSection section;

        ThrowRoot(TruffleLanguage<?> language, String name, SourceSection section) {
            super(language);
            this.name = name;
            this.section = section;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            throw new AOTTestException(MESSAGE, this);
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
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
     * "throwcall": calls, through a direct call, a callee "thrower" (a {@link ThrowRoot}, not
     * executed while parsing), so its exception crosses the call site.
     */
    public static final class ThrowCallRoot extends RootNode {
        /** Test hook: the number of executions of "throwcall" in compiled code. */
        public static volatile int compiledExecutions;

        private final SourceSection section;
        @Child DirectCallNode call;

        ThrowCallRoot(TruffleLanguage<?> language, CallTarget callee, SourceSection section) {
            super(language);
            this.section = section;
            this.call = DirectCallNode.create(callee);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            return call.call(frame.getArguments());
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
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
            return "throwcall";
        }
    }

    /**
     * "throwinval": like {@link ThrowCallRoot}, calls a callee "throwinvalcallee" that throws, but
     * its compiled code depends on {@link #THROW_STABLE}, so invalidating it can deoptimize an
     * activation while the exception crosses its call site or root.
     */
    public static final class ThrowAssumeRoot extends RootNode {
        private final Assumption assumption;
        private final SourceSection section;
        @Child DirectCallNode call;

        ThrowAssumeRoot(TruffleLanguage<?> language, CallTarget callee, Assumption assumption, SourceSection section) {
            super(language);
            this.assumption = assumption;
            this.section = section;
            this.call = DirectCallNode.create(callee);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (!assumption.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            return call.call(frame.getArguments());
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
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
            return "throwinval";
        }
    }

    /**
     * "deopt": returns {@code arg0 + 1} for a non-negative argument. For a negative one, it
     * transfers to the interpreter without invalidating, i.e., deoptimizes compiled code, which
     * stays valid; then it returns {@code -arg0}, or, for -1, throws.
     */
    public static final class DeoptRoot extends RootNode {
        static final String MESSAGE = "thrown after a deoptimization";

        /** Test hook: the number of executions of "deopt" in compiled code. */
        public static volatile int compiledExecutions;

        private final SourceSection section;

        DeoptRoot(TruffleLanguage<?> language, SourceSection section) {
            super(language);
            this.section = section;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            int n = (Integer) frame.getArguments()[0];
            if (n < 0) {
                CompilerDirectives.transferToInterpreter();
                if (n == -1) {
                    throw new AOTTestException(MESSAGE, this);
                }
                return -n;
            }
            return n + 1;
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
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
            return "deopt";
        }
    }

    /**
     * "drecurse": calls itself {@code arg0} times through a direct call of its (source) call
     * target, which a copy of the root keeps calling.
     */
    public static final class DirectRecurseRoot extends RootNode {
        /** Test hook: the number of executions of "drecurse" in compiled code. */
        public static volatile int compiledExecutions;

        @Child DirectCallNode self;

        DirectRecurseRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            int n = (Integer) frame.getArguments()[0];
            if (n <= 0) {
                return 0;
            }
            return 1 + (Integer) self.call(n - 1);
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
            return "drecurse";
        }
    }

    /**
     * "branch": profiles its paths with a {@link ConditionProfile} and a {@link BranchProfile} of
     * its own, not managed by the DSL: returns {@code arg0} if it is over 1000, {@code -arg0} if it
     * is negative, and {@code arg0 + 1} otherwise.
     */
    public static final class BranchRoot extends RootNode {
        /** Test hook: the number of executions of "branch" in compiled code. */
        public static volatile int compiledExecutions;

        final ConditionProfile big = ConditionProfile.create();
        final BranchProfile negative = BranchProfile.create();

        BranchRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (CompilerDirectives.inCompiledCode()) {
                compiledExecutions++;
            }
            int n = (Integer) frame.getArguments()[0];
            if (big.profile(n > 1000)) {
                return n;
            }
            if (n < 0) {
                negative.enter();
                return -n;
            }
            return n + 1;
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
            return "branch";
        }
    }

    /**
     * "pollspin": like {@link SpinRoot}, for {@link #POLL_STABLE}, but it polls Truffle safepoints
     * in its loop, so a thread-local action can stop the loop's thread at the poll.
     */
    public static final class PollSpinRoot extends RootNode {
        /** Test hook: set when an execution entered the loop. */
        public static volatile boolean started;
        /** Test hook: ends the loop regardless of the assumption. */
        public static volatile boolean stop;
        /** Test hook: whether the execution that entered the loop runs in compiled code. */
        public static volatile boolean startedInCompiledCode;

        PollSpinRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            startedInCompiledCode = CompilerDirectives.inCompiledCode();
            started = true;
            long iterations = 0;
            while (!stop) {
                TruffleSafepoint.poll(this);
                if (!POLL_STABLE.isValid()) {
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
            return "pollspin";
        }
    }

    /**
     * "loop": loops until {@link #stop} is set, and returns the number of iterations. It does not
     * poll Truffle safepoints itself: the compilation inserts the polls.
     */
    public static final class LoopRoot extends RootNode {
        /** Test hook: set when an execution entered the loop. */
        public static volatile boolean started;
        /** Test hook: whether the execution that entered the loop runs in compiled code. */
        public static volatile boolean startedInCompiledCode;
        /** Test hook: ends the loop. */
        public static volatile boolean stop;

        LoopRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            startedInCompiledCode = CompilerDirectives.inCompiledCode();
            started = true;
            long iterations = 0;
            while (!stop) {
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
            return "loop";
        }
    }

    /**
     * An instrumentable statement: returns {@code arg0 + 100}. Its source section is the whole
     * source.
     */
    @GenerateWrapper
    public abstract static class AOTStatementNode extends Node implements InstrumentableNode {
        public abstract Object execute(VirtualFrame frame);

        @Override
        public boolean isInstrumentable() {
            return getSourceSection() != null;
        }

        @Override
        public boolean hasTag(Class<? extends Tag> tag) {
            return tag == StandardTags.StatementTag.class;
        }

        @Override
        public WrapperNode createWrapper(ProbeNode probe) {
            return new AOTStatementNodeWrapper(this, probe);
        }
    }

    static final class ArgPlus100Node extends AOTStatementNode {
        private final SourceSection section;

        ArgPlus100Node(SourceSection section) {
            this.section = section;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return (Integer) frame.getArguments()[0] + 100;
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
        }
    }

    /** "instr...": a root whose body is one instrumentable statement, {@link ArgPlus100Node}. */
    public static final class StatementRoot extends RootNode {
        private final String name;
        private final SourceSection section;
        @Child AOTStatementNode body;

        StatementRoot(TruffleLanguage<?> language, String name, SourceSection section) {
            super(language);
            this.name = name;
            this.section = section;
            this.body = new ArgPlus100Node(section);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return body.execute(frame);
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
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
     * "assumeloop": sums {@code arg0} terms, each 1 while {@link #LOOP_STABLE} is valid and 2
     * afterwards. Compiled code folds the check. So an activation that is deoptimized in its loop
     * returns a sum between {@code arg0} and {@code 2 * arg0}, and a call that starts after the
     * assumption was invalidated returns {@code 2 * arg0}, unless it runs stale code.
     */
    public static final class AssumeLoopRoot extends RootNode {
        /** Read in the loop, so that compiled code cannot fold the sum. */
        static volatile int one = 1;

        AssumeLoopRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int n = (Integer) frame.getArguments()[0];
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (LOOP_STABLE.isValid()) {
                    sum += one;
                } else {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    sum += 2 * one;
                }
            }
            return sum;
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
            return "assumeloop";
        }
    }

    /**
     * "jitstatic:a|b": returns 1 while {@link #JIT_STATIC_A} (or {@link #JIT_STATIC_B}) is valid
     * and 2 afterwards, plus 10 in compiled code. The assumption is read from a static final field,
     * so the image builder, which initializes this class, parses the load as a constant: the
     * runtime compiled graph of this method must still leave the check of the assumption to the
     * partial evaluation at run time, which makes the compiled code depend on it.
     */
    public static final class StaticAssumeRoot extends RootNode {
        private final boolean b;

        StaticAssumeRoot(TruffleLanguage<?> language, boolean b) {
            super(language);
            this.b = b;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            boolean valid = b ? JIT_STATIC_B.isValid() : JIT_STATIC_A.isValid();
            return (valid ? 1 : 2) + (CompilerDirectives.inCompiledCode() ? 10 : 0);
        }

        @Override
        public String getName() {
            return b ? "jitstatic:b" : "jitstatic:a";
        }
    }

    /**
     * "jitfield:a|b": the same as {@link StaticAssumeRoot}, with the assumption in an instance
     * field, which the image builder does not see as a constant.
     */
    public static final class FieldAssumeRoot extends RootNode {
        private final Assumption assumption;

        FieldAssumeRoot(TruffleLanguage<?> language, Assumption assumption) {
            super(language);
            this.assumption = assumption;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return (assumption.isValid() ? 1 : 2) + (CompilerDirectives.inCompiledCode() ? 10 : 0);
        }

        @Override
        public String getName() {
            return assumption == JIT_FIELD_B ? "jitfield:b" : "jitfield:a";
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
