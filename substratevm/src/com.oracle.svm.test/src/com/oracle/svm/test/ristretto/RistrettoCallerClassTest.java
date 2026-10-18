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
package com.oracle.svm.test.ristretto;

import static com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.awaitCompiled;
import static com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.invoke;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.FrameInfoQueryResult;
import com.oracle.svm.core.code.FrameSourceInfo;
import com.oracle.svm.core.deopt.DeoptimizationSupport;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.interpreter.InterpreterSupport;
import com.oracle.svm.core.jdk.StackTraceUtils;
import com.oracle.svm.core.stack.JavaStackFrameVisitor;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.StackFrameVisitor;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.guest.staging.jdk.InternalVMMethod;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Holder;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.One;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Probe;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.RuntimeClassLoader;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Shape;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Two;

/** Differential caller lookup across interpreted and compiled runtime-loaded code. */
public class RistrettoCallerClassTest {
    static final List<Class<?>> RUNTIME_CLASSES = List.of(Inner.class, Outer.class, Owner.class, RistrettoCallerForeign.class, Shape.class, One.class, Two.class, Holder.class);

    /**
     * Called by {@code RistrettoDeoptimizationTest.TestFeature} before analysis, which also registers
     * the class files of {@link #RUNTIME_CLASSES}.
     */
    static void beforeAnalysis(Feature.BeforeAnalysisAccess access) {
        RuntimeReflection.register(FrameProbe.class.getDeclaredMethods());
        try {
            /*
             * The run-time classes call these image methods, which the interpreter and Ristretto
             * code can only call while they have compiled code. The caller-sensitive ones are
             * @ForceInline, so reflection metadata alone does not compile them on their own.
             */
            RuntimeReflection.register(Class.class.getMethod("getDeclaredMethod", String.class, Class[].class), Class.class.getMethod("getDeclaredField", String.class),
                            Class.class.getMethod("getDeclaredConstructor", Class[].class));
            var accessImpl = (FeatureImpl.BeforeAnalysisAccessImpl) access;
            for (Executable callerSensitive : new Executable[]{Method.class.getMethod("invoke", Object.class, Object[].class), Field.class.getMethod("getInt", Object.class),
                            Constructor.class.getMethod("newInstance", Object[].class)}) {
                accessImpl.registerAsRoot(callerSensitive, true, "called by run-time loaded code in RistrettoCallerClassTest");
            }
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    @Before
    public void requireRistretto() {
        Assume.assumeTrue("Run with the explicit runtime-compilation test property", Boolean.getBoolean(RistrettoDeoptimizationTest.REQUIRE_RISTRETTO_PROPERTY));
    }

    public static final class Inner {
        public static Class<?> caller(boolean ignoreFirst, int[] compiled) {
            Class<?> result = FrameProbe.compare(ignoreFirst);
            compiled[0] += Probe.compiled();
            return result;
        }
    }

    public static final class Outer {
        public static Class<?> caller(boolean ignoreFirst, int[] compiled) {
            Class<?> result = Inner.caller(ignoreFirst, compiled);
            compiled[1] += Probe.compiled();
            return result;
        }

        public static long deoptimizing(Holder holder, int n, Runnable hook, int[] compiled) {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (i == n / 2 && hook != null) {
                    hook.run();
                }
                if (Inner.caller(false, compiled) != Inner.class || Inner.caller(true, compiled) != Outer.class) {
                    throw new AssertionError("caller identity changed across deoptimization");
                }
                sum += holder.shape.value();
                compiled[i < n / 2 ? 2 : 3] += Probe.compiled();
            }
            return sum;
        }

        public static long allocationProbe(int count, boolean full, int[] compiled) {
            long before = FrameProbe.allocatedBytes();
            for (int i = 0; i < count; i++) {
                if (FrameProbe.allocationCaller(full) != Outer.class) {
                    throw new AssertionError("allocation probe caller identity changed");
                }
            }
            long bytes = FrameProbe.allocatedBytes() - before;
            compiled[0] += Probe.compiled();
            return bytes;
        }

        public static Class<?> caught(int[] compiled) {
            try {
                FrameProbe.fail();
                throw new AssertionError("expected native callee exception");
            } catch (IllegalStateException expected) {
                Class<?> result = FrameProbe.compare(false);
                compiled[0] += Probe.compiled();
                return result;
            }
        }

        public static int access(int[] compiled) throws ReflectiveOperationException {
            int result = Owner.ownAccess();
            if (!RistrettoCallerForeign.denied()) {
                throw new AssertionError("foreign private access was allowed");
            }
            compiled[0] += Probe.compiled();
            return result;
        }
    }

    public static final class Owner {
        private static final int VALUE = 23;
        private final int value;

        private Owner(int value) {
            this.value = value;
        }

        private static int secret() {
            return 17;
        }

        public static int ownAccess() throws ReflectiveOperationException {
            int method = (int) Owner.class.getDeclaredMethod("secret").invoke(null);
            int field = Owner.class.getDeclaredField("VALUE").getInt(null);
            Owner object = Owner.class.getDeclaredConstructor(int.class).newInstance(31);
            return method + field + object.value;
        }
    }

    public static final class FrameProbe {
        static volatile int lastRoots;
        static volatile int lastStubs;
        static volatile int lastHandlerPairs;
        static volatile int lastDeoptimizedFrames;
        static volatile int lastSyntheticFrames;

        public static long allocatedBytes() {
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) {
                throw new AssertionError("thread allocation accounting unavailable");
            }
            long bytes = bean.getCurrentThreadAllocatedBytes();
            if (bytes < 0) {
                throw new AssertionError("thread allocation reading unavailable");
            }
            return bytes;
        }

        @NeverInline("Measure caller lookup separately from the differential semantic oracle.")
        public static Class<?> allocationCaller(boolean full) {
            Pointer sp = KnownIntrinsics.readCallerStackPointer();
            if (!full) {
                return StackTraceUtils.getCallerClass(sp, false);
            }
            AllocationCallerVisitor visitor = new AllocationCallerVisitor();
            JavaStackWalker.walkCurrentThread(sp, visitor);
            return visitor.result;
        }

        @NeverInline("Exercise unwinding from image code into a guest exception handler.")
        public static void fail() {
            throw new IllegalStateException("expected native callee exception");
        }

        @NeverInline("Materialize the current runtime caller while it remains on stack.")
        public static void materializeRuntimeCaller() {
            RuntimeFrameFinder finder = new RuntimeFrameFinder();
            JavaStackWalker.walkCurrentThread(KnownIntrinsics.readCallerStackPointer(), finder);
            if (finder.sp.isNull()) {
                throw new AssertionError("no active runtime caller frame");
            }
            if (!finder.alreadyDeoptimized) {
                /*
                 * Folded at build time: the default native-unittest image, which also contains
                 * this class, has no deoptimization support, so the call must not be parsed there.
                 */
                if (!DeoptimizationSupport.enabled()) {
                    throw new AssertionError("eager deoptimization needs an image with deoptimization support");
                }
                Deoptimizer.deoptimizeFrameEagerly(finder.sp, false, null);
            }
        }

        @NeverInline("Both visitors must start from the same live caller SP.")
        public static Class<?> compare(boolean ignoreFirst) {
            Pointer sp = KnownIntrinsics.readCallerStackPointer();
            Class<?> actual = StackTraceUtils.getCallerClass(sp, ignoreFirst);
            FullCallerVisitor reference = new FullCallerVisitor(ignoreFirst);
            JavaStackWalker.walkCurrentThread(sp, reference);
            lastRoots = reference.roots;
            lastStubs = reference.stubs;
            lastHandlerPairs = reference.handlerPairs;
            lastDeoptimizedFrames = reference.deoptimizedFrames;
            lastSyntheticFrames = reference.syntheticFrames;
            if (actual != reference.result) {
                throw new AssertionError("cursor and full caller lookup disagree");
            }
            return actual;
        }
    }

    /** Full metadata decoding of the same live runtime caller used by allocationCaller. */
    static final class AllocationCallerVisitor extends JavaStackFrameVisitor {
        Class<?> result;

        @Override
        public boolean visitFrame(FrameSourceInfo info, Pointer sp) {
            if (info.getSourceClass() == null) {
                return true;
            }
            result = info.getSourceClass();
            return false;
        }
    }

    static final class RuntimeFrameFinder extends StackFrameVisitor {
        Pointer sp = Word.nullPointer();
        boolean alreadyDeoptimized;

        @Override
        public boolean visitRegularFrame(Pointer frameSP, CodePointer ip, CodeInfo info) {
            if (!CodeInfoAccess.isAOTImageCode(info)) {
                sp = frameSP;
                return false;
            }
            return true;
        }

        @Override
        protected boolean visitDeoptimizedFrame(Pointer originalSP, CodePointer ip, DeoptimizedFrame frame) {
            sp = originalSP;
            alreadyDeoptimized = true;
            return false;
        }
    }

    /** Uses the unchanged full-frame decoder and independent known caller expectations below. */
    static final class FullCallerVisitor extends JavaStackFrameVisitor {
        private boolean ignoreFirst;
        Class<?> result;
        int roots;
        int stubs;
        int handlerPairs;
        int deoptimizedFrames;
        int syntheticFrames;

        FullCallerVisitor(boolean ignoreFirst) {
            this.ignoreFirst = ignoreFirst;
        }

        @Override
        public boolean visitRegularFrame(Pointer sp, CodePointer ip, CodeInfo codeInfo) {
            if (InterpreterSupport.isEnabled()) {
                var interpreter = InterpreterSupport.singleton();
                for (FrameInfoQueryResult frame = CodeInfoTable.lookupCodeInfoQueryResult(codeInfo, ip).getFrameInfo(); frame != null; frame = frame.getCaller()) {
                    if (frame.getSourceClass() == null && interpreter.getSyntheticMethodFrameInfo(frame) != null) {
                        syntheticFrames++;
                    }
                    if (interpreter.isInterpreterRoot(frame)) {
                        roots++;
                    }
                    if (interpreter.isInterpreterBytecodeHandlerStub(frame)) {
                        stubs++;
                    }
                    if (frame.getCaller() != null && interpreter.isInterpreterBytecodeHandlerStub(frame.getCaller())) {
                        handlerPairs++;
                    }
                }
            }
            return super.visitRegularFrame(sp, ip, codeInfo);
        }

        @Override
        protected boolean visitDeoptimizedFrame(Pointer sp, CodePointer ip, DeoptimizedFrame frame) {
            deoptimizedFrames++;
            return super.visitDeoptimizedFrame(sp, ip, frame);
        }

        @Override
        public boolean visitFrame(FrameSourceInfo info, Pointer sp) {
            if (ignoreFirst) {
                Class<?> type = info.getSourceClass();
                String method = info.getSourceMethodName();
                boolean intrinsic = type == MethodHandle.class && (method.equals("invoke") || method.equals("invokeExact") || method.equals("invokeBasic") || method.startsWith("linkTo"));
                if (type != null && !DynamicHub.fromClass(type).isVMInternal() && !intrinsic) {
                    ignoreFirst = false;
                }
                return true;
            }
            if (StackTraceUtils.ignoredBySecurityStackWalk(info)) {
                return true;
            }
            result = info.getSourceClass();
            return false;
        }
    }

    @InternalVMMethod
    static final class DeoptHook implements Runnable {
        final RuntimeClassLoader loader;
        final Object holder;
        final Field shape;
        final boolean eager;
        volatile boolean armed;
        Class<?> pendingCaller;
        Class<?> materializedCaller;
        int materializedFrames;

        DeoptHook(RuntimeClassLoader loader, Object holder, Field shape, boolean eager) {
            this.loader = loader;
            this.holder = holder;
            this.shape = shape;
            this.eager = eager;
        }

        @Override
        @NeverInline("Keep the compiled guest caller live below this image-code hook.")
        public void run() {
            if (!armed) {
                return;
            }
            try {
                if (eager) {
                    FrameProbe.materializeRuntimeCaller();
                    materializedCaller = FrameProbe.compare(false);
                    materializedFrames = FrameProbe.lastDeoptimizedFrames;
                }
                shape.set(holder, loader.loadClass(Two.class.getName()).getConstructor().newInstance());
                pendingCaller = FrameProbe.compare(false);
                System.gc();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    public void callerIdentityDuringAndAfterDeoptimization() throws Throwable {
        for (boolean eager : new boolean[]{false, true}) {
            RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
            Class<?> outer = loader.loadClass(Outer.class.getName());
            Class<?> holderClass = loader.loadClass(Holder.class.getName());
            Object holder = holderClass.getConstructor().newInstance();
            Field shape = holderClass.getField("shape");
            shape.set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
            Method call = outer.getMethod("deoptimizing", holderClass, int.class, Runnable.class, int[].class);
            int n = 100;
            DeoptHook hook = new DeoptHook(loader, holder, shape, eager);
            awaitCompiled("caller lookup in compiled loop", () -> {
                int[] compiled = new int[4];
                assertEquals(n, (long) invoke(call, holder, n, hook, compiled));
                return compiled[2] == n / 2 && compiled[3] == n / 2;
            });
            hook.armed = true;
            int[] compiled = new int[4];
            assertEquals(3L * n / 2, (long) invoke(call, holder, n, hook, compiled));
            assertSame(outer, hook.pendingCaller);
            if (eager) {
                assertSame(outer, hook.materializedCaller);
                assertTrue("materialized virtual frames were visited", hook.materializedFrames > 0);
            }
            assertEquals("loop ran compiled before invalidation", n / 2, compiled[2]);
            assertEquals("loop resumed in the interpreter", 0, compiled[3]);
        }
    }

    @Test
    public void concurrentMixedWalksDuringCompilationAndGc() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> inner = loader.loadClass(Inner.class.getName());
        Class<?> outer = loader.loadClass(Outer.class.getName());
        Method caller = outer.getMethod("caller", boolean.class, int[].class);
        Method access = outer.getMethod("access", int[].class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        assertSame(inner, invoke(caller, false, new int[2]));
                        assertSame(outer, invoke(caller, true, new int[2]));
                        assertEquals(71, (int) invoke(access, new int[1]));
                        if (i % 25 == 0) {
                            System.gc();
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent caller lookup failed", failure.get());
        }
    }

    @Test
    public void compiledCallerLookupAvoidsFullValueDecoding() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> outer = loader.loadClass(Outer.class.getName());
        Method probe = outer.getMethod("allocationProbe", int.class, boolean.class, int[].class);
        for (boolean full : new boolean[]{false, true}) {
            awaitCompiled("allocation probe with a live compiled caller", () -> {
                int[] compiled = new int[1];
                invoke(probe, 256, full, compiled);
                return compiled[0] == 1;
            });
        }
        int[] fastCompiled = new int[1];
        int[] fullCompiled = new int[1];
        long fast = (long) invoke(probe, 4_000, false, fastCompiled);
        long full = (long) invoke(probe, 4_000, true, fullCompiled);
        assertEquals("optimized caller must remain compiled", 1, fastCompiled[0]);
        assertEquals("full-decoder caller must remain compiled", 1, fullCompiled[0]);
        assertTrue("allocation readings must be valid", fast >= 0 && full > 0);
        assertTrue("compiled caller lookup must avoid full value decoding: fast=" + fast + ", full=" + full, fast * 10 <= full * 9);
    }

    @Test
    public void interpretedAndCompiledIdentityAndAccess() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> inner = loader.loadClass(Inner.class.getName());
        Class<?> outer = loader.loadClass(Outer.class.getName());
        Method caller = outer.getMethod("caller", boolean.class, int[].class);
        Method access = outer.getMethod("access", int[].class);
        for (boolean ignoreFirst : new boolean[]{false, true}) {
            int[] compiled = new int[2];
            assertSame(ignoreFirst ? outer : inner, invoke(caller, ignoreFirst, compiled));
            assertEquals("first inner invocation must be interpreted", 0, compiled[0]);
            assertEquals("first outer invocation must be interpreted", 0, compiled[1]);
            assertTrue("raw interpreter-root frames were visited", FrameProbe.lastRoots > 0);
            if (Boolean.getBoolean("com.oracle.svm.test.caller.expectStubs")) {
                assertTrue("generated handler stubs were visited: roots=" + FrameProbe.lastRoots + ", stubs=" + FrameProbe.lastStubs + ", pairs=" + FrameProbe.lastHandlerPairs,
                                FrameProbe.lastStubs > 0);
            }
            if (Boolean.getBoolean("com.oracle.svm.test.caller.expectHandlerPairs")) {
                assertTrue("inlined handler-plus-stub chains were visited: roots=" + FrameProbe.lastRoots + ", stubs=" + FrameProbe.lastStubs + ", pairs=" + FrameProbe.lastHandlerPairs,
                                FrameProbe.lastHandlerPairs > 0);
            }
        }
        Method exceptional = outer.getMethod("caught", int[].class);
        int[] interpretedCatch = new int[1];
        assertSame(outer, invoke(exceptional, interpretedCatch));
        assertEquals("first exception handler invocation must be interpreted", 0, interpretedCatch[0]);
        awaitCompiled("runtime exception handler caller lookup", () -> {
            int[] compiled = new int[1];
            assertSame(outer, invoke(exceptional, compiled));
            return compiled[0] == 1;
        });
        int[] interpretedAccess = new int[1];
        assertEquals(71, (int) invoke(access, interpretedAccess));
        assertEquals("first access check invocation must be interpreted", 0, interpretedAccess[0]);
        awaitCompiled("caller identity across nested runtime frames", () -> {
            for (boolean ignoreFirst : new boolean[]{false, true}) {
                int[] compiled = new int[2];
                assertSame(ignoreFirst ? outer : inner, invoke(caller, ignoreFirst, compiled));
                if (compiled[0] != 1 || compiled[1] != 1) {
                    return false;
                }
                assertTrue("null-source compiled frames were translated for ignoreFirst=" + ignoreFirst, FrameProbe.lastSyntheticFrames > 0);
            }
            return true;
        });
        awaitCompiled("runtime reflection access checks", () -> {
            int[] compiled = new int[1];
            assertEquals(71, (int) invoke(access, compiled));
            return compiled[0] == 1;
        });
        for (int i = 0; i < 100; i++) {
            assertSame(inner, invoke(caller, false, new int[2]));
            assertSame(outer, invoke(caller, true, new int[2]));
            assertEquals(71, (int) invoke(access, new int[1]));
        }
        assertTrue("distinct dynamic caller classes", inner != outer);
    }
}

/** Different nest from Owner, including when both are loaded at runtime. */
final class RistrettoCallerForeign {
    public static boolean denied() throws ReflectiveOperationException {
        boolean method = false;
        boolean field = false;
        boolean constructor = false;
        try {
            RistrettoCallerClassTest.Owner.class.getDeclaredMethod("secret").invoke(null);
        } catch (IllegalAccessException expected) {
            method = true;
        }
        try {
            RistrettoCallerClassTest.Owner.class.getDeclaredField("VALUE").getInt(null);
        } catch (IllegalAccessException expected) {
            field = true;
        }
        try {
            RistrettoCallerClassTest.Owner.class.getDeclaredConstructor(int.class).newInstance(1);
        } catch (IllegalAccessException expected) {
            constructor = true;
        }
        return method && field && constructor;
    }
}
