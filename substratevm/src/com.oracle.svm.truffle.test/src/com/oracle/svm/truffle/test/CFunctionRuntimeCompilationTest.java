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
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.InvokeCFunctionPointer;
import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.guest.staging.c.CGlobalDataFactory;
import com.oracle.svm.test.NativeImageBuildArgs;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Calls to C functions through the C interface from Truffle code that is runtime compiled. The
 * three shapes are those of generated bindings that guest languages call from hot, partially
 * evaluated code:
 * <ol>
 * <li>a {@linkplain CFunction.Transition#NO_TRANSITION non-transitioning} "leaf"
 * {@link InvokeCFunctionPointer} that is uninterruptible and may be inlined, called on a function
 * pointer from {@link CGlobalDataFactory#forSymbol};</li>
 * <li>an {@link InvokeCFunctionPointer} with the default {@link CFunction.Transition#TO_NATIVE
 * TO_NATIVE} transition;</li>
 * <li>a {@link CFunction} native method, also with the default transition.</li>
 * </ol>
 * Each must compile (the engine throws on a compilation failure), run compiled, and return correct
 * results, also while another thread triggers collections. The leaf call is expected to be inlined
 * into the compiled code; the transitioning calls must be emitted as calls to their AOT-compiled
 * call stubs, since the stack-allocated frame anchor of a transition cannot live in a frame that
 * may be deoptimized.
 */
@NativeImageBuildArgs({
                "--macro:truffle",
                "-H:MaxRuntimeCompileMethods=2000",
                // Compiling in isolates aborts when installing typed entries (elide-dev/graal#194).
                // Remove these three options with that fix.
                "-H:+UnlockExperimentalVMOptions",
                "-H:-SupportCompileInIsolates",
                "-H:-UnlockExperimentalVMOptions",
})
public class CFunctionRuntimeCompilationTest {

    enum Kind {
        LEAF,
        TRANSITION,
        CFUNCTION
    }

    /** Escape hatch for allocations made in compiled code, so they are not scalar-replaced. */
    static volatile Object sink;

    /** Whether the most recent execution of a {@link CallRoot} ran in compiled code. */
    static volatile boolean lastExecutionCompiled;

    /**
     * Sums {@code |value - i|} for {@code iterations} values, calling the C function once per
     * iteration through the binding of {@link #kind}. With {@code allocate}, every iteration also
     * allocates, so collections can happen while the loop runs.
     */
    static final class CallRoot extends RootNode {
        private final Kind kind;

        CallRoot(Kind kind) {
            super(null);
            this.kind = kind;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            long value = (long) args[0];
            int iterations = (int) args[1];
            boolean allocate = (boolean) args[2];
            long sum = 0;
            for (int i = 0; i < iterations; i++) {
                long result = call(value - i);
                sum += result;
                if (allocate) {
                    sink = new long[]{result};
                }
            }
            lastExecutionCompiled = CompilerDirectives.inCompiledCode();
            return sum;
        }

        private long call(long value) {
            switch (kind) {
                case LEAF:
                    return CFunctionRuntimeCompilationBindings.leaf(value);
                case TRANSITION:
                    return CFunctionRuntimeCompilationBindings.transition(value);
                case CFUNCTION:
                    return CFunctionRuntimeCompilationBindings.cFunction(value);
                default:
                    throw CompilerDirectives.shouldNotReachHere();
            }
        }

        @Override
        public String getName() {
            return "cfunction-" + kind;
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

    private static long expected(long value, int iterations) {
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += Math.abs(value - i);
        }
        return sum;
    }

    /** Compiles the root and checks that the compiled code runs and computes the right sums. */
    private static CallTarget compileAndCheck(Kind kind) {
        CallTarget target = new CallRoot(kind).getCallTarget();
        boolean compiled = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            long value = 17 + attempt;
            int iterations = 100 + attempt;
            assertEquals(expected(value, iterations), (long) target.call(value, iterations, false));
            if (lastExecutionCompiled) {
                compiled = true;
                break;
            }
        }
        assertTrue(kind + " root did not run in compiled code", compiled);
        return target;
    }

    private static void checkUnderGC(CallTarget target) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        Thread collector = new Thread(() -> {
            while (!stop.get()) {
                System.gc();
            }
        }, "gc-trigger");
        collector.start();
        try {
            for (int round = 0; round < 200; round++) {
                long value = -1000 + round;
                int iterations = 2000;
                assertEquals(expected(value, iterations), (long) target.call(value, iterations, true));
                assertTrue("compiled code was invalidated during round " + round, lastExecutionCompiled);
            }
        } finally {
            stop.set(true);
            collector.join();
        }
    }

    /** Reports the per-call cost from compiled code; informational only. */
    private static void report(Kind kind, CallTarget target) {
        int iterations = 20_000_000;
        target.call(0L, iterations, false);
        long start = System.nanoTime();
        long sum = (long) target.call(0L, iterations, false);
        long nanos = System.nanoTime() - start;
        assertEquals(expected(0, iterations), sum);
        assertTrue(lastExecutionCompiled);
        System.out.printf("%s: %.2f ns/call from runtime-compiled code (%d calls)%n", kind, (double) nanos / iterations, iterations);
    }

    @Test
    public void testLeaf() throws InterruptedException {
        CallTarget target = compileAndCheck(Kind.LEAF);
        checkUnderGC(target);
        report(Kind.LEAF, target);
    }

    @Test
    public void testTransitioningFunctionPointer() throws InterruptedException {
        CallTarget target = compileAndCheck(Kind.TRANSITION);
        checkUnderGC(target);
        report(Kind.TRANSITION, target);
    }

    @Test
    public void testCFunction() throws InterruptedException {
        CallTarget target = compileAndCheck(Kind.CFUNCTION);
        checkUnderGC(target);
        report(Kind.CFUNCTION, target);
    }
}
