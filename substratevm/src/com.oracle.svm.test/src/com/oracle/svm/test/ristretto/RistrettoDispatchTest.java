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
import static org.junit.Assert.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.graalvm.nativeimage.hosted.RuntimeJNIAccess;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.meta.KnownOffsets;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.crema.CremaSupport;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType;
import com.oracle.svm.interpreter.ristretto.meta.RistrettoMethod;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.test.NativeImageBuildArgs;

/** Actual hub-slot checks: an assembly tail jump can bypass Java fast-path test hooks. */
@NativeImageBuildArgs("--add-exports=org.graalvm.nativeimage.guest.staging/com.oracle.svm.guest.staging.core.graal=ALL-UNNAMED")
public class RistrettoDispatchTest {
    static final List<Class<?>> RUNTIME_CLASSES = List.of(Target.class);

    static void beforeAnalysis() {
        RuntimeJNIAccess.register(Base.class, Operation.class);
        RuntimeJNIAccess.register(Base.class.getDeclaredConstructors());
        RuntimeJNIAccess.register(Base.class.getDeclaredMethods());
        RuntimeJNIAccess.register(Operation.class.getDeclaredMethods());
    }

    public interface Operation {
        int value(int input);
    }

    public static class Base {
        public Base() {
        }

        public int value(int input) {
            return input;
        }
    }

    /** Only a resource in the image; the test defines a fresh, runtime-loaded copy. */
    public static class Target extends Base implements Operation {
        public int bias = 7;

        @Override
        public int value(int input) {
            return input + bias;
        }
    }

    @Before
    public void requireRistretto() {
        new RistrettoDeoptimizationTest().requireRistretto();
    }

    @NeverInline("Keep the AOT virtual dispatch through the runtime receiver's hub.")
    private static int callBase(Base receiver, int input) {
        return receiver.value(input);
    }

    @NeverInline("Keep the AOT interface dispatch through the runtime receiver's hub.")
    private static int callInterface(Operation receiver, int input) {
        return receiver.value(input);
    }

    private static final class FanoutLoader extends ClassLoader {
        FanoutLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(int index) {
            String name = "com.oracle.svm.test.ristretto.generated.DispatchReceiver" + index;
            ClassDesc target = ClassDesc.of(Target.class.getName());
            byte[] bytes = ClassFile.of().build(ClassDesc.of(name), builder -> builder
                            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                            .withSuperclass(target)
                            .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC,
                                            code -> code.aload(0).invokespecial(target, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_()));
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private record Fixture(Base warm, List<Base> receivers, InterpreterResolvedJavaMethod method) {
    }

    private static Fixture fixture(int fanout) throws Exception {
        var loader = new RistrettoDeoptimizationTest.RuntimeClassLoader(RUNTIME_CLASSES);
        Class<?> target = loader.loadClass(Target.class.getName());
        Base warm = (Base) target.getConstructor().newInstance();
        var fanoutLoader = new FanoutLoader(loader);
        List<Base> receivers = new ArrayList<>();
        for (int i = 0; i < fanout; i++) {
            receivers.add((Base) fanoutLoader.define(i).getConstructor().newInstance());
        }
        var method = (InterpreterResolvedJavaMethod) CremaSupport.singleton().lookupMethodForRuntimeClass(target, "value", "(I)I");
        assertTrue(method != null);
        awaitEntry(warm, method);
        return new Fixture(warm, receivers, method);
    }

    private static void awaitEntry(Base receiver, InterpreterResolvedJavaMethod method) {
        long deadline = System.nanoTime() + RistrettoDeoptimizationTest.COMPILATION_TIMEOUT_NANOS;
        while (method.getDirectCallEntry() == 0) {
            assertTrue("target did not compile", System.nanoTime() < deadline);
            assertEquals(18, callBase(receiver, 11));
            Thread.yield();
        }
    }

    private static int[] slots(Base receiver, InterpreterResolvedJavaMethod method) {
        var type = (InterpreterResolvedObjectType) DynamicHub.fromClass(receiver.getClass()).getInterpreterType();
        List<Integer> indices = new ArrayList<>();
        var table = type.getVtable();
        for (int i = 0; i < table.length; i++) {
            if (table[i] == method) {
                indices.add(i);
            }
        }
        assertTrue("expected virtual and interface slots", indices.size() >= 2);
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    private static long slotValue(Base receiver, int index) {
        var offsets = KnownOffsets.singleton();
        return readSlot(receiver, index, offsets.getVTableBaseOffset(), offsets.getVTableEntrySize());
    }

    @Uninterruptible(reason = "Read the raw hub slot without allowing the hub to move.")
    private static long readSlot(Base receiver, int index, int baseOffset, int entrySize) {
        Pointer slot = Word.objectToUntrackedPointer(DynamicHub.fromClass(receiver.getClass()))
                        .add(baseOffset).add(index * entrySize);
        return slot.readLong(0);
    }

    @Uninterruptible(reason = "Snapshot the published code address; the test never calls it.")
    private static long encodedEntry(InterpreterResolvedJavaMethod method) {
        long entry = method.getDirectCallEntry();
        return SubstrateOptions.useRelativeCodePointers() ? entry - KnownIntrinsics.codeBase().rawValue() : entry;
    }

    private static List<long[]> snapshot(Fixture fixture) {
        List<long[]> result = new ArrayList<>();
        for (Base receiver : fixture.receivers()) {
            int[] indices = slots(receiver, fixture.method());
            long[] values = new long[indices.length];
            for (int i = 0; i < indices.length; i++) {
                values[i] = slotValue(receiver, indices[i]);
            }
            result.add(values);
        }
        return result;
    }

    private static void assertRestored(Fixture fixture, List<long[]> before) {
        for (int r = 0; r < fixture.receivers().size(); r++) {
            Base receiver = fixture.receivers().get(r);
            int[] indices = slots(receiver, fixture.method());
            for (int i = 0; i < indices.length; i++) {
                assertEquals("retired entry remains reachable", before.get(r)[i], slotValue(receiver, indices[i]));
            }
        }
    }

    private static void invalidate(InterpreterResolvedJavaMethod method) {
        var code = ((RistrettoMethod) method.getRistrettoMethod()).installedCode;
        assertTrue(code != null);
        code.reprofile();
        code.invalidate();
        assertEquals(0, method.getDirectCallEntry());
    }

    @Test
    public void firstCompiledCallPatchesVirtualAndInterfaceSlots() throws Exception {
        Fixture fixture = fixture(2);
        List<long[]> before = snapshot(fixture);
        for (int round = 0; round < 2; round++) {
            for (Base receiver : fixture.receivers()) {
                assertEquals(18, callBase(receiver, 11));
                assertEquals(20, callInterface((Operation) receiver, 13));
                for (int index : slots(receiver, fixture.method())) {
                    assertEquals("compiled dispatch did not patch its hub slot", encodedEntry(fixture.method()), slotValue(receiver, index));
                }
            }
            invalidate(fixture.method());
            assertRestored(fixture, before);
            if (round == 0) {
                awaitEntry(fixture.warm(), fixture.method());
            }
        }
    }

    @Test
    public void concurrentFanoutRetainsFallbackAndResetsEveryPatchedSlot() throws Exception {
        // Each hub has at least two slots, exceeding the current sixteen-record capacity.
        Fixture fixture = fixture(32);
        List<long[]> before = snapshot(fixture);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (Base receiver : fixture.receivers()) {
                        assertEquals(18, callBase(receiver, 11));
                        assertEquals(20, callInterface((Operation) receiver, 13));
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent dispatch failed", failure.get());
        }
        int patched = 0;
        int fallback = 0;
        for (Base receiver : fixture.receivers()) {
            for (int index : slots(receiver, fixture.method())) {
                if (slotValue(receiver, index) == encodedEntry(fixture.method())) {
                    patched++;
                } else {
                    fallback++;
                }
            }
        }
        assertTrue("no receiver slot was patched", patched > 0);
        assertTrue("fanout did not exercise bounded fallback", fallback > 0);
        invalidate(fixture.method());
        assertRestored(fixture, before);
    }
}
