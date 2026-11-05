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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.graalvm.collections.EconomicSet;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.objectfile.BasicProgbitsSectionImpl;
import com.oracle.objectfile.BuildDependency;
import com.oracle.objectfile.LayoutDecision;
import com.oracle.objectfile.LayoutDecisionMap;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.elf.ELFMachine;
import com.oracle.objectfile.elf.dwarf.DwarfDebugInfo;
import com.oracle.objectfile.elf.dwarf.DwarfSectionImpl;
import com.oracle.objectfile.elf.dwarf.constants.DwarfSectionName;
import com.oracle.objectfile.elf.dwarf.constants.DwarfLanguage;
import com.oracle.objectfile.macho.MachOCpuType;
import com.oracle.objectfile.macho.MachOObjectFile;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.test.AddExports;

@AddExports({"java.base/sun.nio.ch", "java.base/jdk.internal.ref"})
public class MachODwarfCodeAddressTest {
    @Test
    public void testDebuggerClassDialect() {
        for (ObjectFile.Format format : new ObjectFile.Format[]{ObjectFile.Format.ELF, ObjectFile.Format.MACH_O}) {
            DwarfDebugInfo dwarf = new DwarfDebugInfo(ELFMachine.AArch64, ByteOrder.LITTLE_ENDIAN, format);
            byte[] language = new byte[5];
            int length = dwarf.getInfoSectionImpl().writeAttrLanguage(dwarf.getLanguageEncoding(), language, 0);
            Assert.assertEquals(format == ObjectFile.Format.MACH_O ? DwarfLanguage.DW_LANG_C_plus_plus.value() : DwarfLanguage.DW_LANG_Java.value(), language[0]);
            Assert.assertEquals(format == ObjectFile.Format.MACH_O ? 5 : 1, length);
        }
    }

    @Test
    public void testDarwinCodeAndHeapAddends() throws Exception {
        for (MachOCpuType cpu : new MachOCpuType[]{MachOCpuType.ARM64, MachOCpuType.X86_64}) {
            MachOObjectFile object = new MachOObjectFile(4096, cpu);
            ObjectFile.Segment segment = object.getSegments().iterator().next();
            ObjectFile.Section text = object.newProgbitsSection(segment, "__text", 16, false, true, new BasicProgbitsSectionImpl(new byte[64]));
            ObjectFile.Section heap = object.newProgbitsSection(segment, "__data", 16, true, false, new BasicProgbitsSectionImpl(new byte[32]));
            object.createDefinedSymbol(DwarfDebugInfo.CODE_BEGIN_NAME, text, 0, 0, true, true, true);
            object.createDefinedSymbol(DwarfDebugInfo.HEAP_BEGIN_NAME, heap, 0, 0, false, true, true);
            object.createDefinedSymbol("test_method", text, 32, 16, true, true, true);
            DwarfDebugInfo dwarf = new DwarfDebugInfo(cpu == MachOCpuType.ARM64 ? ELFMachine.AArch64 : ELFMachine.X86_64,
                            ByteOrder.LITTLE_ENDIAN, ObjectFile.Format.MACH_O);
            DwarfSectionImpl impl = new DwarfSectionImpl(dwarf, DwarfSectionName.DW_INFO_SECTION, DwarfSectionName.TEXT_SECTION) {
                @Override
                public EconomicSet<BuildDependency> getDependencies(Map<ObjectFile.Element, LayoutDecisionMap> decisions) {
                    EconomicSet<BuildDependency> deps = super.getDependencies(decisions);
                    deps.add(BuildDependency.createOrGet(decisions.get(getElement()).getDecision(LayoutDecision.Kind.CONTENT),
                                    decisions.get(getElement()).getDecision(LayoutDecision.Kind.SIZE)));
                    return deps;
                }

                @Override
                public void createContent() {
                    setContent(new byte[24]);
                }

                @Override
                public void writeContent(DebugContext context) {
                    int pos = writeCodeOffset(32, getContent(), 0);
                    pos = writeCodeOffset(48, getContent(), pos);
                    writeHeapOffset(24, getContent(), pos);
                }
            };
            object.newDebugSection(impl.getSectionName(), impl);
            impl.getOrCreateRelocationElement(0);
            Path output = Files.createTempFile("macho-dwarf-address", ".o");
            try {
                object.write(DebugContext.disabled(null), output);
                long textAddress = (int) object.getDecisionsTaken().get(text).getDecidedValue(LayoutDecision.Kind.VADDR);
                ByteBuffer addresses = ByteBuffer.wrap(impl.getContent()).order(ByteOrder.LITTLE_ENDIAN);
                Assert.assertEquals("Code start must retain its object address.", textAddress + 32, addresses.getLong());
                Assert.assertEquals("Code end must remain distinct from its start.", textAddress + 48, addresses.getLong());
                Assert.assertEquals("The external heap addend must not be overwritten.", 24, addresses.getLong());
                ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
                int commandOffset = 32;
                int checked = 0;
                for (int command = 0; command < file.getInt(16); command++) {
                    if (file.getInt(commandOffset) == 0x19) { // LC_SEGMENT_64
                        for (int section = 0; section < file.getInt(commandOffset + 64); section++) {
                            int sectionOffset = commandOffset + 72 + section * 80;
                            int relocationOffset = file.getInt(sectionOffset + 56);
                            int relocationCount = file.getInt(sectionOffset + 60);
                            for (int relocation = 0; relocation < relocationCount; relocation++) {
                                int entry = relocationOffset + relocation * 8;
                                int offset = file.getInt(entry);
                                int word = file.getInt(entry + 4);
                                if (offset == 0 || offset == 8) {
                                    Assert.assertEquals("Code relocations must be local.", 0, word & (1 << 27));
                                    Assert.assertEquals("Mach-O section ordinals are one-based.", object.getSections().indexOf(text) + 1, word & 0xffffff);
                                    checked++;
                                } else {
                                    Assert.assertEquals(16, offset);
                                    Assert.assertNotEquals("Heap relocations remain external.", 0, word & (1 << 27));
                                }
                            }
                        }
                    }
                    commandOffset += file.getInt(commandOffset + 4);
                }
                Assert.assertEquals(2, checked);
            } finally {
                Files.delete(output);
            }
        }
    }
}
