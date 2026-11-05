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
package com.oracle.svm.hosted.test.debuginfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.objectfile.BasicProgbitsSectionImpl;
import com.oracle.objectfile.LayoutDecision;
import com.oracle.objectfile.LayoutDecisionMap;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.elf.ELFMachine;
import com.oracle.objectfile.elf.ELFObjectFile;
import com.oracle.objectfile.elf.ELFRelocationSection;

public class ObjectFileStreamedContentTest {
    @Test
    public void testContiguousSectionWritesAreBoundedAndHandleShortWrites() throws Exception {
        byte[] bytes = new byte[5 * 64 * 1024 + 27];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        ELFObjectFile object = new ELFObjectFile(4096, ELFMachine.X86_64);
        object.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        ObjectFile.Section section = object.newUserDefinedSection(null, ".debug_contiguous", 8, new BasicProgbitsSectionImpl(bytes) {
            @Override
            public boolean isLoadable() {
                return false;
            }
        });
        Path output = Files.createTempFile("objectfile-bounded-writes", ".o");
        try (FileChannel file = FileChannel.open(output, StandardOpenOption.READ, StandardOpenOption.WRITE);
                        SparseZeroChannel channel = new SparseZeroChannel(file, null, 7001)) {
            object.write(channel);
            Assert.assertTrue("Heap-buffer writes must not request section-sized native copies", channel.maximumRequest <= 64 * 1024);
            Assert.assertTrue("The fixture must exercise short writes", channel.positionedWrites > bytes.length / (64 * 1024));
            long offset = (long) object.getDecisionsTaken().get(section).getDecidedValue(LayoutDecision.Kind.OFFSET);
            ByteBuffer actual = ByteBuffer.allocate(bytes.length);
            while (actual.hasRemaining()) {
                Assert.assertTrue(channel.read(actual, offset + actual.position()) > 0);
            }
            Assert.assertArrayEquals(bytes, actual.array());
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void testSectionSizeBeyondSignedInt() throws Exception {
        long size = 0x8000_0020L;
        ByteBuffer zeroes = ByteBuffer.allocate(64 * 1024);
        ELFObjectFile object = new ELFObjectFile(4096, ELFMachine.X86_64);
        object.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        ObjectFile.Section section = object.newUserDefinedSection(null, ".debug_streamed", 8, new BasicProgbitsSectionImpl(new byte[0]) {
            @Override
            public boolean isLoadable() {
                return false;
            }

            @Override
            public long getOrDecideSize(Map<ObjectFile.Element, LayoutDecisionMap> decisions, long hint) {
                return size;
            }

            @Override
            public byte[] getOrDecideContent(Map<ObjectFile.Element, LayoutDecisionMap> decisions, byte[] hint) {
                throw new AssertionError("A large section must not allocate a contiguous byte array");
            }

            @Override
            public ObjectFile.StreamedContent getOrDecideStreamedContent(Map<ObjectFile.Element, LayoutDecisionMap> decisions) {
                return new ObjectFile.StreamedContent() {
                    @Override
                    public long size() {
                        return size;
                    }

                    @Override
                    public void write(ObjectFile.ContentSink sink) throws IOException {
                        long remaining = size - 4;
                        while (remaining != 0) {
                            zeroes.clear();
                            int count = (int) Math.min(remaining, zeroes.capacity());
                            zeroes.limit(count);
                            sink.write(zeroes);
                            Assert.assertFalse(zeroes.hasRemaining());
                            remaining -= count;
                        }
                        sink.write(ByteBuffer.wrap(new byte[]{0x12, 0x34, 0x56, 0x78}));
                    }
                };
            }
        });
        Path output = Files.createTempFile("objectfile-streamed-section", ".o");
        try (FileChannel file = FileChannel.open(output, StandardOpenOption.READ, StandardOpenOption.WRITE);
                        FileChannel channel = new SparseZeroChannel(file, zeroes)) {
            object.write(channel);
            Map<ObjectFile.Element, LayoutDecisionMap> decisions = object.getDecisionsTaken();
            Assert.assertEquals(size, decisions.get(section).getDecidedValue(LayoutDecision.Kind.SIZE));
            long offset = (long) decisions.get(section).getDecidedValue(LayoutDecision.Kind.OFFSET);
            Assert.assertTrue(channel.size() >= offset + size);
            ByteBuffer tail = ByteBuffer.allocate(4);
            Assert.assertEquals(4, channel.read(tail, offset + size - 4));
            Assert.assertArrayEquals(new byte[]{0x12, 0x34, 0x56, 0x78}, tail.array());
            ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            Assert.assertEquals(64, channel.read(header, 0));
            long sectionTable = header.getLong(40);
            int stride = Short.toUnsignedInt(header.getShort(58));
            int count = Short.toUnsignedInt(header.getShort(60));
            int found = 0;
            for (int i = 0; i < count; i++) {
                ByteBuffer entry = ByteBuffer.allocate(stride).order(ByteOrder.LITTLE_ENDIAN);
                Assert.assertEquals(stride, channel.read(entry, sectionTable + (long) i * stride));
                if (entry.getLong(24) == offset) {
                    Assert.assertEquals(size, entry.getLong(32));
                    found++;
                }
            }
            Assert.assertEquals(1, found);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    public void testLittleEndianRelocationChunksMatchLegacyBytes() throws Exception {
        checkRelocations(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void testBigEndianRelocationChunksMatchLegacyBytes() throws Exception {
        checkRelocations(ByteOrder.BIG_ENDIAN);
    }

    private static void checkRelocations(ByteOrder order) throws Exception {
        int count = 6000;
        ELFObjectFile object = new ELFObjectFile(4096, ELFMachine.X86_64);
        object.setByteOrder(order);
        BasicProgbitsSectionImpl impl = new BasicProgbitsSectionImpl(new byte[count * 8]) {
            @Override
            public boolean isLoadable() {
                return false;
            }
        };
        ObjectFile.Section section = object.newUserDefinedSection(null, ".debug_relocations", 8, impl);
        object.createDefinedSymbol("target", section, 0, 8, false, true, false);
        for (int i = 0; i < count; i++) {
            impl.markRelocationSite(i * 8, ObjectFile.RelocationKind.DIRECT_8, "target", (long) i * 13 - 44);
        }
        ArrayList<ObjectFile.Element> elements = new ArrayList<>();
        long size = object.bake(elements);
        ELFRelocationSection relocations = (ELFRelocationSection) impl.getOrCreateRelocationElement(1);
        ObjectFile.StreamedContent content = (ObjectFile.StreamedContent) object.getDecisionsTaken().get(relocations).getDecidedValue(LayoutDecision.Kind.CONTENT);
        Assert.assertEquals((long) count * 24, content.size());
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        int[] chunks = {0};
        content.write(buffer -> {
            Assert.assertTrue(buffer.remaining() <= 64 * 1024);
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            actual.write(bytes);
            chunks[0]++;
        });
        Assert.assertTrue(chunks[0] > 1);
        Assert.assertArrayEquals(relocations.getOrDecideContent(object.getDecisionsTaken(), new byte[0]), actual.toByteArray());
        ByteBuffer records = ByteBuffer.wrap(actual.toByteArray()).order(order);
        for (int i = 0; i < count; i++) {
            Assert.assertEquals((long) i * 8, records.getLong());
            Assert.assertEquals(1, records.getLong() & 0xffff_ffffL);
            Assert.assertEquals((long) i * 13 - 44, records.getLong());
        }
        ByteBuffer buffered = ByteBuffer.allocate(Math.toIntExact(size));
        object.writeBuffer(elements, buffered);
        Assert.assertEquals(size, buffered.position());
    }

    /** The fixture's one known zero buffer becomes sparse holes, keeping this boundary test small. */
    private static final class SparseZeroChannel extends FileChannel {
        private final FileChannel delegate;
        private final ByteBuffer zeroes;
        private final int maximumWrite;
        private int maximumRequest;
        private int positionedWrites;

        SparseZeroChannel(FileChannel delegate, ByteBuffer zeroes) {
            this(delegate, zeroes, Integer.MAX_VALUE);
        }

        SparseZeroChannel(FileChannel delegate, ByteBuffer zeroes, int maximumWrite) {
            this.delegate = delegate;
            this.zeroes = zeroes;
            this.maximumWrite = maximumWrite;
        }

        @Override
        public int write(ByteBuffer source, long position) throws IOException {
            maximumRequest = Math.max(maximumRequest, source.remaining());
            positionedWrites++;
            if (source == zeroes) {
                int count = source.remaining();
                source.position(source.limit());
                return count;
            }
            int limit = source.limit();
            source.limit(source.position() + Math.min(source.remaining(), maximumWrite));
            try {
                return delegate.write(source, position);
            } finally {
                source.limit(limit);
            }
        }

        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        @Override
        public long read(ByteBuffer[] targets, int offset, int length) throws IOException {
            return delegate.read(targets, offset, length);
        }

        @Override
        public int read(ByteBuffer target, long position) throws IOException {
            return delegate.read(target, position);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
            return delegate.write(sources, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long position) throws IOException {
            return delegate.position(position);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public void force(boolean metadata) throws IOException {
            delegate.force(metadata);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
            return delegate.transferFrom(source, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
