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
package com.oracle.svm.test.continuations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.c.InvokeJavaFunctionPointer;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.StackFrameVisitor;
import com.oracle.svm.core.thread.ContinuationInternals;
import com.oracle.svm.core.thread.Target_jdk_internal_vm_Continuation;
import com.oracle.svm.graal.SubstrateGraalUtils;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.graal.meta.SubstrateMethod;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.ModuleSupport;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.test.NativeImageBuildArgs;

import jdk.vm.ci.code.InstalledCode;

/**
 * Continuation-backed virtual threads whose stacks contain runtime-compiled (JIT) frames, in an
 * image with runtime compilation and deoptimization. A virtual thread calls the runtime-compiled
 * {@link JitSubject#compute}, which calls the AOT {@link Hooks#atYieldPoint}; that parks the
 * virtual thread (unmounting it, so its frames are copied into a StoredContinuation) until the
 * test resumes it.
 */
@NativeImageBuildArgs({
                "--features=com.oracle.svm.test.continuations.ContinuationsWithRuntimeCompilationTest$TestFeature",
                "-H:+VMContinuationsWithRuntimeCompilation",
                "-H:MaxRuntimeCompileMethods=1000",
                // Compile in the image's own isolate: isolated compilation trips an NMT header check
                // (IsolatedRuntimeMethodInfoAccess.startTrackingInCurrentIsolate) when NMT is enabled.
                "-H:-SupportCompileInIsolates",
})
public class ContinuationsWithRuntimeCompilationTest {

    /** Build-time holder for the method prepared for runtime compilation. */
    public static final class Holder {
        SubstrateMethod compute;
    }

    public static final class TestFeature implements Feature {
        public TestFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false,
                            "org.graalvm.nativeimage.builder",
                            "com.oracle.svm.graal", "com.oracle.svm.graal.hosted.runtimecompilation", "com.oracle.svm.hosted",
                            "com.oracle.svm.core.code", "com.oracle.svm.core.deopt", "com.oracle.svm.core.stack");
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false, "org.graalvm.nativeimage.guest.staging", "com.oracle.svm.guest.staging.core.graal");
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false, "jdk.internal.vm.ci", "jdk.vm.ci.code");
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false, "org.graalvm.nativeimage.builder", "com.oracle.svm.core.thread", "com.oracle.svm.core.heap");
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.OPEN, TestFeature.class, false, "java.base", "java.lang");
        }

        @Override
        public List<Class<? extends Feature>> getRequiredFeatures() {
            return List.of(RuntimeCompilationFeature.class);
        }

        @Override
        public void beforeAnalysis(BeforeAnalysisAccess a) {
            BeforeAnalysisAccessImpl config = (BeforeAnalysisAccessImpl) a;
            RuntimeClassInitialization.initializeAtBuildTime(Holder.class);
            RuntimeClassInitialization.initializeAtBuildTime(JitSubject.class);
            try {
                RuntimeReflection.register(Class.forName("java.lang.VirtualThread").getDeclaredField("cont"));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            Holder holder = new Holder();
            ImageSingletons.add(Holder.class, holder);
            RuntimeCompilationFeature rcf = RuntimeCompilationFeature.singleton();
            rcf.initializeRuntimeCompilationForTesting(config);
            try {
                holder.compute = rcf.prepareMethodForRuntimeCompilation(JitSubject.class.getMethod("compute", int.class), config);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The method compiled at image run time; its frame is what ends up in a stored continuation. */
    public static final class JitSubject {
        public static int compute(int x) {
            int a = x * 3;
            int b = Hooks.atYieldPoint(a);
            return a + b + 1;
        }
    }

    static int expected(int x) {
        return x * 3 + x * 3 + 1;
    }

    static final Runnable NOOP = () -> {
    };

    public static final class Hooks {
        static volatile Runnable beforeYield = NOOP;
        static volatile Runnable afterResume = NOOP;
        /** Whether {@link #atYieldPoint} parks until {@link #resume} is set. */
        static volatile boolean park = true;
        static volatile boolean resume;
        static volatile Thread parkedThread;

        @NeverInline("Must remain a separate AOT frame directly below the runtime-compiled frame.")
        static int atYieldPoint(int a) {
            beforeYield.run();
            if (park) {
                parkedThread = Thread.currentThread();
                while (!resume) {
                    LockSupport.park();
                }
            }
            afterResume.run();
            return a;
        }
    }

    interface ComputeFn extends CFunctionPointer {
        @InvokeJavaFunctionPointer
        int invoke(int x);
    }

    /** A virtual thread running {@code JitSubject.compute(arg)} through the runtime-compiled code. */
    static final class VirtualRun {
        final AtomicInteger result = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread;

        VirtualRun(InstalledCode code, int arg) {
            thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    ComputeFn fn = WordFactory.pointer(code.getEntryPoint());
                    result.set(fn.invoke(arg));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }
    }

    static VirtualRun startOnVirtualThread(InstalledCode code, int arg) {
        Hooks.resume = false;
        Hooks.parkedThread = null;
        VirtualRun run = new VirtualRun(code, arg);
        assertEquals("continuations must be supported", "java.lang.VirtualThread", run.thread.getClass().getName());
        run.thread.start();
        return run;
    }

    /** Waits until the virtual thread is parked (and therefore unmounted) in {@link Hooks#atYieldPoint}. */
    static void awaitParked(VirtualRun run) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Hooks.parkedThread != run.thread || run.thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline || run.thread.getState() == Thread.State.TERMINATED) {
                throw new AssertionError("virtual thread did not park: state=" + run.thread.getState() + ", parked=" + (Hooks.parkedThread == run.thread), run.failure.get());
            }
            Thread.onSpinWait();
        }
    }

    /** Resumes the parked virtual thread and waits for it; returns what it threw, or null. */
    static Throwable resumeAndJoin(VirtualRun run) throws InterruptedException {
        Hooks.resume = true;
        LockSupport.unpark(run.thread);
        run.thread.join();
        return run.failure.get();
    }

    static void resumeAndJoinSuccessfully(VirtualRun run) throws InterruptedException {
        Throwable failure = resumeAndJoin(run);
        if (failure != null) {
            throw new AssertionError(failure);
        }
    }

    private InstalledCode code;

    @Before
    public void compile() {
        code = SubstrateGraalUtils.compileAndInstall(ImageSingletons.lookup(Holder.class).compute);
        assertTrue(code.isValid());
        Hooks.park = true;
        Hooks.beforeYield = NOOP;
        Hooks.afterResume = NOOP;
    }

    @After
    public void invalidate() {
        if (code.isAlive()) {
            code.invalidate();
        }
    }

    InstalledCode compiled() {
        return code;
    }

    static final class FindRuntimeFrame extends StackFrameVisitor {
        Pointer sp = WordFactory.nullPointer();
        CodePointer ip = WordFactory.nullPointer();

        @Override
        protected boolean visitRegularFrame(Pointer frameSp, CodePointer frameIp, CodeInfo codeInfo) {
            if (!CodeInfoAccess.isAOTImageCode(codeInfo)) {
                sp = frameSp;
                ip = frameIp;
                return false;
            }
            return true;
        }

        @Override
        protected boolean visitDeoptimizedFrame(Pointer originalSP, CodePointer deoptStubIP, DeoptimizedFrame deoptimizedFrame) {
            return true;
        }
    }

    @NeverInline("Starts a stack walk in the caller frame.")
    static Pointer findRuntimeCompiledFrameSP() {
        FindRuntimeFrame v = new FindRuntimeFrame();
        JavaStackWalker.walkCurrentThread(KnownIntrinsics.readCallerStackPointer(), v);
        return v.sp;
    }

    @NeverInline("Starts a stack walk in the caller frame.")
    static CodePointer findRuntimeCompiledFrameIP() {
        FindRuntimeFrame v = new FindRuntimeFrame();
        JavaStackWalker.walkCurrentThread(KnownIntrinsics.readCallerStackPointer(), v);
        return v.ip;
    }

    @Test
    public void yieldInsideRuntimeCompiledFrameAndResume() throws Exception {
        AtomicLong jitSpBeforeYield = new AtomicLong();
        Hooks.beforeYield = () -> jitSpBeforeYield.set(findRuntimeCompiledFrameSP().rawValue());

        VirtualRun run = startOnVirtualThread(compiled(), 5);
        awaitParked(run);
        assertTrue("runtime-compiled frame must be on the virtual thread's stack", jitSpBeforeYield.get() != 0);

        resumeAndJoinSuccessfully(run);
        assertEquals(expected(5), run.result.get());
    }

    @Test
    public void invalidatedCodeSurvivesGCWhileParked() throws Exception {
        VirtualRun run = startOnVirtualThread(compiled(), 7);
        awaitParked(run);

        compiled().invalidate(); // lazy deopt: code becomes STATE_NON_ENTRANT; the parked frame is in the heap, not patched
        for (int i = 0; i < 3; i++) {
            System.gc(); // walks the StoredContinuation; must still find the JIT frame's CodeInfo
        }

        resumeAndJoinSuccessfully(run);
        assertEquals(expected(7), run.result.get());
    }

    @Test
    public void codeTethersAreReleasedAfterResume() throws Exception {
        AtomicLong jitIp = new AtomicLong();
        Hooks.beforeYield = () -> jitIp.set(findRuntimeCompiledFrameIP().rawValue());
        VirtualRun run = startOnVirtualThread(compiled(), 1);
        awaitParked(run);
        resumeAndJoinSuccessfully(run);
        assertTrue(jitIp.get() != 0);

        compiled().invalidate();
        for (int i = 0; i < 5; i++) {
            System.gc();
        }
        // The terminated virtual thread (and its Continuation) is still reachable through run.
        assertTrue("invalidated code must be freed once no parked stack references it", !isInRuntimeCodeCache(WordFactory.pointer(jitIp.get())));
        Reference.reachabilityFence(run);
    }

    @Uninterruptible(reason = "Test helper: looks up an IP in the code cache.")
    static boolean isInRuntimeCodeCache(CodePointer ip) {
        return CodeInfoTable.lookupCodeInfo(ip).isNonNull();
    }

    @Test
    public void frameLazilyDeoptimizedBeforeYieldSurvivesFreezeGCAndThaw() throws Exception {
        AtomicBoolean pendingAtYield = new AtomicBoolean();
        Hooks.beforeYield = () -> {
            compiled().invalidate(); // VM operation: lazily patches the mounted JIT caller frame
            pendingAtYield.set(isPendingLazyDeopt(findRuntimeCompiledFrameSP()));
        };
        VirtualRun run = startOnVirtualThread(compiled(), 4);
        awaitParked(run);
        assertTrue("JIT frame must be pending lazy deopt when frozen", pendingAtYield.get());

        System.gc(); // walks the frozen, lazily-pending frame
        resumeAndJoinSuccessfully(run);
        assertEquals(expected(4), run.result.get());
    }

    static boolean isPendingLazyDeopt(Pointer sp) {
        return Deoptimizer.checkLazyDeoptimized(CurrentIsolate.getCurrentThread(), sp);
    }

    @Test
    public void codeInvalidatedWhileParkedIsDeoptimizedOnResume() throws Exception {
        AtomicBoolean pendingAfterResume = new AtomicBoolean();
        Hooks.afterResume = () -> pendingAfterResume.set(isPendingLazyDeopt(findRuntimeCompiledFrameSP()));
        VirtualRun run = startOnVirtualThread(compiled(), 2);
        awaitParked(run);
        compiled().invalidate(); // the frame is parked in the heap: deoptimizeInRange cannot see it

        resumeAndJoinSuccessfully(run);
        assertTrue("resumed JIT frame of invalidated code must be pending lazy deopt", pendingAfterResume.get());
        assertEquals(expected(2), run.result.get());
    }

    @Test
    public void validCodeIsNotDeoptimizedOnResume() throws Exception {
        AtomicBoolean pendingAfterResume = new AtomicBoolean(true);
        Hooks.afterResume = () -> pendingAfterResume.set(isPendingLazyDeopt(findRuntimeCompiledFrameSP()));
        VirtualRun run = startOnVirtualThread(compiled(), 2);
        awaitParked(run);
        resumeAndJoinSuccessfully(run);
        assertTrue("no invalidation happened, frame must not be deoptimized", !pendingAfterResume.get());
        assertEquals(expected(2), run.result.get());
    }

    static final class MarkerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @Test
    public void exceptionUnwindsThroughDeoptPendingResumedFrame() throws Exception {
        Hooks.afterResume = () -> {
            throw new MarkerException();
        };
        VirtualRun run = startOnVirtualThread(compiled(), 2);
        awaitParked(run);
        compiled().invalidate();
        Throwable failure = resumeAndJoin(run);
        assertTrue("expected MarkerException, got " + failure, failure instanceof MarkerException);
    }

    @Test
    public void eagerlyDeoptimizedFrameOnStackPinsInsteadOfFreezing() throws Exception {
        AtomicBoolean eagerAtYield = new AtomicBoolean();
        Hooks.beforeYield = () -> {
            Pointer jitSp = findRuntimeCompiledFrameSP();
            Deoptimizer.deoptimizeFrameEagerly(jitSp, false, null);
            eagerAtYield.set(Deoptimizer.checkEagerDeoptimized(CurrentIsolate.getCurrentThread(), jitSp) != null);
        };
        VirtualRun run = startOnVirtualThread(compiled(), 3);
        awaitParked(run);
        assertTrue("JIT frame must be eagerly deoptimized when the virtual thread parks", eagerAtYield.get());

        System.gc(); // if the stack had been frozen, walking the DeoptimizedFrame in the heap would fail
        resumeAndJoinSuccessfully(run);
        assertEquals(expected(3), run.result.get());
    }

    @Test
    public void stackTraceOfParkedVirtualThreadIncludesRuntimeCompiledFrame() throws Exception {
        VirtualRun run = startOnVirtualThread(compiled(), 1);
        awaitParked(run);
        StackTraceElement[] trace = run.thread.getStackTrace();
        resumeAndJoinSuccessfully(run);
        boolean found = false;
        for (StackTraceElement e : trace) {
            found |= e.getClassName().endsWith("JitSubject") && e.getMethodName().equals("compute");
        }
        assertTrue(Arrays.toString(trace), found);
    }

    @Test
    public void thawedStoredContinuationIsNotWalkedAfterItsCodeIsFreed() throws Exception {
        /*
         * After resuming, the StoredContinuation that held the frames is garbage, but the GC can
         * still visit it (e.g., through a dirty card in the old generation). Hold on to it to make
         * every GC visit it: once its code is invalidated and freed, its stale frames must not be
         * walked.
         */
        AtomicLong jitIp = new AtomicLong();
        AtomicReference<Object> continuation = new AtomicReference<>();
        Hooks.beforeYield = () -> {
            jitIp.set(findRuntimeCompiledFrameIP().rawValue());
            continuation.set(continuationOf(Thread.currentThread()));
        };
        VirtualRun run = startOnVirtualThread(compiled(), 6);
        awaitParked(run);
        Object stored = ContinuationInternals.getStoredContinuation(SubstrateUtil.cast(continuation.get(), Target_jdk_internal_vm_Continuation.class));
        assertTrue("parked virtual thread must have a stored continuation", stored != null);
        resumeAndJoinSuccessfully(run);
        assertEquals(expected(6), run.result.get());

        compiled().invalidate();
        for (int i = 0; i < 5; i++) {
            System.gc(); // visits the thawed StoredContinuation, which is still reachable through `stored`
        }
        assertTrue("invalidated code must be freed: a thawed StoredContinuation must not keep it alive", !isInRuntimeCodeCache(WordFactory.pointer(jitIp.get())));
        Reference.reachabilityFence(stored);
    }

    static Object continuationOf(Thread vthread) {
        try {
            return VirtualThreadCont.FIELD.get(vthread);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Separate holder: a reflective class initializer on the test class itself would make it (and
     * {@link Hooks}) run-time initialized, which breaks runtime compilation of {@link JitSubject}.
     */
    static final class VirtualThreadCont {
        static final Field FIELD;
        static {
            try {
                FIELD = Class.forName("java.lang.VirtualThread").getDeclaredField("cont");
                FIELD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
