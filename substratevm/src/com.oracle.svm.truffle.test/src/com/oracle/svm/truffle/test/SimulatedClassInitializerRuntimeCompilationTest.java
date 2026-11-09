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
package com.oracle.svm.truffle.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.test.NativeImageBuildArgs;
import com.oracle.svm.truffle.test.runtimeinit.RuntimeInitializedHolder;
import com.oracle.svm.truffle.test.runtimeinit.SimulatedHolder;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Runtime compiled Truffle code that uses classes initialized at image run time
 * (elide-dev/graal#209):
 * <ul>
 * <li>The class initializer of {@link SimulatedHolder} is simulated at build time. The analysis
 * folds its static final field into the runtime compiled graph as an image heap constant that has
 * no backing object in the image builder. Encoding that graph for runtime compilation must keep the
 * constant.</li>
 * <li>{@link RuntimeInitializedHolder} cannot be simulated. Its instance method needs no class
 * initialization check, so it is inlined into the runtime compiled graph of the root, which then
 * needs a deoptimization target for it.</li>
 * </ul>
 * Classes of the {@code com.oracle.svm} packages are initialized at build time by default, so the
 * classes initialized at run time are in their own package.
 */
@NativeImageBuildArgs({
                "--macro:truffle",
                "-H:MaxRuntimeCompileMethods=2000",
                "--initialize-at-run-time=com.oracle.svm.truffle.test.runtimeinit",
})
public class SimulatedClassInitializerRuntimeCompilationTest {

    /** Whether the most recent execution of a root ran in compiled code. */
    static volatile boolean lastExecutionCompiled;

    @TruffleBoundary
    public static int boundary(int value) {
        return value;
    }

    static final class SimulatedConstantRoot extends RootNode {
        SimulatedConstantRoot() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object result = SimulatedHolder.VALUE;
            lastExecutionCompiled = CompilerDirectives.inCompiledCode();
            return result;
        }

        @Override
        public String getName() {
            return "simulated-constant";
        }
    }

    static final class RuntimeInitializedRoot extends RootNode {
        RuntimeInitializedRoot() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            int result = ((RuntimeInitializedHolder) args[0]).add((int) args[1]);
            lastExecutionCompiled = CompilerDirectives.inCompiledCode();
            return result;
        }

        @Override
        public String getName() {
            return "runtime-initialized";
        }
    }

    private Context context;

    @Before
    public void setUp() {
        Assume.assumeTrue("Needs runtime compilation in a native image", ImageInfo.inImageRuntimeCode());
        context = Context.newBuilder().allowExperimentalOptions(true) //
                        .option("engine.CompileImmediately", "true") //
                        .option("engine.BackgroundCompilation", "false") //
                        .option("engine.MultiTier", "false") //
                        .option("engine.CompilationFailureAction", "Throw") //
                        .build();
        context.enter();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    @Test
    public void testSimulatedConstant() {
        CallTarget target = new SimulatedConstantRoot().getCallTarget();
        boolean compiled = false;
        for (int attempt = 0; attempt < 10 && !compiled; attempt++) {
            Object result = target.call();
            /* The compiled code must reference the image heap object of the simulated field. */
            assertSame(SimulatedHolder.VALUE, result);
            assertEquals("simulated", ((SimulatedHolder.Marker) result).name);
            compiled = lastExecutionCompiled;
        }
        assertTrue("root did not run in compiled code", compiled);
    }

    @Test
    public void testRuntimeInitializedCallee() {
        RuntimeInitializedHolder holder = new RuntimeInitializedHolder(40);
        assertTrue(RuntimeInitializedHolder.CREATED != 0);
        CallTarget target = new RuntimeInitializedRoot().getCallTarget();
        boolean compiled = false;
        for (int attempt = 0; attempt < 10 && !compiled; attempt++) {
            assertEquals(42 + attempt, (int) target.call(holder, 2 + attempt));
            compiled = lastExecutionCompiled;
        }
        assertTrue("root did not run in compiled code", compiled);
        /* Deoptimizes within the inlined method and continues in its deoptimization target. */
        assertEquals(10, (int) target.call(holder, -50));
        assertEquals(43, (int) target.call(holder, 3));
    }
}
