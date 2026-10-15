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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
 * Accesses through constant {@link VarHandle}s in code that Ristretto compiled from bytecode loaded
 * at run time (elide-dev/komodo#127).
 * <p>
 * The interpreter links such an access to a guard, {@code VarHandleGuards.guard_L_I} for example,
 * which reaches the handle's access method through {@code MethodHandle.linkToStatic}: in image
 * code, that call goes through the interpreter's call path and allocates on every access.
 * Ristretto replaces the guard of a constant handle with a call to the access method, and inlines
 * it. The tests check that compiled code gives the interpreter's results and exceptions, that it no
 * longer allocates, and that it deoptimizes correctly. They run like
 * {@link RistrettoDeoptimizationTest}, whose {@code TestFeature} registers the class files of
 * {@link #RUNTIME_CLASSES}.
 */
public class RistrettoVarHandleTest {

    /**
     * The handles of {@link Access}, in the order of its fields, bound to the run-time
     * {@link Cell} class by {@link #bindVarHandles} in image code.
     */
    public static VarHandle[] varHandles;

    /**
     * Called by {@code RistrettoDeoptimizationTest.TestFeature} before analysis. The interpreter
     * runs the accesses through the guards and the access methods, which therefore must be
     * compiled on their own. JNI access does that, as with
     * {@code -H:Preserve=package=java.lang.invoke}.
     */
    static void beforeAnalysis() {
        try {
            RuntimeJNIAccess.register(Class.forName("java.lang.invoke.VarHandleGuards").getDeclaredMethods());
            for (String kind : new String[]{"Booleans", "Bytes", "Shorts", "Chars", "Ints", "Longs", "Floats", "Doubles", "References"}) {
                for (Class<?> handleClass : Class.forName("java.lang.invoke.VarHandle" + kind).getDeclaredClasses()) {
                    RuntimeJNIAccess.register(handleClass.getDeclaredMethods());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The fields that {@link Access} reaches through var handles. */
    public static final class Cell {
        public static int counter;

        public volatile int i;
        public int j;
        public volatile long l;
        public volatile Object ref;
        public boolean flag;
        public byte b;
        public short s;
        public char c;
        public float f;
        public double d;
        public final int fixed;

        public Cell() {
            fixed = 5;
        }

        /** Combines the values of all fields. */
        public static long digest(Cell cell, int[] ints) {
            long digest = cell.i;
            digest = digest * 31 + cell.j;
            digest = digest * 31 + cell.l;
            digest = digest * 31 + (cell.ref == cell ? 1 : cell.ref == ints ? 2 : cell.ref == null ? 3 : 4);
            digest = digest * 31 + (cell.flag ? 1 : 0);
            digest = digest * 31 + cell.b;
            digest = digest * 31 + cell.s;
            digest = digest * 31 + cell.c;
            digest = digest * 31 + (long) (cell.f * 4);
            digest = digest * 31 + (long) (cell.d * 4);
            digest = digest * 31 + counter;
            for (int value : ints) {
                digest = digest * 31 + value;
            }
            return digest;
        }
    }

    /** Accesses {@link Cell} through constant var handles. */
    public static final class Access {
        static final VarHandle I;
        static final VarHandle J;
        static final VarHandle L;
        static final VarHandle REF;
        static final VarHandle FLAG;
        static final VarHandle B;
        static final VarHandle S;
        static final VarHandle C;
        static final VarHandle F;
        static final VarHandle D;
        static final VarHandle FIXED;
        static final VarHandle COUNTER;
        static final VarHandle INTS;
        static final VarHandle I_EXACT;

        static {
            VarHandle[] h = RistrettoVarHandleTest.varHandles;
            I = h[0];
            J = h[1];
            L = h[2];
            REF = h[3];
            FLAG = h[4];
            B = h[5];
            S = h[6];
            C = h[7];
            F = h[8];
            D = h[9];
            FIXED = h[10];
            COUNTER = h[11];
            INTS = h[12];
            I_EXACT = h[13];
        }

        /**
         * Reads {@code cell.i} and {@code cell.l} with several access modes {@code n} times and
         * counts the compiled iterations in {@code compiled[0]}.
         */
        public static long read(Cell cell, int n, int[] compiled) {
            long sum = 0;
            for (int k = 0; k < n; k++) {
                sum += (int) I.getVolatile(cell);
                sum += (int) I.getAcquire(cell);
                sum += (int) I.getOpaque(cell);
                sum += (int) I.get(cell);
                sum += (long) L.getVolatile(cell);
                sum += (int) I_EXACT.getVolatile(cell);
                compiled[0] += Probe.compiled();
            }
            return sum;
        }

        /**
         * Updates every field of {@code cell} and the elements of {@code ints} (4 of them) with
         * many access modes {@code n} times, and returns a checksum of the values read. Counts the
         * compiled iterations in {@code compiled[0]}.
         */
        public static long update(Cell cell, int[] ints, int n, int[] compiled) {
            long sum = 0;
            for (int k = 0; k < n; k++) {
                I.setVolatile(cell, k);
                I.setRelease(cell, (int) I.getAcquire(cell) + 1);
                if (!I.compareAndSet(cell, k + 1, k + 2)) {
                    throw new AssertionError("compareAndSet failed");
                }
                sum += (int) I.compareAndExchange(cell, -1, 0);
                sum += (int) I.getAndAdd(cell, 3);
                I.getAndBitwiseXor(cell, 1);
                J.set(cell, (int) J.get(cell) + k);
                sum += (long) L.getAndAdd(cell, 2L);
                L.setOpaque(cell, (long) L.getOpaque(cell) * 3);
                REF.setVolatile(cell, (Object) ints);
                Object previous = REF.compareAndExchange(cell, (Object) ints, (Object) cell);
                sum += previous == ints ? 1 : 0;
                sum += REF.getAndSet(cell, (Object) null) == cell ? 1 : 0;
                FLAG.setVolatile(cell, (k & 1) == 0);
                sum += (boolean) FLAG.getVolatile(cell) ? 1 : 0;
                sum += (boolean) FLAG.compareAndSet(cell, true, false) ? 1 : 0;
                B.set(cell, (byte) k);
                sum += (byte) B.getAcquire(cell);
                sum += (short) S.getAndAdd(cell, (short) 7);
                C.setOpaque(cell, (char) ('a' + (k & 7)));
                sum += (char) C.getOpaque(cell);
                F.setVolatile(cell, 0.5f * k);
                sum += (long) (float) F.getVolatile(cell);
                sum += (long) (double) D.getAndAdd(cell, 1.25);
                sum += (int) COUNTER.getAndAdd(1);
                COUNTER.setRelease((int) COUNTER.getAcquire() + 1);
                INTS.setVolatile(ints, k & 3, k);
                sum += (int) INTS.getAndAdd(ints, (k + 1) & 3, 1);
                sum += (boolean) INTS.compareAndSet(ints, (k + 2) & 3, -1, 0) ? 1 : 0;
                sum += (int) FIXED.get(cell);
                compiled[0] += Probe.compiled();
            }
            return sum;
        }

        /** Reads {@code ((Cell) o).i}: a ClassCastException or NullPointerException otherwise. */
        public static int readOf(Object o, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (int) I.getVolatile(o);
        }

        /** Reads {@code ints[index]}: an ArrayIndexOutOfBoundsException when out of bounds. */
        public static int element(int[] ints, int index, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (int) INTS.getVolatile(ints, index);
        }

        /** Sets the final field: an UnsupportedOperationException. */
        public static void setFixed(Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            FIXED.set(cell, 1);
        }

        /** Adds to a reference field: an UnsupportedOperationException. */
        public static Object addToReference(Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            return REF.getAndAdd(cell, (Object) cell);
        }

        /** Reads {@code cell.i} as long from an exact handle: a WrongMethodTypeException. */
        public static long readExactAsLong(Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (long) I_EXACT.getVolatile(cell);
        }

        /** Reads {@code cell.i} as long: the handle is adapted with {@code asType}. */
        public static long readAsLong(Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (long) I.getVolatile(cell);
        }

        /** Reads {@code cell.ref} as String: a ClassCastException when it is not one. */
        public static String readAsString(Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (String) REF.getVolatile(cell);
        }

        /** Reads an int field of {@code cell} through a handle that is not constant. */
        public static int readThrough(VarHandle handle, Cell cell, int[] compiled) {
            compiled[0] += Probe.compiled();
            return (int) handle.getVolatile(cell);
        }

        /**
         * Sums {@code cell.i * holder.shape.value()} over {@code n} iterations and runs {@code hook}
         * halfway. {@code compiled[0]} and {@code compiled[1]} count the compiled iterations before
         * and after the hook.
         */
        public static long lazy(Cell cell, Holder holder, int n, Runnable hook, int[] compiled) {
            long sum = 0;
            for (int k = 0; k < n; k++) {
                if (k == n / 2) {
                    hook.run();
                }
                sum += (int) I.getAcquire(cell) * holder.shape.value();
                I.setRelease(cell, (int) I.getVolatile(cell) + 1);
                compiled[k < n / 2 ? 0 : 1] += Probe.compiled();
            }
            return sum;
        }
    }

    static final List<Class<?>> RUNTIME_CLASSES = List.of(Cell.class, Access.class, Shape.class, One.class, Two.class, Holder.class);

    @Before
    public void requireRistretto() {
        Assume.assumeTrue("Native Image only", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Run with -D" + RistrettoDeoptimizationTest.REQUIRE_RISTRETTO_PROPERTY + "=true in an image with Ristretto",
                        Boolean.getBoolean(RistrettoDeoptimizationTest.REQUIRE_RISTRETTO_PROPERTY));
    }

    /**
     * Binds the handles of {@link Access} to the run-time {@link Cell} class of {@code loader}
     * and returns its run-time {@link Access} class, whose initializer picks them up.
     */
    private static Class<?> bindVarHandles(RuntimeClassLoader loader) throws ReflectiveOperationException {
        Class<?> cellClass = Class.forName(Cell.class.getName(), true, loader);
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        VarHandle i = lookup.findVarHandle(cellClass, "i", int.class);
        varHandles = new VarHandle[]{
                        i,
                        lookup.findVarHandle(cellClass, "j", int.class),
                        lookup.findVarHandle(cellClass, "l", long.class),
                        lookup.findVarHandle(cellClass, "ref", Object.class),
                        lookup.findVarHandle(cellClass, "flag", boolean.class),
                        lookup.findVarHandle(cellClass, "b", byte.class),
                        lookup.findVarHandle(cellClass, "s", short.class),
                        lookup.findVarHandle(cellClass, "c", char.class),
                        lookup.findVarHandle(cellClass, "f", float.class),
                        lookup.findVarHandle(cellClass, "d", double.class),
                        lookup.findVarHandle(cellClass, "fixed", int.class),
                        lookup.findStaticVarHandle(cellClass, "counter", int.class),
                        MethodHandles.arrayElementVarHandle(int[].class),
                        i.withInvokeExactBehavior()};
        return loader.loadClass(Access.class.getName());
    }

    private static Object newCell(Class<?> cellClass, int i) throws ReflectiveOperationException {
        Object cell = cellClass.getConstructor().newInstance();
        cellClass.getField("i").setInt(cell, i);
        cellClass.getField("l").setLong(cell, 3L * i);
        return cell;
    }

    /**
     * Compiled code reads fields through constant var handles, and does not allocate: the guards'
     * {@code linkToStatic} calls, which box their arguments, are gone.
     */
    @Test
    public void constantVarHandleReadsCompileToMemoryAccesses() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> accessClass = bindVarHandles(loader);
        Class<?> cellClass = loader.loadClass(Cell.class.getName());
        Method read = accessClass.getMethod("read", cellClass, int.class, int[].class);
        Object cell = newCell(cellClass, 7);

        int n = 100;
        /* 5 reads of i (7) and one of l (21) per iteration. */
        long expected = 56L * n;
        awaitCompiled("Access.read", () -> {
            int[] compiled = new int[1];
            assertEquals(expected, (long) invoke(read, cell, n, compiled));
            return compiled[0] == n;
        });

        int many = 100_000;
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int[] compiled = new int[1];
        long before = threads.getCurrentThreadAllocatedBytes();
        long sum = invoke(read, cell, many, compiled);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(56L * many, sum);
        assertEquals("all iterations must run compiled", many, compiled[0]);
        assertTrue("compiled var handle reads must not allocate, but " + many + " iterations allocated " + allocated + " bytes", allocated < many);
    }

    /**
     * Compiled code updates fields of all kinds, a static field and array elements through constant
     * var handles with many access modes, with the interpreter's results.
     */
    @Test
    public void constantVarHandleUpdatesMatchInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> accessClass = bindVarHandles(loader);
        Class<?> cellClass = loader.loadClass(Cell.class.getName());
        Method update = accessClass.getMethod("update", cellClass, int[].class, int.class, int[].class);
        Method digest = cellClass.getMethod("digest", cellClass, int[].class);

        int n = 64;
        /* The first call runs in the interpreter, through the guards. */
        cellClass.getField("counter").setInt(null, 0);
        Object referenceCell = newCell(cellClass, 0);
        int[] referenceInts = new int[4];
        int[] interpreted = new int[1];
        long expectedSum = invoke(update, referenceCell, referenceInts, n, interpreted);
        long expectedDigest = invoke(digest, referenceCell, referenceInts);
        assertEquals("the first call must run in the interpreter", 0, interpreted[0]);

        awaitCompiled("Access.update", () -> {
            try {
                cellClass.getField("counter").setInt(null, 0);
                Object cell = newCell(cellClass, 0);
                int[] ints = new int[4];
                int[] compiled = new int[1];
                assertEquals(expectedSum, (long) invoke(update, cell, ints, n, compiled));
                assertEquals(expectedDigest, (long) invoke(digest, cell, ints));
                return compiled[0] == n;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    /**
     * Compiled code throws the exceptions of the interpreter: a wrong or null receiver, an index
     * out of bounds, an unsupported access mode, and a call of the wrong type to an exact handle.
     * Accesses that the guards adapt ({@code asType}, a cast of the result) keep their semantics.
     */
    @Test
    public void constantVarHandleAccessesKeepTheirExceptions() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> accessClass = bindVarHandles(loader);
        Class<?> cellClass = loader.loadClass(Cell.class.getName());
        Object cell = newCell(cellClass, 9);
        Method readOf = accessClass.getMethod("readOf", Object.class, int[].class);
        Method element = accessClass.getMethod("element", int[].class, int.class, int[].class);
        Method setFixed = accessClass.getMethod("setFixed", cellClass, int[].class);
        Method addToReference = accessClass.getMethod("addToReference", cellClass, int[].class);
        Method readExactAsLong = accessClass.getMethod("readExactAsLong", cellClass, int[].class);
        Method readAsLong = accessClass.getMethod("readAsLong", cellClass, int[].class);
        Method readAsString = accessClass.getMethod("readAsString", cellClass, int[].class);

        awaitCompiled("Access.readOf", () -> {
            int[] compiled = new int[1];
            assertEquals(9, (int) invoke(readOf, cell, compiled));
            return compiled[0] == 1;
        });
        assertThrows(ClassCastException.class, () -> invokeThrowing(readOf, new Object(), new int[1]));
        assertThrows(NullPointerException.class, () -> invokeThrowing(readOf, null, new int[1]));
        assertEquals(9, (int) invoke(readOf, cell, new int[1]));

        int[] ints = {1, 2, 3, 4};
        awaitCompiled("Access.element", () -> {
            int[] compiled = new int[1];
            assertEquals(4, (int) invoke(element, ints, 3, compiled));
            return compiled[0] == 1;
        });
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> invokeThrowing(element, ints, 4, new int[1]));
        assertThrows(NullPointerException.class, () -> invokeThrowing(element, null, 0, new int[1]));

        awaitCompiled("Access.setFixed", () -> {
            int[] compiled = new int[1];
            assertThrows(UnsupportedOperationException.class, () -> invokeThrowing(setFixed, cell, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Access.addToReference", () -> {
            int[] compiled = new int[1];
            assertThrows(UnsupportedOperationException.class, () -> invokeThrowing(addToReference, cell, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Access.readExactAsLong", () -> {
            int[] compiled = new int[1];
            assertThrows(WrongMethodTypeException.class, () -> invokeThrowing(readExactAsLong, cell, compiled));
            return compiled[0] == 1;
        });
        awaitCompiled("Access.readAsLong", () -> {
            int[] compiled = new int[1];
            assertEquals(9L, (long) invoke(readAsLong, cell, compiled));
            return compiled[0] == 1;
        });

        cellClass.getField("ref").set(cell, "nine");
        awaitCompiled("Access.readAsString", () -> {
            int[] compiled = new int[1];
            assertEquals("nine", invoke(readAsString, cell, compiled));
            return compiled[0] == 1;
        });
        cellClass.getField("ref").set(cell, cell);
        assertThrows(ClassCastException.class, () -> invokeThrowing(readAsString, cell, new int[1]));
    }

    /** Compiled code accesses fields through var handles that are not constants. */
    @Test
    public void nonConstantVarHandleAccessesKeepTheirSemantics() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> accessClass = bindVarHandles(loader);
        Class<?> cellClass = loader.loadClass(Cell.class.getName());
        Object cell = newCell(cellClass, 11);
        cellClass.getField("j").setInt(cell, 13);
        Method readThrough = accessClass.getMethod("readThrough", VarHandle.class, cellClass, int[].class);
        VarHandle i = varHandles[0];
        VarHandle j = varHandles[1];

        int[] round = new int[1];
        awaitCompiled("Access.readThrough", () -> {
            int[] compiled = new int[1];
            boolean even = (round[0]++ & 1) == 0;
            assertEquals(even ? 11 : 13, (int) invoke(readThrough, even ? i : j, cell, compiled));
            return compiled[0] == 1;
        });
        assertThrows(NullPointerException.class, () -> invokeThrowing(readThrough, null, cell, new int[1]));
        /* The static field handle has no receiver coordinate. */
        assertThrows(WrongMethodTypeException.class, () -> invokeThrowing(readThrough, varHandles[11], cell, new int[1]));
    }

    /**
     * Loading a second {@link Shape} invalidates compiled code that accesses a field through a
     * constant var handle, while its frame is active below the hook. The frame resumes in the
     * interpreter, which continues the accesses through the guards.
     */
    @Test
    public void deoptimizationWithConstantVarHandleAccessesResumesInInterpreter() throws Exception {
        RuntimeClassLoader loader = new RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> accessClass = bindVarHandles(loader);
        Class<?> cellClass = loader.loadClass(Cell.class.getName());
        Class<?> holderClass = loader.loadClass(Holder.class.getName());
        Object holder = holderClass.getConstructor().newInstance();
        holderClass.getField("shape").set(holder, loader.loadClass(One.class.getName()).getConstructor().newInstance());
        Method lazy = accessClass.getMethod("lazy", cellClass, holderClass, int.class, Runnable.class, int[].class);

        Runnable noHook = () -> {
        };
        awaitCompiled("Access.lazy", () -> {
            try {
                int[] compiled = new int[2];
                long sum = invoke(lazy, newCell(cellClass, 0), holder, 10, noHook, compiled);
                assertEquals(45, sum);
                return compiled[0] == 5 && compiled[1] == 5;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
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
        Object cell = newCell(cellClass, 0);
        long sum = invoke(lazy, cell, holder, n, loadSecondShape, compiled);
        /* Iteration k adds k, times 2 after the hook. */
        long expected = (long) (n / 2) * (n / 2 - 1) / 2 + 2L * ((long) n * (n - 1) / 2 - (long) (n / 2) * (n / 2 - 1) / 2);
        assertEquals("iterations after the hook must call Two.value()", expected, sum);
        assertEquals("every iteration must update the field", n, cellClass.getField("i").getInt(cell));
        assertEquals("all iterations before the hook must run compiled", n / 2, compiled[0]);
        assertEquals("iterations after the hook must run in the interpreter", 0, compiled[1]);
    }
}
