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
package jdk.graal.compiler.truffle.test;

import static org.junit.Assert.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.dynamicaccess.AccessCondition;
import org.graalvm.nativeimage.dynamicaccess.ForeignAccess;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;

/**
 * Tests that a {@code java.lang.foreign} downcall through a method handle that is a compilation
 * constant is compiled to a direct call of the native-image downcall stub when it is partially
 * evaluated, so the compiled code neither boxes the arguments nor runs the method handle
 * interpreter. A downcall through a handle that is not a constant is compiled as a call of the
 * method handle runtime.
 * <p>
 * The test also reports the cost of a downcall in nanoseconds per call from AOT-compiled Java and
 * from runtime-compiled Truffle code. The timings are informational only: pass or fail depends on
 * the results of the calls and on the shape of the partially evaluated graph.
 * <p>
 * In a native image, the test requires the downcall descriptors to be registered at image build
 * time through {@link TestFeature}, for example
 * {@code --features=jdk.graal.compiler.truffle.test.FFMDowncallPartialEvaluationTest$TestFeature}.
 * The graph checks need the compiler in the image's own isolate
 * ({@code -H:-SupportCompileInIsolates}); the system property {@value #CHECK_GRAPHS_PROPERTY} set
 * to {@code false} skips them, so that the calls can be tested with compilation in isolates.
 */
@SuppressWarnings("restricted")
public class FFMDowncallPartialEvaluationTest extends PartialEvaluationTest {

    static final String CHECK_GRAPHS_PROPERTY = "truffle.test.FFMDowncallPartialEvaluationTest.checkGraphs";

    /**
     * Downcall descriptors and the method handles that are created at image build time, which must
     * all be known to the image builder. The class is initialized at build time in a native image.
     */
    static final class Descriptors {
        static final FunctionDescriptor ABS = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        static final FunctionDescriptor LLABS = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
        static final FunctionDescriptor STRLEN = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        static final FunctionDescriptor STRTOLL = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        static final Linker.Option CRITICAL = Linker.Option.critical(false);
        static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");

        /** Handles created at image build time, where the JDK specializes their bindings. */
        static final MethodHandle ABS_HANDLE = Linker.nativeLinker().downcallHandle(ABS);
        static final MethodHandle LLABS_HANDLE = Linker.nativeLinker().downcallHandle(LLABS);
        static final MethodHandle STRLEN_HANDLE = Linker.nativeLinker().downcallHandle(STRLEN);
        static final MethodHandle ABS_CRITICAL_HANDLE = Linker.nativeLinker().downcallHandle(ABS, CRITICAL);
        static final MethodHandle STRTOLL_ERRNO_HANDLE = Linker.nativeLinker().downcallHandle(STRTOLL, CAPTURE_ERRNO);
        static final MethodHandle ABS_ERRNO_HANDLE = Linker.nativeLinker().downcallHandle(ABS, CAPTURE_ERRNO);
    }

    /** Registers the downcalls of this test with the image builder. */
    public static final class TestFeature implements Feature {
        @Override
        public void afterRegistration(AfterRegistrationAccess access) {
            RuntimeClassInitialization.initializeAtBuildTime(Descriptors.class);
            ForeignAccess foreign = access.getForeignAccess();
            AccessCondition always = AccessCondition.unconditional();
            foreign.registerForDowncall(always, Descriptors.ABS);
            foreign.registerForDowncall(always, Descriptors.LLABS);
            foreign.registerForDowncall(always, Descriptors.STRLEN);
            foreign.registerForDowncall(always, Descriptors.ABS, Descriptors.CRITICAL);
            foreign.registerForDowncall(always, Descriptors.ABS, Descriptors.CAPTURE_ERRNO);
            foreign.registerForDowncall(always, Descriptors.STRTOLL, Descriptors.CAPTURE_ERRNO);
        }
    }

    private static final int WARMUP_CALLS = 200_000;
    private static final int TIMED_CALLS = 2_000_000;

    private static MemorySegment symbol(String name) {
        return Linker.nativeLinker().defaultLookup().find(name).orElseThrow();
    }

    /** {@code int abs(int)} through a handle that is a constant of the root node. */
    static final class AbsRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        AbsRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int x = (int) frame.getArguments()[0];
            try {
                return (int) handle.invokeExact(symbol, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code int abs(int)} through {@code MethodHandle.invoke} with the handle's exact type. */
    static final class AbsGenericInvokeRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        AbsGenericInvokeRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int x = (int) frame.getArguments()[0];
            try {
                return (int) handle.invoke(symbol, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code int abs(int)} through a handle with the function address bound as first argument. */
    static final class AbsBoundRoot extends RootNode {
        private final MethodHandle handle;

        AbsBoundRoot(MethodHandle handle) {
            super(null);
            this.handle = handle;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int x = (int) frame.getArguments()[0];
            try {
                return (int) handle.invokeExact(x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code int abs(int)} through a handle that is not a compilation constant. */
    static final class AbsNonConstantRoot extends RootNode {
        private MethodHandle handle;
        private final MemorySegment symbol;

        AbsNonConstantRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int x = (int) frame.getArguments()[0];
            try {
                return (int) handle.invokeExact(symbol, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code int abs(int)} invoked behind a Truffle boundary, from AOT-compiled code. */
    static final class AbsBoundaryRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        AbsBoundaryRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return abs((int) frame.getArguments()[0]);
        }

        @TruffleBoundary
        private int abs(int x) {
            try {
                return (int) handle.invokeExact(symbol, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code long long llabs(long long)} through a handle that is a constant of the root node. */
    static final class LlabsRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        LlabsRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            long x = (long) frame.getArguments()[0];
            try {
                return (long) handle.invokeExact(symbol, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code size_t strlen(const char*)} with a pointer argument. */
    static final class StrlenRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        StrlenRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MemorySegment string = (MemorySegment) frame.getArguments()[0];
            try {
                return (long) handle.invokeExact(symbol, string);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** {@code int abs(int)} with {@code Linker.Option.captureCallState("errno")}. */
    static final class AbsErrnoRoot extends RootNode {
        private final MethodHandle handle;
        private final MemorySegment symbol;

        AbsErrnoRoot(MethodHandle handle, MemorySegment symbol) {
            super(null);
            this.handle = handle;
            this.symbol = symbol;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MemorySegment captureState = (MemorySegment) frame.getArguments()[0];
            int x = (int) frame.getArguments()[1];
            try {
                return (int) handle.invokeExact(symbol, captureState, x);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    /** Capture errno from an overflowing conversion, rather than merely accepting a buffer. */
    static final class StrtollRoot extends RootNode {
        private final MethodHandle handle = Descriptors.STRTOLL_ERRNO_HANDLE;
        private final MemorySegment symbol = symbol("strtoll");

        StrtollRoot() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MemorySegment state = (MemorySegment) frame.getArguments()[0];
            MemorySegment string = (MemorySegment) frame.getArguments()[1];
            try {
                return (long) handle.invokeExact(symbol, state, string, MemorySegment.NULL, 10);
            } catch (Throwable t) {
                throw CompilerDirectives.shouldNotReachHere(t);
            }
        }
    }

    @Before
    public void setup() {
        Assume.assumeTrue("the downcall stubs are only registered in the native image", ImageInfo.inImageRuntimeCode());
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.CompileImmediately", "false").build());
    }

    private static boolean checkGraphs() {
        return Boolean.parseBoolean(System.getProperty(CHECK_GRAPHS_PROPERTY, "true"));
    }

    private static List<String> invokeTargets(StructuredGraph graph) {
        List<String> targets = new ArrayList<>();
        for (Invoke invoke : graph.getInvokes()) {
            targets.add(invoke.callTarget().targetMethod().format("%H.%n(%p)"));
        }
        return targets;
    }

    private static int allocationCount(StructuredGraph graph) {
        return graph.getNodes().filter(AbstractNewObjectNode.class).count();
    }

    private static void describe(String name, StructuredGraph graph) {
        System.out.printf("ffm-downcall-graph: %s: %d nodes, %d allocations, invokes=%s%n", name, graph.getNodeCount(), allocationCount(graph), invokeTargets(graph));
    }

    /** The name of a downcall stub: {@code downcall_<arguments>_<return>[_r][_c][_t]_<digest>}. */
    private static final Pattern STUB_NAME = Pattern.compile("com\\.oracle\\.svm\\.core\\.foreign\\.DowncallStubsHolder\\.downcall_([A-Z]*_[A-Z](?:_[rct])*)_[^_(]+\\(.*");

    /**
     * Asserts that the partially evaluated graph calls the downcall stub directly: exactly one
     * call of a method of {@code DowncallStubsHolder}, whose name starts with
     * {@code expectedStub} (the stub's argument and return kinds and its flags), no call into
     * the method handle runtime and no allocation. Calls that remain are those of the handle's
     * bindings that partial evaluation cannot inline, such as the acquisition of the sessions of
     * memory segment arguments.
     */
    private void assertDirectDowncall(String name, String expectedStub, RootNode root, Object... arguments) {
        if (!checkGraphs()) {
            return;
        }
        StructuredGraph graph = partialEvalGraph(root, arguments);
        describe(name, graph);
        List<String> targets = invokeTargets(graph);
        int stubCalls = 0;
        for (String target : targets) {
            assertTrue("method handle runtime call in " + name + ": " + targets, !target.contains(".invokeBasic(") && !target.contains(".linkToNative(") && !target.contains(".invokeExact(") &&
                            !target.contains("_MT(") && !target.contains("DowncallLinker"));
            Matcher stub = STUB_NAME.matcher(target);
            if (stub.matches()) {
                Assert.assertEquals("downcall stub in " + name + ": " + targets, expectedStub, stub.group(1));
                stubCalls++;
            }
        }
        Assert.assertEquals("direct downcall stub calls in " + name + ": " + targets, 1, stubCalls);
        Assert.assertEquals("allocations in " + name, 0, allocationCount(graph));
    }

    /** Asserts that the partially evaluated graph calls the method handle runtime. */
    private void assertRuntimeDowncall(String name, RootNode root, Object... arguments) {
        if (!checkGraphs()) {
            return;
        }
        StructuredGraph graph = partialEvalGraph(root, arguments);
        describe(name, graph);
        List<String> targets = invokeTargets(graph);
        assertTrue("no method handle runtime call in " + name + ": " + targets, targets.stream().anyMatch(target -> target.contains(".invokeBasic(")));
        assertTrue("direct downcall stub call in " + name + ": " + targets, targets.stream().noneMatch(target -> target.contains("DowncallStubsHolder")));
    }

    private StructuredGraph partialEvalGraph(RootNode root, Object[] arguments) {
        return partialEval((OptimizedCallTarget) root.getCallTarget(), arguments);
    }

    private static OptimizedCallTarget compile(RootNode root, Object... args) {
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        for (int i = 0; i < 3; i++) {
            target.call(args);
        }
        assertTrue("compilation of " + root, target.compile(true));
        assertTrue("compiled code of " + root + " must be valid", target.isValid());
        return target;
    }

    /** Returns the cost of one call in nanoseconds; the timing itself is informational only. */
    private static double timeCalls(OptimizedCallTarget target, Object[] args, Object expected) {
        for (int i = 0; i < WARMUP_CALLS; i++) {
            target.call(args);
        }
        long start = System.nanoTime();
        for (int i = 0; i < TIMED_CALLS; i++) {
            Object result = target.call(args);
            if (!expected.equals(result)) {
                throw new AssertionError("unexpected result " + result + " from " + target);
            }
        }
        long end = System.nanoTime();
        assertTrue("compiled code of " + target + " must stay valid", target.isValid());
        return (end - start) / (double) TIMED_CALLS;
    }

    private static void report(String name, double nsPerCall) {
        System.out.printf("ffm-downcall-bench: %-56s %8.2f ns/call%n", name, nsPerCall);
    }

    /** A call site whose handle is a constant of the image: intrinsified when the image is built. */
    private static int absAOTConstantHandle(MemorySegment symbol, int x) throws Throwable {
        return (int) Descriptors.ABS_HANDLE.invokeExact(symbol, x);
    }

    /** A call site whose handle is not constant: interpreted by the method handle runtime. */
    private static int absAOT(MethodHandle handle, MemorySegment symbol, int x) throws Throwable {
        return (int) handle.invokeExact(symbol, x);
    }

    private static double timeAOT(MethodHandle handle, MemorySegment symbol, boolean constantHandle) throws Throwable {
        for (int i = 0; i < WARMUP_CALLS; i++) {
            int r = constantHandle ? absAOTConstantHandle(symbol, -i) : absAOT(handle, symbol, -i);
            if (r != i) {
                throw new AssertionError("abs(" + -i + ") = " + r);
            }
        }
        long start = System.nanoTime();
        for (int i = 0; i < TIMED_CALLS; i++) {
            int r = constantHandle ? absAOTConstantHandle(symbol, -i) : absAOT(handle, symbol, -i);
            if (r != i) {
                throw new AssertionError("abs(" + -i + ") = " + r);
            }
        }
        return (System.nanoTime() - start) / (double) TIMED_CALLS;
    }

    @Test
    public void testAbsBuildTimeHandle() {
        MemorySegment abs = symbol("abs");
        AbsRoot root = new AbsRoot(Descriptors.ABS_HANDLE, abs);
        assertDirectDowncall("abs (build-time handle)", "JI_I", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        Assert.assertEquals(42, target.call(42));
        report("Truffle PE, abs, build-time handle", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsRuntimeHandle() {
        MemorySegment abs = symbol("abs");
        MethodHandle handle = Linker.nativeLinker().downcallHandle(Descriptors.ABS);
        AbsRoot root = new AbsRoot(handle, abs);
        assertDirectDowncall("abs (run-time handle)", "JI_I", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        Assert.assertEquals(42, target.call(42));
        report("Truffle PE, abs, run-time handle", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsRuntimeHandleBoundToSymbol() {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(symbol("abs"), Descriptors.ABS);
        AbsBoundRoot root = new AbsBoundRoot(handle);
        assertDirectDowncall("abs (run-time handle bound to the symbol)", "JI_I", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        Assert.assertEquals(42, target.call(42));
        report("Truffle PE, abs, run-time handle bound to the symbol", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsGenericInvoke() {
        MemorySegment abs = symbol("abs");
        AbsGenericInvokeRoot root = new AbsGenericInvokeRoot(Linker.nativeLinker().downcallHandle(Descriptors.ABS), abs);
        assertDirectDowncall("abs (MethodHandle.invoke)", "JI_I", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        Assert.assertEquals(42, target.call(42));
        report("Truffle PE, abs, run-time handle, MethodHandle.invoke", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsNonConstantHandle() {
        MemorySegment abs = symbol("abs");
        AbsNonConstantRoot root = new AbsNonConstantRoot(Linker.nativeLinker().downcallHandle(Descriptors.ABS), abs);
        assertRuntimeDowncall("abs (handle not constant)", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        Assert.assertEquals(42, target.call(42));
        report("Truffle PE, abs, handle not constant", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsBehindBoundary() {
        MemorySegment abs = symbol("abs");
        AbsBoundaryRoot root = new AbsBoundaryRoot(Linker.nativeLinker().downcallHandle(Descriptors.ABS), abs);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        report("Truffle PE, abs, run-time handle behind TruffleBoundary", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testLlabsBuildTimeHandle() {
        MemorySegment llabs = symbol("llabs");
        LlabsRoot root = new LlabsRoot(Descriptors.LLABS_HANDLE, llabs);
        assertDirectDowncall("llabs (build-time handle)", "JJ_J", root, -7L);
        OptimizedCallTarget target = compile(root, -7L);
        Assert.assertEquals(7L, target.call(-7L));
        Assert.assertEquals(1L << 40, target.call(-(1L << 40)));
        report("Truffle PE, llabs, build-time handle", timeCalls(target, new Object[]{-12345L}, 12345L));
    }

    @Test
    public void testStrlenBuildTimeHandle() {
        MemorySegment strlen = symbol("strlen");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment string = arena.allocateFrom("Hello, World!");
            StrlenRoot root = new StrlenRoot(Descriptors.STRLEN_HANDLE, strlen);
            assertDirectDowncall("strlen (build-time handle)", "JJ_J", root, string);
            OptimizedCallTarget target = compile(root, string);
            Assert.assertEquals(13L, target.call(string));
            report("Truffle PE, strlen, build-time handle", timeCalls(target, new Object[]{string}, 13L));
        }
    }

    @Test
    public void testAbsCriticalBuildTimeHandle() {
        MemorySegment abs = symbol("abs");
        AbsRoot root = new AbsRoot(Descriptors.ABS_CRITICAL_HANDLE, abs);
        assertDirectDowncall("abs critical (build-time handle)", "JI_I_t", root, -7);
        OptimizedCallTarget target = compile(root, -7);
        Assert.assertEquals(7, target.call(-7));
        report("Truffle PE, abs critical(false), build-time handle", timeCalls(target, new Object[]{-12345}, 12345));
    }

    @Test
    public void testAbsCaptureErrnoBuildTimeHandle() {
        MemorySegment abs = symbol("abs");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment captureState = arena.allocate(Linker.Option.captureStateLayout());
            AbsErrnoRoot root = new AbsErrnoRoot(Descriptors.ABS_ERRNO_HANDLE, abs);
            assertDirectDowncall("abs errno (build-time handle)", "JJI_I_c", root, captureState, -7);
            OptimizedCallTarget target = compile(root, captureState, -7);
            Assert.assertEquals(7, target.call(captureState, -7));
            report("Truffle PE, abs captureCallState(errno), build-time handle", timeCalls(target, new Object[]{captureState, -12345}, 12345));
        }
    }

    @Test
    public void testCapturedErrnoValue() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            MemorySegment overflow = arena.allocateFrom("99999999999999999999999999999999999999999999");
            long errnoOffset = Linker.Option.captureStateLayout().byteOffset(MemoryLayout.PathElement.groupElement("errno"));
            StrtollRoot root = new StrtollRoot();
            assertDirectDowncall("strtoll errno", "JJJJI_J_c", root, state, overflow);
            OptimizedCallTarget target = compile(root, state, overflow);
            state.fill((byte) 0);
            Assert.assertEquals(Long.MAX_VALUE, target.call(state, overflow));
            assertTrue("overflow must capture a nonzero errno", state.get(ValueLayout.JAVA_INT, errnoOffset) != 0);
        }
    }

    @Test
    public void testClosedSegment() {
        Arena arena = Arena.ofConfined();
        MemorySegment string = arena.allocateFrom("live");
        StrlenRoot root = new StrlenRoot(Descriptors.STRLEN_HANDLE, symbol("strlen"));
        OptimizedCallTarget target;
        try {
            target = compile(root, string);
            Assert.assertEquals(4L, target.call(string));
        } finally {
            arena.close();
        }
        Throwable failure = Assert.assertThrows(Throwable.class, () -> target.call(string));
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue("closed arena must be rejected: " + failure, failure instanceof IllegalStateException);
    }

    @Test
    public void testExactTypeMismatch() {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(symbol("abs"), Descriptors.ABS);
        Assert.assertThrows(WrongMethodTypeException.class, () -> {
            long ignored = (long) handle.invokeExact(-7L);
            Assert.fail("incorrect exact type returned " + ignored);
        });
    }

    @Test
    public void testAOTBaseline() throws Throwable {
        MemorySegment abs = symbol("abs");
        report("AOT Java, abs, build-time constant handle", timeAOT(Descriptors.ABS_HANDLE, abs, true));
        report("AOT Java, abs, build-time handle, not constant", timeAOT(Descriptors.ABS_HANDLE, abs, false));
        report("AOT Java, abs, run-time handle, not constant", timeAOT(Linker.nativeLinker().downcallHandle(Descriptors.ABS), abs, false));
    }
}
