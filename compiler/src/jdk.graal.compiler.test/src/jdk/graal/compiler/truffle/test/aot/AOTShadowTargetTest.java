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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.runtime.OptimizedCallTarget;

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

    @Test
    public void refusingRootHasNoShadow() {
        assertNull(parse("refuse").createAOTShadow());
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
            assertEquals(i + 1, shadow.call(i));
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
            assertEquals(i + 1, shadow.call(i));
        }
        assertTrue("the shadow was called past the threshold", shadow.getCallCount() >= 100);
        assertFalse("hotness must never submit a shadow for compilation", shadow.isSubmittedForCompilation());
        assertFalse("hotness must never compile a shadow", shadow.isValid());
        assertFalse(shadow.shouldCompile());
    }
}
