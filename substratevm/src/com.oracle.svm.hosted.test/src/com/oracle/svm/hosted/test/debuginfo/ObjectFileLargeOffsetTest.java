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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.objectfile.BasicProgbitsSectionImpl;
import com.oracle.objectfile.LayoutDecisionMap;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.elf.ELFMachine;
import com.oracle.objectfile.elf.ELFObjectFile;
import com.oracle.objectfile.macho.MachOCpuType;
import com.oracle.objectfile.macho.MachOObjectFile;

public class ObjectFileLargeOffsetTest {
    private static final byte[] PAYLOAD = new byte[]{0x12, 0x34, 0x56, 0x78};

    private static ELFObjectFile objectWithGap(long offset) {
        ELFObjectFile object = new ELFObjectFile(4096, ELFMachine.X86_64);
        object.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        object.newUserDefinedSection(null, ".debug_large", 8, new BasicProgbitsSectionImpl(PAYLOAD) {
            @Override
            public boolean isLoadable() {
                return false;
            }

            @Override
            public long getOrDecideOffset(Map<ObjectFile.Element, LayoutDecisionMap> alreadyDecided, long offsetHint) {
                return offset;
            }
        });
        return object;
    }

    @Test
    public void testElfOffsetBeyondSignedInt() throws Exception {
        checkSparseObject(0x8000_0020L);
    }

    @Test
    public void testElfOffsetBeyondUnsignedInt() throws Exception {
        checkSparseObject(0x1_0000_0020L);
    }

    private static void checkSparseObject(long offset) throws Exception {
        Path directory = Files.createTempDirectory("objectfile-large-offset");
        Path output = directory.resolve("large.o");
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            objectWithGap(offset).write(channel);
            Assert.assertTrue(channel.size() >= offset + PAYLOAD.length);
            ByteBuffer header = read(channel, 0, 64);
            Assert.assertEquals("Expected ELF64", 2, header.get(4));
            long sectionTable = header.getLong(40);
            int stride = Short.toUnsignedInt(header.getShort(58));
            int count = Short.toUnsignedInt(header.getShort(60));
            int found = 0;
            for (int i = 0; i < count; i++) {
                ByteBuffer section = read(channel, sectionTable + (long) i * stride, stride);
                if (section.getLong(24) == offset) {
                    Assert.assertEquals(PAYLOAD.length, section.getLong(32));
                    Assert.assertArrayEquals(PAYLOAD, read(channel, offset, PAYLOAD.length).array());
                    found++;
                }
            }
            Assert.assertEquals("The ELF section header must retain the complete 64-bit file offset", 1, found);
        } finally {
            Files.deleteIfExists(output);
            Files.delete(directory);
        }
    }

    private static MachOObjectFile machObjectWithGap(long offset) {
        MachOObjectFile object = new MachOObjectFile(4096, MachOCpuType.ARM64);
        ObjectFile.Segment segment = object.getSegments().iterator().next();
        object.newUserDefinedSection(segment, "__debug_large", 8, new BasicProgbitsSectionImpl(PAYLOAD) {
            @Override
            public boolean isLoadable() {
                return false;
            }

            @Override
            public long getOrDecideOffset(Map<ObjectFile.Element, LayoutDecisionMap> alreadyDecided, long offsetHint) {
                return offset;
            }
        });
        return object;
    }

    @Test
    public void testMachOffsetBeyondSignedInt() throws Exception {
        long offset = 0x8000_0020L;
        Path directory = Files.createTempDirectory("macho-large-offset");
        Path output = directory.resolve("large.o");
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            machObjectWithGap(offset).write(channel);
            ByteBuffer header = read(channel, 0, 32);
            Assert.assertEquals(0xfeedfacf, header.getInt(0));
            int commands = header.getInt(16);
            long commandOffset = 32;
            int found = 0;
            for (int i = 0; i < commands; i++) {
                ByteBuffer command = read(channel, commandOffset, 8);
                if (command.getInt(0) == 0x19) { // LC_SEGMENT_64
                    ByteBuffer segment = read(channel, commandOffset, 72);
                    for (int j = 0; j < segment.getInt(64); j++) {
                        ByteBuffer section = read(channel, commandOffset + 72 + (long) j * 80, 80);
                        if (Integer.toUnsignedLong(section.getInt(48)) == offset) {
                            Assert.assertEquals(PAYLOAD.length, section.getLong(40));
                            Assert.assertArrayEquals(PAYLOAD, read(channel, offset, PAYLOAD.length).array());
                            found++;
                        }
                    }
                }
                commandOffset += command.getInt(4);
            }
            Assert.assertEquals("The Mach-O section offset must retain its unsigned high bit", 1, found);
        } finally {
            Files.deleteIfExists(output);
            Files.delete(directory);
        }
    }

    @Test
    public void testMachOffsetBeyondUnsignedIntIsRejected() {
        Assert.assertThrows(IllegalArgumentException.class, () -> machObjectWithGap(0x1_0000_0020L).bake(new ArrayList<>()));
    }

    @Test
    public void testUnsignedOffsetFieldBounds() {
        Assert.assertEquals(Integer.MIN_VALUE, ObjectFile.unsignedFileOffset(0x8000_0000L));
        Assert.assertEquals(-1, ObjectFile.unsignedFileOffset(0xffff_ffffL));
        Assert.assertThrows(IllegalArgumentException.class, () -> ObjectFile.unsignedFileOffset(-1));
        Assert.assertThrows(IllegalArgumentException.class, () -> ObjectFile.unsignedFileOffset(0x1_0000_0000L));
    }

    @Test
    public void testSmallFileMatchesBufferWriter() throws Exception {
        ELFObjectFile buffered = objectWithGap(4096);
        ArrayList<ObjectFile.Element> elements = new ArrayList<>();
        ByteBuffer expected = ByteBuffer.allocate(Math.toIntExact(buffered.bake(elements)));
        buffered.writeBuffer(elements, expected);
        Path output = Files.createTempFile("objectfile-small-offset", ".o");
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            objectWithGap(4096).write(channel);
            Assert.assertArrayEquals(expected.array(), Files.readAllBytes(output));
        } finally {
            Files.delete(output);
        }
    }

    private static ByteBuffer read(FileChannel channel, long offset, int size) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset + buffer.position());
            Assert.assertTrue("Unexpected end of object file", count > 0);
        }
        return buffer.flip();
    }
}
