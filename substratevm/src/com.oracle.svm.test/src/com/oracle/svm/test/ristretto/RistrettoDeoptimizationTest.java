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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BooleanSupplier;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Deoptimizes code that Ristretto compiled from bytecode loaded at run time, and checks that
 * execution resumes in the interpreter with the right state.
 *
 * <p>
 * The nested classes below are not used directly. Each test defines fresh copies of them at run
 * time from their class files, so the interpreter runs them and Ristretto compiles them. In those
 * copies, calls to {@link Probe#compiled()} are rewritten to
 * {@code RistrettoDirectives.inRuntimeCompiledCode()}, which tells the code whether it is running
 * compiled or interpreted.
 *
 * <p>
 * The test needs an image built with {@code -H:+RuntimeClassLoading -H:+GraalJITCompileAtRuntime},
 * {@code --add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.interpreter.ristretto=ALL-UNNAMED}
 * and {@code --features=com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest$TestFeature},
 * run with {@code -Dcom.oracle.svm.test.ristretto=true}. Without that property it is skipped. The
 * Truffle gate runs it in the same image as Truffle runtime compilation, whose code must not take
 * the interpreter deoptimization path (see {@code mx_substratevm.truffle_unittest_task}).
 */
public class RistrettoDeoptimizationTest {
    static final String REQUIRE_RISTRETTO_PROPERTY = "com.oracle.svm.test.ristretto";
    static final long COMPILATION_TIMEOUT_NANOS = 120_000_000_000L;

    /**
     * The handles of {@link Handles}, in the order of its fields. The test binds them in image
     * code (see {@link #bindHandles}): the interpreter can only call AOT methods with compiled
     * code of their own, which the {@code java.lang.invoke} API need not have in the test image.
     */
    public static MethodHandle[] handles;

    /** Registers the class files of the run-time classes as resources. */
    public static final class TestFeature implements Feature {
        @Override
        public void beforeAnalysis(BeforeAnalysisAccess access) {
            for (List<Class<?>> classes : List.of(RUNTIME_CLASSES, RistrettoVarHandleTest.RUNTIME_CLASSES, RistrettoMethodHandleTest.RUNTIME_CLASSES)) {
                for (Class<?> c : classes) {
                    RuntimeResourceAccess.addResource(RistrettoDeoptimizationTest.class.getModule(), classFileName(c));
                }
            }
            RistrettoVarHandleTest.beforeAnalysis();
            RistrettoMethodHandleTest.beforeAnalysis();
            /*
             * Links the VarHandle call sites of the AOT methods that AtomicUser calls in the
             * builder JVM, where JVMCI then resolves them to their invokehandle adapters
             * (elide-dev/graal#113).
             */
            AtomicReferenceArray<Object> array = new AtomicReferenceArray<>(1);
            array.lazySet(0, array);
            AtomicReference<Object> reference = new AtomicReference<>();
            if (array.get(0) != array || !reference.compareAndSet(null, array)) {
                throw new AssertionError("unexpected atomic state");
            }
            /*
             * Compiled on their own, so that the interpreter and Ristretto code can call them. Their
             * VarHandle fields keep a location (as with -H:Preserve), so their bytecodes are kept.
             */
            try {
                RuntimeReflection.register(ArrayList.class.getConstructor(), ArrayList.class.getMethod("add", Object.class), ArrayList.class.getMethod("size"));
                RuntimeReflection.register(AtomicReferenceArray.class.getMethod("get", int.class), AtomicReference.class.getMethod("compareAndSet", Object.class, Object.class),
                                Objects.class.getMethod("requireNonNullElse", Object.class, Object.class));
                RuntimeReflection.register(AtomicReferenceArray.class.getDeclaredField("AA"), AtomicReference.class.getDeclaredField("VALUE"));
                /* The bootstrap method of the lambda call sites of Lambdas, which the interpreter links. */
                RuntimeReflection.register(
                                LambdaMetafactory.class.getMethod("metafactory", MethodHandles.Lookup.class, String.class, MethodType.class, MethodType.class, MethodHandle.class, MethodType.class));
                /*
                 * The updater methods that Updater calls, and the Unsafe accessors they call:
                 * compiled on their own, and with the Unsafe field keeping a location, those make
                 * AtomicReferenceFieldUpdaterImpl.get and set inlinable (see
                 * RistrettoInlinableAOTMethods).
                 */
                Class<?> unsafe = Class.forName("jdk.internal.misc.Unsafe");
                RuntimeReflection.register(AtomicReferenceFieldUpdater.class.getMethod("newUpdater", Class.class, Class.class, String.class),
                                AtomicReferenceFieldUpdater.class.getMethod("get", Object.class), AtomicReferenceFieldUpdater.class.getMethod("set", Object.class, Object.class),
                                unsafe.getMethod("getReferenceVolatile", Object.class, long.class), unsafe.getMethod("putReferenceVolatile", Object.class, long.class, Object.class));
                RuntimeReflection.register(Class.forName("java.util.concurrent.atomic.AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl").getDeclaredField("U"));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Placeholder for {@code RistrettoDirectives.inRuntimeCompiledCode()}: 1 when compiled. */
    public static final class Probe {
        public static int compiled() {
            return 0;
        }
    }

    public abstract static class Shape {
        public abstract int value();
    }

    public static final class One extends Shape {
        @Override
        public int value() {
            return 1;
        }
    }

    /** Loaded only once the compiled code has assumed that {@link One} is the only shape. */
    public static final class Two extends Shape {
        @Override
        public int value() {
            return 2;
        }
    }

    public static final class Holder {
        public Shape shape;
    }

    /** Resolved only on the rare path, which compiled code therefore leaves to the interpreter. */
    public static final class Rare {
        public static int negate(int x) {
            return -x;
        }
    }

    public static final class Hot {
        /** Returns {@code x + 1}, plus 1_000_000 when compiled. The rare path returns {@code -x}. */
        public static int eager(int x, boolean rare) {
            if (rare) {
                return Rare.negate(x) - 1_000_000 * Probe.compiled();
            }
            return x + 1 + 1_000_000 * Probe.compiled();
        }

        /**
         * Sums {@code holder.shape.value()} over {@code n} iterations and runs {@code hook} halfway.
         * {@code compiled[0]} and {@code compiled[1]} count the compiled iterations before and after
         * the hook.
         */
        public static long lazy(Holder holder, int n, Runnable hook, int[] compiled) {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (i == n / 2) {
                    hook.run();
                }
                sum += holder.shape.value();
                compiled[i < n / 2 ? 0 : 1] += Probe.compiled();
            }
            return sum;
        }
    }

    /**
     * A map key whose hashCode and equals exist only in the run-time copy of this class. The first
     * {@code hashCode()} call after {@link #hook} is set runs the hook.
     */
    public static final class Key {
        public static Runnable hook;

        public final int v;

        public Key(int v) {
            this.v = v;
        }

        @Override
        public int hashCode() {
            Runnable h = hook;
            if (h != null) {
                hook = null;
                h.run();
            }
            return v * 31 + 7;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.v == v;
        }
    }

    public static final class MapUser {
        /**
         * Sums {@code map.get(key) * holder.shape.value()} over {@code keys} and counts the compiled
         * iterations before and after the first {@link Key} in {@code compiled[0]} and
         * {@code compiled[1]}. Ristretto inlines {@link ConcurrentHashMap#get}, an AOT method (see
         * {@code -H:RistrettoInlinableAOTClasses}), which calls {@link Key#hashCode()}.
         */
        public static long lookup(ConcurrentHashMap<Object, Integer> map, Object[] keys, Holder holder, int[] compiled) {
            long sum = 0;
            for (Object key : keys) {
                sum += map.get(key) * holder.shape.value();
                compiled[key instanceof Key ? 1 : 0] += Probe.compiled();
            }
            return sum;
        }
    }

    public static final class AtomicUser {
        /**
         * Reads {@code array[i]} and swaps {@code reference} from it to {@code i}, and returns 1
         * when compiled. {@link AtomicReferenceArray#get} and {@link AtomicReference#compareAndSet}
         * are AOT methods that call signature-polymorphic {@code VarHandle} methods.
         */
        public static int readAndSwap(AtomicReferenceArray<Object> array, AtomicReference<Object> reference, int i) {
            Object value = array.get(i);
            if (!reference.compareAndSet(value, i)) {
                throw new AssertionError("compareAndSet failed at " + i);
            }
            return Probe.compiled();
        }
    }

    public static final class CollectionUser {
        public static int grow(int n, int[] compiled) {
            ArrayList<Integer> list = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(i);
            }
            compiled[0] = Probe.compiled();
            return list.size();
        }
    }

    public static final class ConstantUser {
        /**
         * Returns {@code value}, or {@code fallback} when it is null, and 1 in {@code compiled[0]}
         * when compiled. {@link Objects#requireNonNullElse} is an AOT method that loads a string
         * constant ({@code "defaultObj"}) with {@code ldc}.
         */
        public static Object pick(Object value, Object fallback, int[] compiled) {
            compiled[0] = Probe.compiled();
            return Objects.requireNonNullElse(value, fallback);
        }
    }

    public static final class Updater {
        public static final int ROUNDS = 500_000;

        static final AtomicReferenceFieldUpdater<Updater, Object> JOB = AtomicReferenceFieldUpdater.newUpdater(Updater.class, Object.class, "job");

        public volatile Object job;

        /**
         * Sums the {@link Integer} jobs of 64 updaters over {@link #ROUNDS} rounds in one long loop,
         * so that Ristretto enters it through OSR, and counts the compiled rounds in
         * {@code compiled[0]}. {@link AtomicReferenceFieldUpdater#get} and
         * {@link AtomicReferenceFieldUpdater#set} are AOT methods that Ristretto inlines.
         * {@link #finish} has not run when the OSR compilation of the loop starts, so the compiled
         * code deoptimizes on the loop exit, and that deoptimization is moved up to the OSR entry.
         */
        public static long run(int[] compiled) {
            Updater[] updaters = new Updater[64];
            for (int i = 0; i < updaters.length; i++) {
                updaters[i] = new Updater();
                updaters[i].job = Integer.valueOf(i);
            }
            long seen = 0;
            long failures = 0;
            for (int round = 0; round < ROUNDS; round++) {
                Updater updater = updaters[round & 63];
                try {
                    Object job = JOB.get(updater);
                    if (job != null) {
                        seen += ((Integer) job).intValue();
                    }
                    JOB.set(updater, job);
                    compiled[0] += Probe.compiled();
                } catch (RuntimeException e) {
                    failures++;
                }
            }
            return finish(seen, failures);
        }

        static long finish(long seen, long failures) {
            return failures == 0 ? seen : -failures;
        }
    }

    /** A run-time {@link Runnable} implementation, the receiver of an interface method handle. */
    public static final class Counter implements Runnable {
        public int runs;

        @Override
        public void run() {
            runs++;
        }
    }

    /**
     * Calls constant method handles, bound to the run-time classes by
     * {@link RistrettoDeoptimizationTest#bindHandles}. Ristretto compiles the calls as direct
     * calls to their targets (elide-dev/graal#112).
     */
    public static final class Handles {
        static final MethodHandle ADD;
        static final MethodHandle VALUE;
        static final MethodHandle RUN;
        static final MethodHandle NEW_ONE;
        static final MethodHandle NEW_KEY;
        static final MethodHandle HOOKED;

        static {
            MethodHandle[] h = RistrettoDeoptimizationTest.handles;
            ADD = h[0];
            VALUE = h[1];
            RUN = h[2];
            NEW_ONE = h[3];
            NEW_KEY = h[4];
            HOOKED = h[5];
        }

        public static int add(int a, int b) {
            return a + b;
        }

        /**
         * Returns {@code x + 1}. Runs {@code hook} if it is not null and then adds 1 to
         * {@code compiled[1]} when still running compiled.
         */
        public static int hooked(int x, Runnable hook, int[] compiled) {
            if (hook != null) {
                hook.run();
                compiled[1] += Probe.compiled();
            }
            return x + 1;
        }

        /**
         * Sums {@code 2 * i + 5 + holder.shape.value()} over {@code n} iterations through the
         * constant handles, runs {@code counter} once per iteration, and counts the compiled
         * iterations in {@code compiled[0]}.
         */
        public static long exact(Holder holder, int n, Counter counter, int[] compiled) throws Throwable {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                sum += (int) ADD.invokeExact(i, 1);
                sum += (int) ADD.invoke(i, 2);
                sum += (int) VALUE.invokeExact(holder.shape);
                sum += ((One) NEW_ONE.invokeExact()).value();
                sum += ((Key) NEW_KEY.invokeExact(1)).v;
                RUN.invokeExact((Runnable) counter);
                compiled[0] += Probe.compiled();
            }
            return sum;
        }

        /** Calls {@link #ADD} with one argument: a {@link WrongMethodTypeException}. */
        public static int wrongType(int x, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) ADD.invokeExact(x);
        }

        /** Calls {@link #VALUE} on {@code shape}: a {@link NullPointerException} when null. */
        public static int value(Shape shape, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) VALUE.invokeExact(shape);
        }

        /**
         * Sums {@code holder.shape.value() + i + 1} over {@code n} iterations through the constant
         * handles and runs {@code hook} inside {@link #hooked} halfway. {@code compiled[0]} and
         * {@code compiled[2]} count the compiled iterations before and after the hook, and
         * {@code compiled[1]} whether {@link #hooked} was still compiled after the hook.
         */
        public static long lazy(Holder holder, int n, Runnable hook, int[] compiled) throws Throwable {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                sum += (int) VALUE.invokeExact(holder.shape);
                Runnable iterationHook = i == n / 2 ? hook : null;
                sum += (int) HOOKED.invokeExact(i, iterationHook, compiled);
                compiled[i < n / 2 ? 0 : 2] += Probe.compiled();
            }
            return sum;
        }
    }

    /**
     * Uses {@code invokedynamic} call sites: lambdas and method references. Ristretto must compile
     * their methods (elide-dev/graal#131). String concatenation is an {@code invokedynamic} too,
     * but its bootstrap method needs the lambda forms of {@code java.lang.invoke}, which the test
     * image does not keep.
     */
    public static final class Lambdas {
        public static int twice(int x) {
            return 2 * x;
        }

        /**
         * Sums {@code acc + i + seed} over {@code n} iterations through a capturing lambda, whose
         * call site target is a constructor handle of the lambda class, and stores the lambda in a
         * local (an {@code astore} right after the {@code invokedynamic}). Adds 1 to
         * {@code compiled[0]} when compiled.
         */
        public static int capturing(int seed, int n, int[] compiled) {
            int[] ref = {seed};
            IntBinaryOperator op = (a, b) -> a + b + ref[0];
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc = op.applyAsInt(acc, i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /**
         * Runs {@code counter} {@code n} times through a bound method reference (capturing) and sums
         * {@code 2 * i} through an unbound one (not capturing). Adds 1 to {@code compiled[0]} when
         * compiled.
         */
        public static int methodReferences(Counter counter, int n, int[] compiled) {
            Runnable run = counter::run;
            IntUnaryOperator twice = Lambdas::twice;
            int sum = 0;
            for (int i = 0; i < n; i++) {
                run.run();
                sum += twice.applyAsInt(i);
            }
            compiled[0] += Probe.compiled();
            return sum;
        }
    }

    static final List<Class<?>> RUNTIME_CLASSES = List.of(Shape.class, One.class, Two.class, Holder.class, Rare.class, Hot.class, Key.class, MapUser.class, AtomicUser.class, ConstantUser.class, CollectionUser.class,
                    Updater.class, Counter.class,
                    Handles.class, Lambdas.class);

    @Before
    public void requireRistretto() {
        Assume.assumeTrue("Native Image only", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Run with -D" + REQUIRE_RISTRETTO_PROPERTY + "=true in an image with Ristretto", Boolean.getBoolean(REQUIRE_RISTRETTO_PROPERTY));
    }

    /**
     * Binds the handles of {@link Handles} to the run-time classes of {@code loader} and returns
     * its run-time {@link Handles} class, whose initializer picks them up.
     */
    private static Class<?> bindHandles(RuntimeClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> handlesClass = loader.loadClass(Handles.class.getName());
        Class<?> shapeClass = loader.loadClass(Shape.class.getName());
        handles = new MethodHandle[]{
                        lookup.findStatic(handlesClass, "add", MethodType.methodType(int.class, int.class, int.class)),
                        lookup.findVirtual(shapeClass, "value", MethodType.methodType(int.class)),
                        lookup.findVirtual(Runnable.class, "run", MethodType.methodType(void.class)),
                        lookup.findConstructor(loader.loadClass(One.class.getName()), MethodType.methodType(void.class)),
                        lookup.findConstructor(loader.loadClass(Key.class.getName()), MethodType.methodType(void.class, int.class)),
                        lookup.findStatic(handlesClass, "hooked", MethodType.methodType(int.class, int.class, Runnable.class, int[].class))};
        return handlesClass;
    }

    /**
     * Compiled code reaches a call that was unresolved at compile time and deoptimizes eagerly. The
     * call completes in the interpreter.
     */
    @Test
    public void eagerDeoptimizationResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method eager = loader.loadClass(Hot.class.getName()).getMethod("eager", int.class, boolean.class);

        awaitCompiled("Hot.eager", () -> {
            int result = invoke(eager, 3, false);
            if (result != 4 && result != 1_000_004) {
                throw new AssertionError("unexpected result " + result);
            }
            return result == 1_000_004;
        });

        assertEquals("rare path must complete in the interpreter", -7, (int) invoke(eager, 7, true));
        int after = invoke(eager, 5, false);
        assertTrue("unexpected result after deoptimization: " + after, after == 6 || after == 1_000_006);
    }

    /**
     * Loading a second {@link Shape} invalidates compiled code that assumed a single one, while its
     * frame is active below the hook. The frame resumes in the interpreter, which dispatches the
     * remaining calls to the new shape.
     */
    @Test
    public void lazyDeoptimizationOfActiveFrameResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Method lazy = loader.loadClass(Hot.class.getName()).getMethod("lazy", holderClass, int.class, Runnable.class, int[].class);

        Runnable noHook = () -> {
        };
        awaitCompiled("Hot.lazy", () -> {
            int[] compiled = new int[2];
            long sum = invoke(lazy, holder, 10, noHook, compiled);
            assertEquals(10, sum);
            return compiled[0] == 5 && compiled[1] == 5;
        });

        Runnable loadSecondShape = () -> {
            try {
                holderClass.getField("shape").set(holder, loader.loadClass(Two.class.getName()).getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        int n = 1000;
        int[] compiled = new int[2];
        long sum = invoke(lazy, holder, n, loadSecondShape, compiled);
        assertEquals("iterations after the hook must call Two.value()", n / 2 * 1 + n / 2 * 2, sum);
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("iterations after the hook must run in the interpreter", 0, compiled[1]);
    }

    /**
     * Compiled code inlined {@link ConcurrentHashMap#get} (an AOT method), which calls
     * {@link Key#hashCode()}. The hook there loads a second {@link Shape} and invalidates the code
     * while the inlined frame is active. Both frames resume in the interpreter, the AOT method from
     * its retained bytecodes (elide-dev/graal#103).
     */
    @Test
    public void deoptimizationInInlinedAOTMethodResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Class<?> keyClass = loader.loadClass(Key.class.getName());
        Method lookup = loader.loadClass(MapUser.class.getName()).getMethod("lookup", ConcurrentHashMap.class, Object[].class, holderClass, int[].class);

        int n = 64;
        ConcurrentHashMap<Object, Integer> map = new ConcurrentHashMap<>();
        Object[] intKeys = new Object[n];
        Object[] mixedKeys = new Object[n];
        for (int i = 0; i < n; i++) {
            intKeys[i] = i;
            map.put(i, i);
            Object key = keyClass.getConstructor(int.class).newInstance(i);
            map.put(key, 2 * i);
            mixedKeys[i] = i < n / 2 ? Integer.valueOf(i) : key;
        }
        awaitCompiled("MapUser.lookup", () -> {
            int[] compiled = new int[2];
            long sum = invoke(lookup, map, intKeys, holder, compiled);
            assertEquals((long) n * (n - 1) / 2, sum);
            return compiled[0] == n;
        });

        keyClass.getField("hook").set(null, (Runnable) () -> {
            try {
                holderClass.getField("shape").set(holder, loader.loadClass(Two.class.getName()).getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        long expected = 0;
        for (int i = 0; i < n; i++) {
            expected += i < n / 2 ? i : 2 * i * 2;
        }
        int[] compiled = new int[2];
        assertEquals("keys after the hook must use the value of Two", expected, (long) invoke(lookup, map, mixedKeys, holder, compiled));
        assertEquals("Integer keys before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("Key iterations must run in the interpreter", 0, compiled[1]);
        assertEquals("lookups after deoptimization", (long) n * (n - 1), (long) invoke(lookup, map, intKeys, holder, new int[2]));
    }

    /**
     * Compiled code calls AOT methods whose {@code VarHandle} call sites the builder JVM had
     * already linked (see {@link TestFeature}). Ristretto must not inline them as ordinary calls to
     * the static invokehandle adapters, which threw IncompatibleClassChangeError
     * (elide-dev/graal#113).
     */
    @Test
    public void compiledCodeCallsAOTMethodsWithVarHandleSites() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method readAndSwap = loader.loadClass(AtomicUser.class.getName()).getMethod("readAndSwap", AtomicReferenceArray.class, AtomicReference.class, int.class);
        int n = 16;
        AtomicReferenceArray<Object> array = new AtomicReferenceArray<>(n);
        AtomicReference<Object> reference = new AtomicReference<>();
        int[] round = new int[1];
        awaitCompiled("AtomicUser.readAndSwap", () -> {
            int i = round[0]++ % n;
            Object value = Integer.valueOf(round[0]);
            array.lazySet(i, value);
            reference.set(value);
            int compiled = invoke(readAndSwap, array, reference, i);
            assertEquals(Integer.valueOf(i), reference.get());
            return compiled == 1;
        });
    }

    /**
     * AOT collection methods execute natively and never publish interpreter LinkedInvoke entries.
     * Their already-resolved constant pools must still allow the caller to stay compiled.
     */
    @Test
    public void compiledCodeStaysCompiledAcrossAOTCollectionCalls() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method grow = loader.loadClass(CollectionUser.class.getName()).getMethod("grow", int.class, int[].class);
        awaitCompiled("CollectionUser.grow", () -> {
            int[] compiled = new int[1];
            assertEquals(8, (int) invoke(grow, 8, compiled));
            return compiled[0] == 1;
        });
    }

    /**
     * Compiled code calls an AOT method that loads a string constant. Its build-time constant pool
     * stores the string as a pre-resolved appendix entry, which Ristretto failed to read with a
     * fatal "Unknown tag INVOKEDYNAMIC" when it inlined the method (elide-dev/graal#122).
     */
    @Test
    public void compiledCodeCallsAOTMethodsWithStringConstants() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method pick = loader.loadClass(ConstantUser.class.getName()).getMethod("pick", Object.class, Object.class, int[].class);
        Object fallback = new Object();
        awaitCompiled("ConstantUser.pick", () -> {
            int[] compiled = new int[1];
            assertTrue(invoke(pick, null, fallback, compiled) == fallback);
            return compiled[0] == 1;
        });
        assertThrows(NullPointerException.class, () -> invokeThrowing(pick, null, null, new int[1]));
    }

    /**
     * Compiled code calls constant method handles to static, virtual, interface and constructor
     * targets directly (elide-dev/graal#112): the results, a {@link WrongMethodTypeException} for
     * a call of the wrong type and a {@link NullPointerException} for a null receiver are those of
     * the interpreter.
     */
    @Test
    public void constantMethodHandleCallsCompileToDirectCalls() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Class<?> shapeClass = loader.loadClass(Shape.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Class<?> counterClass = loader.loadClass(Counter.class.getName());
        Object counter = counterClass.getConstructor().newInstance();
        Class<?> handlesClass = bindHandles(loader);
        Method exact = handlesClass.getMethod("exact", holderClass, int.class, counterClass, int[].class);
        Method wrongType = handlesClass.getMethod("wrongType", int.class, int[].class);
        Method value = handlesClass.getMethod("value", shapeClass, int[].class);

        int n = 100;
        /* Each iteration adds 2 * i + 5 + One.value(). */
        long expected = (long) n * (n - 1) + 6L * n;
        awaitCompiled("Handles.exact", () -> {
            int[] compiled = new int[1];
            assertEquals(expected, (long) invoke(exact, holder, n, counter, compiled));
            return compiled[0] == n;
        });
        assertTrue("the interface handle must run the counter", (int) counterClass.getField("runs").get(counter) >= n);

        int[] compiled = new int[1];
        assertThrows(WrongMethodTypeException.class, () -> invokeThrowing(wrongType, 1, compiled));
        awaitCompiled("Handles.wrongType", () -> {
            int[] c = new int[1];
            assertThrows(WrongMethodTypeException.class, () -> invokeThrowing(wrongType, 1, c));
            return c[0] == 1;
        });

        Object one = holderClass.getField("shape").get(holder);
        assertThrows(NullPointerException.class, () -> invokeThrowing(value, null, new int[1]));
        awaitCompiled("Handles.value", () -> {
            int[] c = new int[1];
            assertEquals(1, (int) invoke(value, one, c));
            return c[0] == 1;
        });
        assertThrows(NullPointerException.class, () -> invokeThrowing(value, null, new int[1]));
    }

    /**
     * Compiled code calls a constant method handle to {@link Handles#hooked}, whose hook loads a
     * second {@link Shape} and so invalidates the code, which called {@link Shape#value()} through
     * another constant handle assuming a single shape. The frame of the inlined target and the
     * guest frame both resume in the interpreter.
     */
    @Test
    public void deoptimizationInConstantMethodHandleTargetResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Method lazy = bindHandles(loader).getMethod("lazy", holderClass, int.class, Runnable.class, int[].class);

        Runnable noHook = () -> {
        };
        awaitCompiled("Handles.lazy", () -> {
            int[] compiled = new int[3];
            long sum = invoke(lazy, holder, 10, noHook, compiled);
            assertEquals(10 + 55, sum);
            return compiled[0] == 5 && compiled[2] == 5;
        });

        Runnable loadSecondShape = () -> {
            try {
                holderClass.getField("shape").set(holder, loader.loadClass(Two.class.getName()).getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        int n = 1000;
        int[] compiled = new int[3];
        long sum = invoke(lazy, holder, n, loadSecondShape, compiled);
        /* The hook runs after the Shape call of its iteration, so n / 2 + 1 iterations see One. */
        long expected = (n / 2 + 1) * 1L + (n / 2 - 1) * 2L + (long) n * (n + 1) / 2;
        assertEquals("iterations after the hook must call Two.value()", expected, sum);
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("the handle target must resume in the interpreter after the hook", 0, compiled[1]);
        assertEquals("iterations after the hook must run in the interpreter", 0, compiled[2]);
    }

    /**
     * Compiled code uses {@code invokedynamic} call sites. The target of a capturing lambda's call
     * site is a constructor handle of the lambda class; Ristretto replaced the call site by an
     * allocation and a constructor call, but the parser verified the frame state of the call
     * before the new instance was on the operand stack and bailed out of every method with such a
     * lambda (elide-dev/graal#131). Method references link to other handle kinds.
     */
    @Test
    public void compiledCodeUsesInvokeDynamicCallSites() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Class<?> lambdasClass = loader.loadClass(Lambdas.class.getName());
        Class<?> counterClass = loader.loadClass(Counter.class.getName());
        Object counter = counterClass.getConstructor().newInstance();
        Method capturing = lambdasClass.getMethod("capturing", int.class, int.class, int[].class);
        Method methodReferences = lambdasClass.getMethod("methodReferences", counterClass, int.class, int[].class);

        int n = 8;
        awaitCompiled("Lambdas.capturing", () -> {
            int[] compiled = new int[1];
            /* Each iteration adds i + 3. */
            assertEquals(n * (n - 1) / 2 + 3 * n, (int) invoke(capturing, 3, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Lambdas.methodReferences", () -> {
            int[] compiled = new int[1];
            assertEquals(n * (n - 1), (int) invoke(methodReferences, counter, n, compiled));
            return compiled[0] == 1;
        });
        assertTrue("the bound method reference must run the counter", (int) counterClass.getField("runs").get(counter) >= n);
    }

    /**
     * Ristretto compiles the loop of {@link Updater#run} through OSR, with the AOT methods
     * {@link AtomicReferenceFieldUpdater#get} and {@link AtomicReferenceFieldUpdater#set} inlined.
     * The compiled code deoptimizes at a call site that the interpreter had not linked when the
     * code was compiled, and that deoptimization is moved up to the entry of the compiled code. The
     * interpreter must resume with the locals it transferred to the compiled code, not with nulls
     * (elide-dev/graal#129).
     */
    @Test
    public void deoptimizationAtOSREntryResumesWithTransferredLocals() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader();
        Method run = loader.loadClass(Updater.class.getName()).getMethod("run", int[].class);
        long expected = 0;
        for (int round = 0; round < Updater.ROUNDS; round++) {
            expected += round & 63;
        }
        long deadline = System.nanoTime() + COMPILATION_TIMEOUT_NANOS;
        int[] compiled = new int[1];
        while (compiled[0] == 0) {
            assertTrue("Ristretto did not run Updater.run through OSR", System.nanoTime() < deadline);
            assertEquals(expected, (long) invoke(run, compiled));
        }
    }

    /** Calls {@code compiledCall} until it reports compiled execution 100 times in a row. */
    static void awaitCompiled(String what, BooleanSupplier compiledCall) {
        long deadline = System.nanoTime() + COMPILATION_TIMEOUT_NANOS;
        int consecutive = 0;
        while (consecutive < 100) {
            assertTrue("Ristretto did not compile " + what, System.nanoTime() < deadline);
            consecutive = compiledCall.getAsBoolean() ? consecutive + 1 : 0;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(Method method, Object... args) {
        try {
            return (T) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Like {@link #invoke}, but rethrows the exception of the invoked method. */
    static Object invokeThrowing(Method method, Object... args) throws Throwable {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static String classFileName(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    /**
     * Defines fresh copies of {@link #RUNTIME_CLASSES} (or of other classes whose class files
     * {@link TestFeature} registers), with {@link Probe#compiled()} rewritten.
     */
    static final class RuntimeClassLoader extends ClassLoader {
        private static final ClassDesc PROBE = ClassDesc.of(Probe.class.getName());
        private static final ClassDesc DIRECTIVES = ClassDesc.of("com.oracle.svm.interpreter.ristretto.RistrettoDirectives");

        private final Map<String, byte[]> classFiles = new HashMap<>();

        RuntimeClassLoader() throws IOException {
            this(RUNTIME_CLASSES);
        }

        RuntimeClassLoader(List<Class<?>> runtimeClasses) throws IOException {
            super(RistrettoDeoptimizationTest.class.getClassLoader());
            for (Class<?> c : runtimeClasses) {
                try (InputStream in = RistrettoDeoptimizationTest.class.getModule().getResourceAsStream(classFileName(c))) {
                    assertTrue("missing class file of " + c.getName(), in != null);
                    classFiles.put(c.getName(), rewriteProbe(in.readAllBytes()));
                }
            }
        }

        private static byte[] rewriteProbe(byte[] classFile) {
            ClassFile cf = ClassFile.of();
            return cf.transformClass(cf.parse(classFile), ClassTransform.transformingMethodBodies(CodeTransform.ofStateful(() -> (builder, element) -> {
                if (element instanceof InvokeInstruction invoke && invoke.owner().asSymbol().equals(PROBE) && invoke.name().equalsString("compiled")) {
                    /* The boolean result is an int on the operand stack. */
                    builder.invokestatic(DIRECTIVES, "inRuntimeCompiledCode", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                } else {
                    builder.with(element);
                }
            })));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] classFile = classFiles.get(name);
            if (classFile == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, classFile, 0, classFile.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
