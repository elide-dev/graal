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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
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
 * Such a call runs a chain of lambda forms whose {@code invokeBasic} and {@code linkTo*} calls went
 * through the interpreter at every hop, boxing the arguments,
 * both in code that Ristretto compiled and in image code (the lambda forms of the image and calls
 * from image code, the host-binding path). Now such a call goes to the compiled code of its target
 * directly when there is some; for {@code linkToVirtual} and {@code linkToInterface}, the compiled
 * code of the method that the receiver's class selects. The tests check results and exceptions of
 * calls from compiled code and from image code, that the calls no longer allocate once their
 * targets are compiled, and deoptimizations of a compiled caller and of a compiled target during
 * such a call. They run like
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

        public static int noArguments() {
            compiledCalls += Probe.compiled();
            return 7;
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

    /** An interface method, called with {@code linkToInterface}. */
    public interface Speaker {
        int speak(int x);
    }

    /** A virtual method with several implementations, called with {@code linkToVirtual}. */
    public abstract static class Base implements Speaker {
        public static int compiledCalls;

        public abstract int value();

        @Override
        public int speak(int x) {
            return x + value();
        }

        /** A virtual method with an uncommon signature, whose lambda form is not pregenerated. */
        public long mixed(long l, double d, byte b) {
            return l * 3 + (long) d + b + value();
        }
    }

    public static final class Cat extends Base {
        @Override
        public int value() {
            compiledCalls += Probe.compiled();
            return 1;
        }
    }

    public static final class Dog extends Base {
        @Override
        public int value() {
            compiledCalls += Probe.compiled();
            return 2;
        }

        @Override
        public int speak(int x) {
            return 2 * x + value();
        }
    }

    public static final class Cow extends Base {
        @Override
        public int value() {
            compiledCalls += Probe.compiled();
            return 3;
        }
    }

    /** Implements {@link Speaker} without extending {@link Base}: another itable layout. */
    public static final class Parrot implements Speaker {
        @Override
        public int speak(int x) {
            Base.compiledCalls += Probe.compiled();
            return x - 1;
        }
    }

    /**
     * {@link #call} is inherited by both subclasses. While only {@link Kitten} is loaded, compiled
     * code of {@link #call} may assume that {@link #sound} is {@link Kitten#sound}; loading
     * {@link Puppy} invalidates it.
     */
    public abstract static class Animal {
        public static int compiledCalls;
        /** Run once by the next {@link #call}, before {@link #sound}. */
        public static Runnable hook;

        public abstract int sound();

        public int call(int x) {
            compiledCalls += Probe.compiled();
            Runnable h = hook;
            if (h != null) {
                hook = null;
                h.run();
            }
            return x + sound();
        }
    }

    public static final class Kitten extends Animal {
        @Override
        public int sound() {
            return 1;
        }
    }

    /** Loaded only once compiled code of {@link Animal#call} has assumed a single animal. */
    public static final class Puppy extends Animal {
        @Override
        public int sound() {
            return 2;
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

        /** Sums {@code value(receivers[i % length])} with exact invocations. */
        public static int virtualExact(MethodHandle value, Base[] receivers, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) value.invokeExact(receivers[i % receivers.length]);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums {@code speak(receivers[i % length], i)} with exact invocations. */
        public static int interfaceExact(MethodHandle speak, Speaker[] receivers, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) speak.invokeExact(receivers[i % receivers.length], i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** The same sum with generic invocations, which adapt the handle's type. */
        public static int interfaceGeneric(MethodHandle speak, Object[] receivers, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) speak.invoke(receivers[i % receivers.length], (Object) i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums {@code value(receivers[i % length])} through a handle viewed as {@code (Object)int}. */
        public static int virtualAsType(MethodHandle value, Object[] receivers, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) value.invokeExact(receivers[i % receivers.length]);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums the results of a bound handle. */
        public static int bound(MethodHandle bound, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) bound.invokeExact();
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums {@code mixed(receivers[i % length], i, i / 2.0, (byte) i)}. */
        public static long virtualMixed(MethodHandle mixed, Base[] receivers, int n, int[] compiled) throws Throwable {
            long acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (long) mixed.invokeExact(receivers[i % receivers.length], (long) i, i / 2.0, (byte) i);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        /** Sums {@code length(receivers[i % length])} through a handle to an image interface method. */
        public static int imageInterface(MethodHandle length, CharSequence[] receivers, int n, int[] compiled) throws Throwable {
            int acc = 0;
            for (int i = 0; i < n; i++) {
                acc += (int) length.invokeExact(receivers[i % receivers.length]);
            }
            compiled[0] += Probe.compiled();
            return acc;
        }

        public static int virtualOne(MethodHandle value, Base receiver, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) value.invokeExact(receiver);
        }

        public static int interfaceOne(MethodHandle speak, Speaker receiver, int[] compiled) throws Throwable {
            compiled[0] += Probe.compiled();
            return (int) speak.invokeExact(receiver, 1);
        }

        /**
         * Sums {@code call(receivers[0], i)} over {@code n} iterations and sets {@code Animal.hook}
         * to {@code hook} halfway. {@code compiled[0]} and {@code compiled[1]} count the compiled
         * iterations before and after the hook.
         */
        public static long animals(MethodHandle call, Animal[] receivers, int n, Runnable hook, int[] compiled) throws Throwable {
            long sum = 0;
            for (int i = 0; i < n; i++) {
                if (i == n / 2) {
                    Animal.hook = hook;
                }
                sum += (int) call.invokeExact(receivers[0], i);
                compiled[i < n / 2 ? 0 : 1] += Probe.compiled();
            }
            return sum;
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

    static final List<Class<?>> RUNTIME_CLASSES = List.of(Target.class, Calls.class, Shape.class, One.class, Two.class, Holder.class, Speaker.class, Base.class, Cat.class, Dog.class,
                    Cow.class, Parrot.class, Animal.class, Kitten.class, Puppy.class);

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

        /* Calls to a static target do not allocate once it is compiled. */
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
     * Image code calls a handle of a static run-time method without arguments, which is not a
     * constant: {@code invokeBasic} gets only the handle. Once the target is compiled, the calls do
     * not allocate.
     */
    @Test
    public void imageCodeCallsZeroArgumentHandleWithoutAllocating() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> target = loader.loadClass(Target.class.getName());
        MethodHandle handle = MethodHandles.lookup().findStatic(target, "noArguments", MethodType.methodType(int.class));
        Field compiledCalls = target.getField("compiledCalls");
        awaitCompiled("Target.noArguments", () -> {
            try {
                compiledCalls.setInt(null, 0);
                for (int i = 0; i < 100; i++) {
                    assertEquals(7, (int) handle.invokeExact());
                }
                return compiledCalls.getInt(null) == 100;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        int many = 100_000;
        /* The first round can still compile the lambda forms of the handle. */
        for (int round = 0; round < 2; round++) {
            int sum = 0;
            long before = allocatedBytes();
            for (int i = 0; i < many; i++) {
                sum += (int) handle.invokeExact();
            }
            long allocated = allocatedBytes() - before;
            assertEquals(7 * many, sum);
            if (round == 1) {
                assertTrue("image code calling a compiled run-time method through a zero-argument handle must not allocate, but " + many + " iterations allocated " + allocated +
                                " bytes", allocated < many);
            }
        }
    }

    /**
     * Image code calls bound handles of virtual and interface methods of run-time classes that take
     * no arguments: {@code invokeBasic} gets only the handle, and the lambda form of the bound
     * handle links to the target with {@code linkToVirtual} or {@code linkToInterface}. Once the
     * targets are compiled, the calls do not allocate.
     */
    @Test
    public void imageCodeCallsBoundZeroArgumentVirtualAndInterfaceHandles() throws Throwable {
        Virtuals v = Virtuals.bind();
        MethodHandle[] values = new MethodHandle[3];
        for (int k = 0; k < values.length; k++) {
            values[k] = v.value().bindTo(v.bases()[k]);
        }
        MethodHandle[] speaks = new MethodHandle[4];
        for (int k = 0; k < speaks.length; k++) {
            speaks[k] = MethodHandles.insertArguments(v.speak(), 0, v.speakers()[k], k);
        }
        /* value() of Cat, Dog and Cow; speak(k) of Cat, Dog, Cow and Parrot (see expectedSpeakSum). */
        int[] expectedValues = {1, 2, 3};
        int[] expectedSpeaks = {1, 4, 5, 2};
        awaitCompiled("the bound zero-argument targets", () -> {
            try {
                v.resetCompiledCalls();
                for (int i = 0; i < 84; i++) {
                    assertEquals(expectedValues[i % 3], (int) values[i % 3].invokeExact());
                    assertEquals(expectedSpeaks[i % 4], (int) speaks[i % 4].invokeExact());
                }
                return v.compiledCalls() == 2 * 84;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        int many = 100_000;
        int expected = 0;
        for (int i = 0; i < many; i++) {
            expected += expectedValues[i % 3] + expectedSpeaks[i % 4];
        }
        for (int round = 0; round < 2; round++) {
            int acc = 0;
            long before = allocatedBytes();
            for (int i = 0; i < many; i++) {
                acc += (int) values[i % 3].invokeExact();
                acc += (int) speaks[i % 4].invokeExact();
            }
            long allocated = allocatedBytes() - before;
            assertEquals(expected, acc);
            if (round == 1) {
                assertTrue("image code calling compiled targets through bound zero-argument virtual and interface handles must not allocate, but " + many + " iterations allocated " +
                                allocated + " bytes", allocated < many);
            }
        }
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

    /** The run-time classes of {@link Base}, {@link Speaker} and their handles. */
    private record Virtuals(RuntimeClassLoader loader, Class<?> base, Class<?> speaker, Object[] bases, Object[] speakers, MethodHandle value, MethodHandle speak, MethodHandle mixed) {
        static Virtuals bind() throws Throwable {
            RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
            Class<?> base = Class.forName(Base.class.getName(), true, loader);
            Class<?> speaker = Class.forName(Speaker.class.getName(), true, loader);
            Object[] bases = (Object[]) Array.newInstance(base, 3);
            bases[0] = loader.loadClass(Cat.class.getName()).getConstructor().newInstance();
            bases[1] = loader.loadClass(Dog.class.getName()).getConstructor().newInstance();
            bases[2] = loader.loadClass(Cow.class.getName()).getConstructor().newInstance();
            Object[] speakers = (Object[]) Array.newInstance(speaker, 4);
            System.arraycopy(bases, 0, speakers, 0, 3);
            speakers[3] = loader.loadClass(Parrot.class.getName()).getConstructor().newInstance();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return new Virtuals(loader, base, speaker, bases, speakers,
                            lookup.findVirtual(base, "value", MethodType.methodType(int.class)),
                            lookup.findVirtual(speaker, "speak", MethodType.methodType(int.class, int.class)),
                            lookup.findVirtual(base, "mixed", MethodType.methodType(long.class, long.class, double.class, byte.class)));
        }

        Method call(String name, Class<?>... parameterTypes) throws ReflectiveOperationException {
            return loader.loadClass(Calls.class.getName()).getMethod(name, parameterTypes);
        }

        void resetCompiledCalls() throws ReflectiveOperationException {
            base.getField("compiledCalls").setInt(null, 0);
        }

        int compiledCalls() throws ReflectiveOperationException {
            return base.getField("compiledCalls").getInt(null);
        }
    }

    /** {@code value()} of Cat, Dog and Cow is 1, 2 and 3. */
    private static int expectedValueSum(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += i % 3 + 1;
        }
        return acc;
    }

    /** {@code speak(i)} of Cat, Dog, Cow and Parrot is i + 1, 2i + 2, i + 3 and i - 1. */
    private static int expectedSpeakSum(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += switch (i % 4) {
                case 0 -> i + 1;
                case 1 -> 2 * i + 2;
                case 2 -> i + 3;
                default -> i - 1;
            };
        }
        return acc;
    }

    private static long expectedMixedSum(int n) {
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += (long) i * 3 + (long) (i / 2.0) + (byte) i + i % 3 + 1;
        }
        return acc;
    }

    /**
     * Compiled code calls virtual and interface handles that are not constants, with receivers of
     * several run-time classes, exactly, generically, through {@code asType} and bound handles, and
     * gets their results. Once the targets are compiled, the exact calls do not allocate: the
     * {@code linkToVirtual} and {@code linkToInterface} hops select the compiled target of the
     * receiver instead of boxing the arguments for the interpreter.
     */
    @Test
    public void compiledCodeCallsVirtualAndInterfaceHandles() throws Throwable {
        Virtuals v = Virtuals.bind();
        Class<?> baseArray = v.bases().getClass();
        Class<?> speakerArray = v.speakers().getClass();
        Method virtualExact = v.call("virtualExact", MethodHandle.class, baseArray, int.class, int[].class);
        Method interfaceExact = v.call("interfaceExact", MethodHandle.class, speakerArray, int.class, int[].class);
        Method interfaceGeneric = v.call("interfaceGeneric", MethodHandle.class, Object[].class, int.class, int[].class);
        Method virtualAsType = v.call("virtualAsType", MethodHandle.class, Object[].class, int.class, int[].class);
        Method bound = v.call("bound", MethodHandle.class, int.class, int[].class);
        Method virtualMixed = v.call("virtualMixed", MethodHandle.class, baseArray, int.class, int[].class);
        Object[] objects = v.speakers().clone();
        Object[] objectBases = v.bases().clone();
        MethodHandle valueOfObject = v.value().asType(MethodType.methodType(int.class, Object.class));
        MethodHandle boundValue = v.value().bindTo(v.bases()[1]);

        int n = 120;
        awaitCompiled("Calls.virtualExact", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedValueSum(n), (int) invoke(virtualExact, v.value(), v.bases(), n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.interfaceExact", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSpeakSum(n), (int) invoke(interfaceExact, v.speak(), v.speakers(), n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.interfaceGeneric", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedSpeakSum(n), (int) invoke(interfaceGeneric, v.speak(), objects, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.virtualAsType", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedValueSum(n), (int) invoke(virtualAsType, valueOfObject, objectBases, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.bound", () -> {
            int[] compiled = new int[1];
            assertEquals(2 * n, (int) invoke(bound, boundValue, n, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.virtualMixed", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedMixedSum(n), (long) invoke(virtualMixed, v.mixed(), v.bases(), n, compiled));
            return compiled[0] == 1;
        });

        /* The targets run compiled, and calls to them do not allocate. */
        awaitCompiled("the value() targets", () -> {
            try {
                v.resetCompiledCalls();
                invoke(virtualExact, v.value(), v.bases(), n, new int[1]);
                invoke(interfaceExact, v.speak(), v.speakers(), n, new int[1]);
                return v.compiledCalls() == n + n;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        int many = 100_000;
        for (int round = 0; round < 2; round++) {
            long before = allocatedBytes();
            int virtualSum = invoke(virtualExact, v.value(), v.bases(), many, new int[1]);
            int interfaceSum = invoke(interfaceExact, v.speak(), v.speakers(), many, new int[1]);
            long allocated = allocatedBytes() - before;
            assertEquals(expectedValueSum(many), virtualSum);
            assertEquals(expectedSpeakSum(many), interfaceSum);
            if (round == 1) {
                assertTrue("virtual and interface calls to compiled targets through handles that are not constants must not allocate, but " + many + " iterations allocated " +
                                allocated + " bytes", allocated < many);
            }
        }
    }

    /**
     * Compiled code calls a handle of an interface method of image classes ({@code CharSequence.length})
     * with receivers of several image classes, and virtual and interface handles with a null
     * receiver, which throws a {@link NullPointerException}.
     */
    @Test
    public void compiledCodeCallsImageInterfaceHandlesAndNullReceivers() throws Throwable {
        Virtuals v = Virtuals.bind();
        Method imageInterface = v.call("imageInterface", MethodHandle.class, CharSequence[].class, int.class, int[].class);
        MethodHandle length = MethodHandles.lookup().findVirtual(CharSequence.class, "length", MethodType.methodType(int.class));
        CharSequence[] receivers = {"abc", new StringBuilder("hello"), "", CharBuffer.wrap("xy")};
        int n = 100;
        int expected = 0;
        for (int i = 0; i < n; i++) {
            expected += receivers[i % receivers.length].length();
        }
        int expectedLength = expected;
        awaitCompiled("Calls.imageInterface", () -> {
            int[] compiled = new int[1];
            assertEquals(expectedLength, (int) invoke(imageInterface, length, receivers, n, compiled));
            return compiled[0] == 1;
        });

        Method virtualOne = v.call("virtualOne", MethodHandle.class, v.base(), int[].class);
        Method interfaceOne = v.call("interfaceOne", MethodHandle.class, v.speaker(), int[].class);
        awaitCompiled("Calls.virtualOne", () -> {
            int[] compiled = new int[1];
            assertEquals(2, (int) invoke(virtualOne, v.value(), v.bases()[1], compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Calls.interfaceOne", () -> {
            int[] compiled = new int[1];
            assertEquals(0, (int) invoke(interfaceOne, v.speak(), v.speakers()[3], compiled));
            return compiled[0] == 1;
        });
        for (int round = 0; round < 3; round++) {
            assertThrows(NullPointerException.class, () -> invokeThrowing(virtualOne, v.value(), null, new int[1]));
            assertThrows(NullPointerException.class, () -> invokeThrowing(interfaceOne, v.speak(), null, new int[1]));
            assertEquals(3, (int) invoke(virtualOne, v.value(), v.bases()[2], new int[1]));
            assertEquals(4, (int) invoke(interfaceOne, v.speak(), v.speakers()[1], new int[1]));
        }
    }

    /**
     * Image code calls virtual and interface handles of run-time classes (the host-binding path)
     * with receivers of several classes, exactly, through {@code asType} and bound handles, and
     * with a null receiver. Once the targets are compiled, the exact calls do not allocate.
     */
    @Test
    public void imageCodeCallsVirtualAndInterfaceHandlesOfRuntimeMethods() throws Throwable {
        Virtuals v = Virtuals.bind();
        MethodHandle value = v.value().asType(MethodType.methodType(int.class, Object.class));
        MethodHandle speak = v.speak().asType(MethodType.methodType(int.class, Object.class, int.class));
        MethodHandle mixed = v.mixed().asType(MethodType.methodType(long.class, Object.class, long.class, double.class, byte.class));
        MethodHandle boundSpeak = v.speak().bindTo(v.speakers()[3]);
        Object[] bases = v.bases();
        Object[] speakers = v.speakers();

        for (int round = 0; round < 3_000; round++) {
            assertEquals(round % 3 + 1, (int) value.invokeExact(bases[round % 3]));
            int i = round;
            int expected = switch (i % 4) {
                case 0 -> i + 1;
                case 1 -> 2 * i + 2;
                case 2 -> i + 3;
                default -> i - 1;
            };
            assertEquals(expected, (int) speak.invokeExact(speakers[i % 4], i));
            assertEquals(expected, (int) speak.invoke(speakers[i % 4], (Object) i));
            assertEquals((long) i * 3 + (long) (i / 2.0) + (byte) i + i % 3 + 1, (long) mixed.invokeExact(bases[i % 3], (long) i, i / 2.0, (byte) i));
            assertEquals(i - 1, (int) boundSpeak.invokeExact(i));
        }
        assertThrows(NullPointerException.class, () -> {
            int unused = (int) value.invokeExact((Object) null);
            throw new AssertionError(unused);
        });
        assertThrows(NullPointerException.class, () -> {
            int unused = (int) speak.invokeExact((Object) null, 1);
            throw new AssertionError(unused);
        });

        awaitCompiled("the value() targets", () -> {
            try {
                v.resetCompiledCalls();
                for (int i = 0; i < 99; i++) {
                    assertEquals(i % 3 + 1, (int) value.invokeExact(bases[i % 3]));
                }
                return v.compiledCalls() == 99;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
        int many = 100_000;
        for (int round = 0; round < 2; round++) {
            int acc = 0;
            long before = allocatedBytes();
            for (int i = 0; i < many; i++) {
                acc += (int) value.invokeExact(bases[i % 3]);
                acc += (int) speak.invokeExact(speakers[i % 4], i);
            }
            long allocated = allocatedBytes() - before;
            assertEquals(expectedValueSum(many) + expectedSpeakSum(many), acc);
            if (round == 1) {
                assertTrue("image code calling compiled virtual and interface run-time methods through handles must not allocate, but " + many + " iterations allocated " +
                                allocated + " bytes", allocated < many);
            }
        }
    }

    /**
     * A compiled target that a {@code linkToVirtual} hop calls directly is invalidated while it
     * runs: loading {@link Puppy} breaks the assumption of compiled {@link Animal#call} that
     * {@link Kitten} is the only animal. The target resumes in the interpreter and returns its
     * result to the compiled caller; later calls with {@link Puppy} receivers select
     * {@link Animal#call} again and get its new result.
     */
    @Test
    public void deoptimizationInVirtualHandleTargetResumesInInterpreter() throws Throwable {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> animal = Class.forName(Animal.class.getName(), true, loader);
        Object[] receivers = (Object[]) Array.newInstance(animal, 1);
        receivers[0] = loader.loadClass(Kitten.class.getName()).getConstructor().newInstance();
        MethodHandle call = MethodHandles.lookup().findVirtual(animal, "call", MethodType.methodType(int.class, int.class));
        Method animals = loader.loadClass(Calls.class.getName()).getMethod("animals", MethodHandle.class, receivers.getClass(), int.class, Runnable.class, int[].class);
        Field compiledCalls = animal.getField("compiledCalls");

        int warm = 20;
        long warmSum = (long) warm * (warm - 1) / 2 + warm;
        awaitCompiled("Calls.animals and Animal.call", () -> {
            try {
                int[] compiled = new int[2];
                compiledCalls.setInt(null, 0);
                assertEquals(warmSum, (long) invoke(animals, call, receivers, warm, null, compiled));
                return compiled[0] + compiled[1] == warm && compiledCalls.getInt(null) == warm;
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });

        Runnable loadPuppy = () -> {
            try {
                receivers[0] = loader.loadClass(Puppy.class.getName()).getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        int n = 1000;
        int[] compiled = new int[2];
        compiledCalls.setInt(null, 0);
        long sum = invoke(animals, call, receivers, n, loadPuppy, compiled);
        /*
         * The hook runs inside call(n / 2) on the kitten, which then makes the kitten sound: n / 2 +
         * 1 kitten sounds, then n / 2 - 1 puppy sounds.
         */
        long expected = (long) n * (n - 1) / 2 + (n / 2 + 1) * 1L + (n / 2 - 1) * 2L;
        assertEquals("calls after the hook must make the puppy sound", expected, sum);
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        int compiledTargetCalls = compiledCalls.getInt(null);
        assertTrue("calls up to the hook must run the compiled target, but only " + compiledTargetCalls + " did", compiledTargetCalls >= n / 2 + 1);
    }
}
