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
package com.oracle.svm.test.foreign;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.svm.test.NativeImageBuildArgs;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Tests Vector API accesses to memory segments of shared arenas, and in particular closing a shared
 * arena while other threads access its memory with the Vector API.
 * <p>
 * {@link IntVector#SPECIES_512} is used in addition to the preferred species: unless the image is
 * built for a target with 512-bit vectors, the Vector API operations on it cannot be expanded to
 * SIMD code and fall back to the Java implementation of {@code jdk.incubator.vector}.
 */
@NativeImageBuildArgs({
                "--add-modules=jdk.incubator.vector",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+VectorAPISupport",
                "-H:+SharedArenaSupport",
                "-H:-UnlockExperimentalVMOptions"
})
public class SharedArenaVectorAPITest {
    private static final ByteOrder ORDER = ByteOrder.nativeOrder();
    private static final List<VectorSpecies<Integer>> INT_SPECIES = List.of(IntVector.SPECIES_PREFERRED, IntVector.SPECIES_128, IntVector.SPECIES_512);

    private static final int CLOSE_ROUNDS = 20;
    private static final long MIN_ITERATIONS_BEFORE_CLOSE = 2_000;
    private static final long SEGMENT_BYTES = 64 * 1024;

    @Test
    public void testSharedArenaValues() {
        try (Arena arena = Arena.ofShared()) {
            checkValues(arena);
        }
    }

    @Test
    public void testConfinedArenaValues() {
        try (Arena arena = Arena.ofConfined()) {
            checkValues(arena);
        }
    }

    @Test
    public void testAutoArenaValues() {
        checkValues(Arena.ofAuto());
    }

    @Test
    public void testGlobalArenaValues() {
        checkValues(Arena.global());
    }

    @Test
    public void testHeapSegmentValues() {
        for (VectorSpecies<Integer> species : INT_SPECIES) {
            MemorySegment segment = MemorySegment.ofArray(new int[species.length() * 4]);
            checkIntValues(species, segment);
        }
    }

    @Test
    public void testAccessAfterSharedArenaClose() {
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(SEGMENT_BYTES, 64);
        arena.close();
        assertAccessesFailAfterClose(segment);
    }

    @Test
    public void testAccessAfterConfinedArenaClose() {
        Arena arena = Arena.ofConfined();
        MemorySegment segment = arena.allocate(SEGMENT_BYTES, 64);
        arena.close();
        assertAccessesFailAfterClose(segment);
    }

    @Test
    public void testConfinedArenaAccessFromOtherThread() throws InterruptedException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(SEGMENT_BYTES, 64);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    IntVector.fromMemorySegment(IntVector.SPECIES_PREFERRED, segment, 0, ORDER);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            thread.start();
            thread.join();
            Assert.assertTrue(String.valueOf(failure.get()), failure.get() instanceof WrongThreadException);
        }
    }

    /**
     * Closes a shared arena backing an allocated segment while other threads load and store
     * vectors from and to it.
     */
    @Test
    public void testCloseAllocatedSegmentWhileAccessing() throws Exception {
        for (int round = 0; round < CLOSE_ROUNDS; round++) {
            Arena arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(SEGMENT_BYTES, 64);
            closeWhileAccessing(arena, segment);
        }
    }

    /**
     * Closes a shared arena backing a mapped file segment while other threads load and store
     * vectors from and to it. Closing the arena unmaps the file, so any access after the close
     * crashes instead of silently reading freed memory.
     */
    @Test
    public void testCloseMappedSegmentWhileAccessing() throws Exception {
        Path file = Files.createTempFile("shared-arena-vector-api", ".bin");
        try {
            Files.write(file, new byte[(int) SEGMENT_BYTES]);
            for (int round = 0; round < CLOSE_ROUNDS; round++) {
                Arena arena = Arena.ofShared();
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, SEGMENT_BYTES, arena);
                    closeWhileAccessing(arena, segment);
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void checkValues(Arena arena) {
        for (VectorSpecies<Integer> species : INT_SPECIES) {
            checkIntValues(species, arena.allocate((long) species.vectorByteSize() * 4, 64));
        }
        checkByteValues(arena.allocate((long) ByteVector.SPECIES_PREFERRED.vectorByteSize() * 4, 64));
        checkDoubleValues(arena.allocate((long) DoubleVector.SPECIES_PREFERRED.vectorByteSize() * 4, 64));
    }

    private static void checkIntValues(VectorSpecies<Integer> species, MemorySegment segment) {
        int length = species.length();
        long vectorBytes = species.vectorByteSize();
        for (int i = 0; i < length * 2; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i * 3 + 1);
        }

        IntVector loaded = IntVector.fromMemorySegment(species, segment, vectorBytes, ORDER);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals(species.toString(), (length + i) * 3 + 1, loaded.lane(i));
        }

        loaded.add(5).intoMemorySegment(segment, 2 * vectorBytes, ORDER);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals(species.toString(), (length + i) * 3 + 6, segment.getAtIndex(ValueLayout.JAVA_INT, 2L * length + i));
        }

        VectorMask<Integer> lowerHalf = species.indexInRange(0, Math.max(1, length / 2));
        IntVector maskedLoad = IntVector.fromMemorySegment(species, segment, 0, ORDER, lowerHalf);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals(species.toString(), lowerHalf.laneIsSet(i) ? i * 3 + 1 : 0, maskedLoad.lane(i));
        }

        IntVector.broadcast(species, -7).intoMemorySegment(segment, 3 * vectorBytes, ORDER, lowerHalf);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals(species.toString(), lowerHalf.laneIsSet(i) ? -7 : 0, segment.getAtIndex(ValueLayout.JAVA_INT, 3L * length + i));
        }

        // Masked access at the end of the segment: the lanes beyond the end must not be touched.
        int lanesInRange = Math.max(1, length / 2);
        long tailOffset = segment.byteSize() - (long) lanesInRange * Integer.BYTES;
        VectorMask<Integer> inRange = species.indexInRange(0, lanesInRange);
        IntVector.broadcast(species, 42).intoMemorySegment(segment, tailOffset, ORDER, inRange);
        IntVector tail = IntVector.fromMemorySegment(species, segment, tailOffset, ORDER, inRange);
        for (int i = 0; i < lanesInRange; i++) {
            Assert.assertEquals(species.toString(), 42, tail.lane(i));
        }
    }

    private static void checkByteValues(MemorySegment segment) {
        VectorSpecies<Byte> species = ByteVector.SPECIES_PREFERRED;
        int length = species.length();
        for (int i = 0; i < length; i++) {
            segment.set(ValueLayout.JAVA_BYTE, i, (byte) (i - 64));
        }
        ByteVector loaded = ByteVector.fromMemorySegment(species, segment, 0, ORDER);
        loaded.neg().intoMemorySegment(segment, length, ORDER);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals((byte) (i - 64), loaded.lane(i));
            Assert.assertEquals((byte) -(i - 64), segment.get(ValueLayout.JAVA_BYTE, length + i));
        }
    }

    private static void checkDoubleValues(MemorySegment segment) {
        VectorSpecies<Double> species = DoubleVector.SPECIES_PREFERRED;
        int length = species.length();
        for (int i = 0; i < length; i++) {
            segment.setAtIndex(ValueLayout.JAVA_DOUBLE, i, i * 0.5);
        }
        DoubleVector loaded = DoubleVector.fromMemorySegment(species, segment, 0, ORDER);
        loaded.mul(2).intoMemorySegment(segment, species.vectorByteSize(), ORDER);
        for (int i = 0; i < length; i++) {
            Assert.assertEquals(i * 0.5, loaded.lane(i), 0);
            Assert.assertEquals(i * 1.0, segment.getAtIndex(ValueLayout.JAVA_DOUBLE, length + i), 0);
        }
    }

    private static void assertAccessesFailAfterClose(MemorySegment segment) {
        List<Function<VectorSpecies<Integer>, Object>> accesses = List.of(
                        species -> IntVector.fromMemorySegment(species, segment, 0, ORDER),
                        species -> IntVector.fromMemorySegment(species, segment, 0, ORDER, species.indexInRange(0, 1)),
                        species -> {
                            IntVector.zero(species).intoMemorySegment(segment, 0, ORDER);
                            return null;
                        },
                        species -> {
                            IntVector.zero(species).intoMemorySegment(segment, 0, ORDER, species.indexInRange(0, 1));
                            return null;
                        });
        for (VectorSpecies<Integer> species : INT_SPECIES) {
            for (Function<VectorSpecies<Integer>, Object> access : accesses) {
                try {
                    access.apply(species);
                    Assert.fail("Expected IllegalStateException for " + species);
                } catch (IllegalStateException e) {
                    Assert.assertEquals("Already closed", e.getMessage());
                }
            }
        }
    }

    /** The kinds of Vector API accesses performed by the accessor threads. */
    private enum AccessKind {
        LOAD,
        STORE,
        MASKED_LOAD,
        MASKED_STORE
    }

    private static void closeWhileAccessing(Arena arena, MemorySegment segment) throws InterruptedException {
        int threadCount = AccessKind.values().length * INT_SPECIES.size();
        CountDownLatch started = new CountDownLatch(threadCount);
        AtomicLong iterations = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        List<AtomicReference<IllegalStateException>> closedExceptions = new ArrayList<>();
        long regionBytes = (segment.byteSize() / threadCount) & ~63L;
        int threadIndex = 0;
        for (AccessKind kind : AccessKind.values()) {
            for (VectorSpecies<Integer> species : INT_SPECIES) {
                MemorySegment region = segment.asSlice(regionBytes * threadIndex, regionBytes);
                int fill = threadIndex + 1;
                AtomicReference<IllegalStateException> closedException = new AtomicReference<>();
                closedExceptions.add(closedException);
                Thread thread = new Thread(() -> {
                    try {
                        for (long i = 0; i < region.byteSize() / Integer.BYTES; i++) {
                            region.setAtIndex(ValueLayout.JAVA_INT, i, fill);
                        }
                        started.countDown();
                        accessUntilClosed(kind, species, region, fill, iterations);
                    } catch (IllegalStateException e) {
                        closedException.set(e);
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                threads.add(thread);
                thread.start();
                threadIndex++;
            }
        }

        Assert.assertTrue(started.await(60, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (iterations.get() < MIN_ITERATIONS_BEFORE_CLOSE && failure.get() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        arena.close();

        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            Assert.assertFalse("Accessor thread did not observe the arena close", thread.isAlive());
        }
        if (failure.get() != null) {
            throw new AssertionError("Unexpected failure in accessor thread", failure.get());
        }
        for (AtomicReference<IllegalStateException> closedException : closedExceptions) {
            Assert.assertNotNull("Accessor thread did not observe the arena close", closedException.get());
            Assert.assertEquals("Already closed", closedException.get().getMessage());
        }
        Assert.assertFalse(segment.scope().isAlive());
    }

    /**
     * Performs one kind of Vector API access to {@code region} until the arena backing it is
     * closed. The loop does not allocate, so a thread is most likely stopped for the close at the
     * safepoint of the loop backedge, directly before the next access. Every lane of the region
     * holds {@code fill}, so loads check that they never observe other values.
     */
    private static void accessUntilClosed(AccessKind kind, VectorSpecies<Integer> species, MemorySegment region, int fill, AtomicLong iterations) {
        long vectorBytes = species.vectorByteSize();
        long vectorsInRegion = region.byteSize() / vectorBytes;
        VectorMask<Integer> mask = species.indexInRange(0, Math.max(1, species.length() / 2));
        IntVector value = IntVector.broadcast(species, fill);
        int expectedSum = fill * species.length();
        int expectedMaskedSum = fill * mask.trueCount();
        for (long iteration = 0;; iteration++) {
            long offset = (iteration % vectorsInRegion) * vectorBytes;
            switch (kind) {
                case LOAD -> {
                    int sum = IntVector.fromMemorySegment(species, region, offset, ORDER).reduceLanes(VectorOperators.ADD);
                    if (sum != expectedSum) {
                        throw new AssertionError("Unexpected lane sum " + sum + " at offset " + offset + ", expected " + expectedSum);
                    }
                }
                case STORE -> value.intoMemorySegment(region, offset, ORDER);
                case MASKED_LOAD -> {
                    int sum = IntVector.fromMemorySegment(species, region, offset, ORDER, mask).reduceLanes(VectorOperators.ADD);
                    if (sum != expectedMaskedSum) {
                        throw new AssertionError("Unexpected masked lane sum " + sum + " at offset " + offset + ", expected " + expectedMaskedSum);
                    }
                }
                case MASKED_STORE -> value.intoMemorySegment(region, offset, ORDER, mask);
            }
            if ((iteration & 0xff) == 0) {
                iterations.incrementAndGet();
            }
        }
    }
}
