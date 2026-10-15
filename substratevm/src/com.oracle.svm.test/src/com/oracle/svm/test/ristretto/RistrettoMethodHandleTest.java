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
import static com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.invokeThrowing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.List;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.hosted.RuntimeJNIAccess;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Holder;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.One;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Probe;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.RuntimeClassLoader;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Shape;
import com.oracle.svm.test.ristretto.RistrettoDeoptimizationTest.Two;

/**
 * Calls through method handles that are not compile-time constants, to methods of classes loaded
 * at run time (elide-dev/graal#112).
 * <p>
 * Such a call runs a chain of lambda forms whose {@code invokeBasic}, {@code linkToStatic} and
 * {@code linkToSpecial} calls went through the interpreter at every hop, boxing the arguments,
 * both in code that Ristretto compiled and in image code (the lambda forms of the image and calls
 * from image code, the host-binding path). Now such a call goes to the compiled code of its target
 * directly when there is some. The tests check results and exceptions of calls from compiled code
 * and from image code, that the calls no longer allocate once their targets are compiled, and a
 * deoptimization of a compiled caller during such a call. They run like
 * {@link RistrettoDeoptimizationTest}, whose {@code TestFeature} registers the class files of
 * {@link #RUNTIME_CLASSES}.
 */
public class RistrettoMethodHandleTest {

    /** Called by {@code RistrettoDeoptimizationTest.TestFeature} before analysis. */
    static void beforeAnalysis() {
        try {
            /* The interpreter runs the lambda forms of the image (as with -H:Preserve). */
            for (String holder : new String[]{"java.lang.invoke.DirectMethodHandle$Holder", "java.lang.invoke.Invokers$Holder", "java.lang.invoke.LambdaForm$Holder",
                            "java.lang.invoke.DelegatingMethodHandle$Holder"}) {
                RuntimeJNIAccess.register(Class.forName(holder).getDeclaredMethods());
            }
            RuntimeJNIAccess.register(MethodHandle.class.getMethod("invokeWithArguments", Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The targets of the handles. */
    public static final class Target {
        public static int compiledCalls;

        public int base = 1;

        public static int add(int a, int b) {
            compiledCalls += Probe.compiled();
            return a + b;
        }

        public static boolean not(boolean b) {
            return !b;
        }

        public static long mix(long l, double d, Object o, byte b) {
            return l * 31 + (long) d * 7 + (o == null ? 0 : 1) + b;
        }

        public static int check(int x) {
            if (x < 0) {
                throw new IllegalStateException("negative");
            }
            return x;
        }

        public int get() {
            compiledCalls += Probe.compiled();
            return base;
        }

        /** Statically bound: a method of a final class. */
        public int twice() {
            return 2 * base;
        }

        /** Invoked with {@code linkToSpecial}, as a private method. */
        private int secret() {
            return base + 40;
        }

        /** Returns {@code x + 1}, and runs {@code hook} first if it is not null. */
        public static int hooked(int x, Runnable hook) {
            if (hook != null) {
                hook.run();
            }
            return x + 1;
        }
    }

    /** Calls handles it is given, so that they are not constants. */
    public static final class Calls {
        /** Sums {@code add(acc, i + 1)} over {@code n} iterations with exact invocations. */
        public static int exactStatic(MethodHandle add, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc = (int) add.invokeExact(acc, i + 1);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums {@code add(acc, get(o) + i)} over {@code n} iterations with exact invocations. */
        public static int exact(MethodHandle add, MethodHandle get, Target o, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc = (int) add.invokeExact(acc, (int) get.invokeExact(o) + i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** The same sum with generic invocations, whose types the handles adapt. */
        public static int generic(MethodHandle add, MethodHandle get, Object o, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc = (int) add.invoke((Object) acc, (Object) ((int) get.invoke(o) + i));
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** The same sum with {@code invokeWithArguments}. */
        public static int withArguments(MethodHandle add, MethodHandle get, Object o, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc = (int) add.invokeWithArguments(acc, (int) get.invokeWithArguments(o) + i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Calls handles with other parameter and return types. */
        public static long kinds(MethodHandle not, MethodHandle mix, MethodHandle twice, Target o, int n, int[] compiled) throws Throwable {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                sum += (boolean) not.invokeExact((i & 1) == 0) ? 1 : 0;
                sum += (long) mix.invokeExact((long) i, 2.5 * i, (Object) (i % 3 == 0 ? null : o), (byte) i);
                sum += (int) twice.invokeExact(o);
            }
            compiled[0] += Probe.compiled();
            return sum;
        }

        public static int check(MethodHandle check, int x, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) check.invokeExact(x);
        }

        public static int special(MethodHandle secret, Target o, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) secret.invokeExact(o);
        }

        /**
         * Sums {@code holder.shape.value() + i + 1} over {@code n} iterations and runs {@code hook}
         * inside {@code hooked} halfway. {@code compiled[0]} and {@code compiled[1]} count the
         * compiled iterations before and after the hook.
         */
        public static long lazy(MethodHandle hooked, Holder holder, int n, Runnable hook, int[] compiled) throws Throwable {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                sum += holder.shape.value();
                Runnable iterationHook = i == n / 2 ? hook : null;
                sum += (int) hooked.invokeExact(i, iterationHook);
                compiled[i < n / 2 ? 0 : 1] += Probe.compiled();
            }
            return sum;
        }
    }

    static final List<Class<?>> RUNTIME_CLASSES = List.of(Target.class, Calls.class, Shape.class, One.class, Two.class, Holder.class);

    @Before
    public void requireRistretto() {
        Assume.assumeTrue("Native Image only", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Run with -D" + RistrettoDeoptimizationTest.REQUIRE_RISTRETTO_PROPERTY + "=true in an image with Ristretto",
                        Boolean.getBoolean(RistrettoDeoptimizationTest.REQUIRE_RISTRETTO_PROPERTY));
    }

    /** The handles to the methods of a run-time {@link Target} class. */
    private record Handles(Class<?> target, MethodHandle add, MethodHandle get, MethodHandle getObject, MethodHandle not, MethodHandle mix, MethodHandle twice, MethodHandle check,
                    MethodHandle secret, MethodHandle hooked) {
        static Handles bind(RuntimeClassLoader loader) throws Throwable {
            Class<?> target = Class.forName(Target.class.getName(), true, loader);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle get = lookup.findVirtual(target, "get", MethodType.methodType(int.class));
            return new Handles(target,
                            lookup.findStatic(target, "add", MethodType.methodType(int.class, int.class, int.class)),
                            get,
                            get.asType(MethodType.methodType(int.class, Object.class)),
                            lookup.findStatic(target, "not", MethodType.methodType(boolean.class, boolean.class)),
                            lookup.findStatic(target, "mix", MethodType.methodType(long.class, long.class, double.class, Object.class, byte.class)),
                            lookup.findVirtual(target, "twice", MethodType.methodType(int.class)),
                            lookup.findStatic(target, "check", MethodType.methodType(int.class, int.class)),
                            MethodHandles.privateLookupIn(target, lookup).findVirtual(target, "secret", MethodType.methodType(int.class)),
                            lookup.findStatic(target, "hooked", MethodType.methodType(int.class, int.class, Runnable.class)));
        }
    }

    private static int expectedSum(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc = acc + 1 + i;
        }
        return acc;
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    /**
     * Compiled code calls handles that are not constants with {@code invokeExact}, {@code invoke}
     * and {@code invokeWithArguments}, and gets their results. Once a static target is compiled,
     * exact calls to it do not allocate: no hop of their lambda forms boxes its arguments.
     */
    @Test
    public void compiledCodeCallsNonConstantHandles() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Handles h = Handles.bind(loader);
        Class<?> callsClass = loader.loadClass(Calls.class.getName());
        Method exact = callsClass.getMethod("exact", MethodHandle.class, MethodHandle.class, h.target(), int.class, int[].class);
        Method generic = callsClass.getMethod("generic", MethodHandle.class, MethodHandle.class, Object.class, int.class, int[].class);
        Method withArguments = callsClass.getMethod("withArguments", MethodHandle.class, MethodHandle.class, Object.class, int.class, int[].class);
        Object o = h.target().getConstructor().newInstance();

        int n = 100;
        awaitCompiled("Calls.exact", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSum(n), (int) invoke(exact, h.add(), h.get(), o, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.generic", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSum(n), (int) invoke(generic, h.add(), h.getObject(), o, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.withArguments", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSum(n), (int) invoke(withArguments, h.add(), h.getObject(), o, n, compiled));
            return compiled[0] == 1;
        });

        /*
         * Calls to a static target do not allocate once it is compiled. (A virtual target is still
         * selected through the interpreter: linkToVirtual is not handled.)
         */
        Method exactStatic = callsClass.getMethod("exactStatic", MethodHandle.class, int.class, int[].class);
        awaitCompiled("Calls.exactStatic", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSum(n), (int) invoke(exactStatic, h.add(), n, compiled));
            return compiled[0] == 1;
        });
        awaitTargetCompiled(h, n, () -> invoke(exactStatic, h.add(), n, new int[1]));
        int many = 100_000;
        int[] compiled = new int[1];
        long before = allocatedBytes();
        int sum = invoke(exactStatic, h.add(), many, compiled);
        long allocated = allocatedBytes() - before;
        assertEquals(expectedSum(many), sum);
        assertEquals(1, compiled[0]);
        assertTrue("calls through handles that are not constants must not allocate, but " + many + " iterations allocated " + allocated + " bytes", allocated < many);
    }

    /** Waits until all {@code calls} calls of {@code Target.add} made by {@code call} run compiled. */
    private static void awaitTargetCompiled(Handles h, int calls, Runnable call) throws ReflectiveOperationException {
        java.lang.reflect.Field compiledCalls = h.target().getField("compiledCalls");
        awaitCompiled("Target.add", () -> {
            try {
                compiledCalls.setInt(null, 0);
                call.run();
                return compiledCalls.getInt(null) == calls;
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
    }

    /**
     * Compiled code calls handles with boolean, long, double, reference and byte parameters and
     * results, a final method, a private method ({@code linkToSpecial}), and targets that throw.
     */
    @Test
    public void compiledCodeCallsNonConstantHandlesOfAllKinds() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Handles h = Handles.bind(loader);
        Class<?> callsClass = loader.loadClass(Calls.class.getName());
        Method kinds = callsClass.getMethod("kinds", MethodHandle.class, MethodHandle.class, MethodHandle.class, h.target(), int.class, int[].class);
        Method check = callsClass.getMethod("check", MethodHandle.class, int.class, int[].class);
        Method special = callsClass.getMethod("special", MethodHandle.class, h.target(), int[].class);
        Object o = h.target().getConstructor().newInstance();

        int n = 50;
        long expected = 0;
        for (int i = 0; i < n; i++) {
            expected += (i & 1) == 0 ? 0 : 1;
            expected += (long) i * 31 + (long) (2.5 * i) * 7 + (i % 3 == 0 ? 0 : 1) + (byte) i;
            expected += 2;
        }
        long expectedKinds = expected;
        awaitCompiled("Calls.kinds", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedKinds, (long) invoke(kinds, h.not(), h.mix(), h.twice(), o, n, compiled));
            return compiled[0] == 1;
        });

        awaitCompiled("Calls.check", () -> {
            int[] compiled = new int[1];
            assertEquals(5, (int) invoke(check, h.check(), 5, compiled));
            return compiled[0] == 1;
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> invokeThrowing(check, h.check(), -3, new int[1]));
        assertEquals("negative", e.getMessage());
        assertThrows(WrongMethodTypeException.class, () -> invokeThrowing(check, h.add(), 1, new int[1]));
        assertThrows(NullPointerException.class, () -> invokeThrowing(check, null, 1, new int[1]));

        awaitCompiled("Calls.special", () -> {
            int[] compiled = new int[1];
            assertEquals(41, (int) invoke(special, h.secret(), o, compiled));
            return compiled[0] == 1;
        });
        assertThrows(NullPointerException.class, () -> invokeThrowing(special, h.secret(), null, new int[1]));
        assertEquals(41, (int) invoke(special, h.secret(), o, new int[1]));
    }

    /**
     * Image code calls handles to run-time methods (the host-binding path): {@code invokeExact}
     * and {@code invoke} of handles of all kinds, with their results and exceptions. Once a static
     * target is compiled, exact calls to it do not allocate.
     */
    @Test
    public void imageCodeCallsHandlesOfRuntimeMethods() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Handles h = Handles.bind(loader);
        Object o = h.target().getConstructor().newInstance();
        MethodHandle get = h.getObject();
        MethodHandle twice = h.twice().asType(MethodType.methodType(int.class, Object.class));
        MethodHandle secret = h.secret().asType(MethodType.methodType(int.class, Object.class));

        for (int round = 0; round < 3_000; round++) {
            assertEquals(round + 1, (int) h.add().invokeExact(round, 1));
            assertEquals(1, (int) get.invokeExact(o));
            assertEquals(2, (int) twice.invokeExact(o));
            assertEquals(41, (int) secret.invokeExact(o));
            assertEquals(false, (boolean) h.not().invokeExact(true));
            assertEquals(31L * round + 7 + 1 + 3, (long) h.mix().invokeExact((long) round, 1.0, o, (byte) 3));
            assertEquals(round + 2, (int) h.add().invoke((Object) round, (Object) 2));
            assertEquals(7, (int) h.check().invokeExact(7));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            int unused = (int) h.check().invokeExact(-1);
            throw new AssertionError(unused);
        });
        assertEquals("negative", e.getMessage());
        assertThrows(NullPointerException.class, () -> {
            int unused = (int) secret.invokeExact((Object) null);
            throw new AssertionError(unused);
        });

        MethodHandle add = h.add();
        awaitTargetCompiled(h, 100, () -> {
            try {
                for (int i = 0; i < 100; i++) {
                    assertEquals(i + 1, (int) add.invokeExact(i, 1));
                }
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        int many = 100_000;
        int acc = 0;
        long before = allocatedBytes();
        for (int i = 0; i < many; i++) {
            acc = (int) add.invokeExact(acc, i + 1);
        }
        long allocated = allocatedBytes() - before;
        assertEquals(expectedSum(many), acc);
        assertTrue("image code calling compiled run-time methods through handles must not allocate, but " + many + " iterations allocated " + allocated + " bytes", allocated < many);
    }

    /**
     * Loading a second {@link Shape} inside a target called through a handle that is not a
     * constant invalidates the compiled caller, which assumed a single shape, while it waits for
     * the call. The caller resumes in the interpreter with the call's result.
     */
    @Test
    public void deoptimizationDuringNonConstantHandleCallResumesInInterpreter() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Handles h = Handles.bind(loader);
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Method lazy = loader.loadClass(Calls.class.getName()).getMethod("lazy", MethodHandle.class, holderClass, int.class, Runnable.class, int[].class);

        Runnable noHook = () -> {
        };
        awaitCompiled("Calls.lazy", () -> {
            int[] compiled = new int[2];
            assertEquals(10 + 55, (long) invoke(lazy, h.hooked(), holder, 10, noHook, compiled));
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
        long sum = invoke(lazy, h.hooked(), holder, n, loadSecondShape, compiled);
        /* The hook runs after the Shape call of its iteration, so n / 2 + 1 iterations see One. */
        long expected = (n / 2 + 1) * 1L + (n / 2 - 1) * 2L + (long) n * (n + 1) / 2;
        assertEquals("iterations after the hook must call Two.value()", expected, sum);
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("iterations after the hook must run in the interpreter", 0, compiled[1]);
    }
}
