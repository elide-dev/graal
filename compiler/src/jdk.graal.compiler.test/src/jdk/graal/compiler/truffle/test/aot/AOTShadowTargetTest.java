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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedDirectCallNode;

import jdk.graal.compiler.truffle.test.TestWithPolyglotOptions;

public class AOTShadowTargetTest extends TestWithPolyglotOptions {

    /**
     * Creates a fresh context (left entered until the test ends) and parses {@code code} in it.
     */
    private OptimizedCallTarget parse(String code) {
        return parse(code, true);
    }

    private OptimizedCallTarget parse(String code, boolean multiTier) {
        Context context = setupContext(newContextBuilder().option("engine.BackgroundCompilation", "false").option("engine.MultiTier", String.valueOf(multiTier)) //
                        .option("engine.FirstTierCompilationThreshold", "10").option("engine.SingleTierCompilationThreshold", "10") //
                        .option("engine.AOTTierThreshold", "5"));
        context.initialize(AOTTestLanguage.ID);
        return (OptimizedCallTarget) AOTTestLanguage.parseTarget(AOTTestLanguage.get(), code);
    }

    @Test
    public void shadowIsPreparedAndLiveAstUntouched() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        assertNotNull(shadow);
        assertTrue(shadow.isAOTShadow());
        assertFalse(live.isAOTShadow());
        assertSame(live, shadow.getSourceCallTarget());
        assertTrue("live AST must stay uninitialized", ((AOTTestLanguage.AddRoot) live.getRootNode()).isUninitialized());
        assertFalse("shadow AST must be AOT-prepared", ((AOTTestLanguage.AddRoot) shadow.getRootNode()).isUninitialized());
    }

    /**
     * A live target with AOT code runs that code instead of its own AST, which the code counts on
     * the shadow: the live target may look never executed. An explicit synchronous compilation must
     * still not prepare its AST for AOT (prepareForAOT is only for never-executed targets).
     */
    @Test
    public void compileNeverPreparesLiveTargetWithAOTCode() {
        OptimizedCallTarget live = parse("add:1");
        assertNotNull(live.createAOTShadow());
        live.setAOTCode(reason -> {
        });
        assertFalse(live.wasExecuted());
        live.compile(true);
        assertTrue("live AST must stay uninitialized", ((AOTTestLanguage.AddRoot) live.getRootNode()).isUninitialized());
    }

    /**
     * A direct call node for the shadow, e.g., one the shadow's AST creates for its own
     * {@code getCallTarget()}, calls the live target: a call of a shadow is a call of its live
     * target. So splitting never clones a shadow, only the live target.
     */
    @Test
    public void directCallNodeForShadowCallsLiveTarget() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        AOTTestLanguage.CallRoot caller = new AOTTestLanguage.CallRoot(AOTTestLanguage.get(), shadow);
        OptimizedDirectCallNode call = (OptimizedDirectCallNode) caller.call;
        assertSame(live, call.getCallTarget());
        assertEquals(3, caller.getCallTarget().call(2));
        assertTrue(live.wasExecuted());
        assertFalse(shadow.wasExecuted());
    }

    @Test
    public void refusingRootHasNoShadow() {
        assertNull(parse("refuse").createAOTShadow());
    }

    /**
     * Runs the shadow's own code, like the AOT tier dispatch of its live target does (through the
     * target's code entry, not a call site): a call of a shadow is a call of its live target.
     */
    private static Object enterShadowCode(OptimizedCallTarget shadow, Object... args) {
        return shadow.callOSR(args);
    }

    /**
     * Calls to a shadow, e.g. by the shadow's AST calling its own call target, are calls to the live
     * target, which profiles them before its compiled code can rely on its profile.
     */
    @Test
    public void callOfShadowIsCallOfLiveTarget() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        assertEquals(2, shadow.call(1));
        assertEquals(3, shadow.call(new Object[]{2}));
        assertEquals("calls of the shadow run the live target", 2, live.getCallCount());
        assertEquals("the shadow itself is not called", 0, shadow.getCallCount());
        assertFalse("the live AST runs in the interpreter", ((AOTTestLanguage.AddRoot) live.getRootNode()).isUninitialized());
    }

    /** Calls {@code arguments[0]} with {@code arguments[1]}, a target that is not a PE constant. */
    private static final class IndirectCallerRoot extends RootNode {
        @Child IndirectCallNode call = IndirectCallNode.create();

        IndirectCallerRoot(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            return call.call((CallTarget) arguments[0], arguments[1]);
        }
    }

    /**
     * Code that calls a target that is not a PE constant compiles, and its calls of a shadow run the
     * live target. The redirection to the live target used to inline {@code callIndirect}
     * recursively, so that partial evaluation bailed out ("Too deep inlining").
     */
    @Test
    public void indirectCallOfNonConstantShadowCompilesAndCallsLiveTarget() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        OptimizedCallTarget caller = (OptimizedCallTarget) new IndirectCallerRoot(AOTTestLanguage.get()).getCallTarget();
        assertEquals(2, caller.call(shadow, 1));
        caller.compile(true);
        assertTrue("the caller must compile", caller.isValidLastTier());
        assertEquals(3, caller.call(shadow, 2));
        assertTrue("still compiled after calling the shadow", caller.isValidLastTier());
        assertEquals("calls of the shadow run the live target", 2, live.getCallCount());
        assertEquals("the shadow itself is not called", 0, shadow.getCallCount());
    }

    @Test
    public void firstTierShadowUnlinksLiveAtThreshold() {
        OptimizedCallTarget live = parse("add:1");
        OptimizedCallTarget shadow = live.createAOTShadow();
        AtomicReference<CharSequence> unlinked = new AtomicReference<>();
        live.setAOTCode(unlinked::set);
        shadow.compile(false); // first tier: counts via firstTierCall()
        assertTrue(shadow.isValid());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, enterShadowCode(shadow, i));
        }
        assertNotNull("threshold must unlink the live target's AOT code", unlinked.get());
        assertNull(live.getAOTCode());
        assertFalse("a shadow must never be compiled in the last tier", shadow.isValidLastTier());
    }

    @Test
    public void interpretedShadowIsNeverCompiledByHotnessMultiTier() {
        assertInterpretedShadowIsNeverCompiledByHotness(true);
    }

    @Test
    public void interpretedShadowIsNeverCompiledByHotnessSingleTier() {
        assertInterpretedShadowIsNeverCompiledByHotness(false);
    }

    /**
     * Calls a shadow in the interpreter well past the interpreter compilation threshold, which with
     * synchronous compilation would otherwise compile it (in the last tier without multi-tier).
     */
    private void assertInterpretedShadowIsNeverCompiledByHotness(boolean multiTier) {
        OptimizedCallTarget live = parse("add:1", multiTier);
        OptimizedCallTarget shadow = live.createAOTShadow();
        for (int i = 0; i < 100; i++) {
            assertEquals(i + 1, enterShadowCode(shadow, i));
        }
        assertTrue("the shadow was called past the threshold", shadow.getCallCount() >= 100);
        assertFalse("hotness must never submit a shadow for compilation", shadow.isSubmittedForCompilation());
        assertFalse("hotness must never compile a shadow", shadow.isValid());
        assertFalse(shadow.shouldCompile());
    }

    /**
     * The shadow is compiled without being executed: its exception profiles start generic, so
     * that its compiled code survives a guest exception thrown through its call site and its root.
     * The live target's profiles are not changed.
     */
    @Test
    public void shadowExceptionProfilesStartGeneric() {
        OptimizedCallTarget live = parse("throwcall");
        OptimizedCallTarget shadow = live.createAOTShadow();
        live.setAOTCode(reason -> {
        });
        shadow.compile(false);
        assertTrue(shadow.isValid());
        for (int i = 0; i < 3; i++) {
            assertThrows(AOTTestLanguage.AOTTestException.class, () -> enterShadowCode(shadow));
        }
        assertTrue("a guest exception must not invalidate the shadow's code", shadow.isValid());
    }

    /**
     * Profiles in node fields of the shadow's AST are replaced by disabled copies, so that the
     * shadow's compiled code survives every profiled path. The live AST keeps its own, still
     * uninitialized, profiles.
     */
    @Test
    public void shadowProfilesAreDisabledCopies() {
        OptimizedCallTarget live = parse("branch");
        OptimizedCallTarget shadow = live.createAOTShadow();
        AOTTestLanguage.BranchRoot liveRoot = (AOTTestLanguage.BranchRoot) live.getRootNode();
        AOTTestLanguage.BranchRoot shadowRoot = (AOTTestLanguage.BranchRoot) shadow.getRootNode();
        assertNotSame(liveRoot.big, shadowRoot.big);
        assertNotSame(liveRoot.negative, shadowRoot.negative);
        assertTrue(liveRoot.big.toString(), liveRoot.big.toString().contains("wasTrue=false, wasFalse=false"));
        assertTrue(liveRoot.negative.toString(), liveRoot.negative.toString().contains("UNINITIALIZED"));
        assertTrue(shadowRoot.big.toString(), shadowRoot.big.toString().contains("wasTrue=true, wasFalse=true"));
        assertTrue(shadowRoot.negative.toString(), shadowRoot.negative.toString().contains("VISITED"));

        live.setAOTCode(reason -> {
        });
        shadow.compile(false);
        assertTrue(shadow.isValid());
        assertEquals(2, enterShadowCode(shadow, 1));
        assertEquals(3, enterShadowCode(shadow, -3));
        assertEquals(2000, enterShadowCode(shadow, 2000));
        assertTrue("a profiled path must not invalidate the shadow's code", shadow.isValid());
    }
}
