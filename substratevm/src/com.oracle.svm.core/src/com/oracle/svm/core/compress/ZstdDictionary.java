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
package com.oracle.svm.core.compress;

import java.util.function.Consumer;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.guest.staging.core.jdk.UninterruptibleUtils;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/**
 * A zstd dictionary trained at build time, shared by any number of {@link CompressedBlob}s (for
 * example all embedded resources). The decoder's digested form ({@code ZSTD_DDict}) is created on
 * first use in {@link HeapBlobMemory} and kept until isolate teardown, see {@link #tearDown}.
 */
public final class ZstdDictionary {
    /*
     * Dictionaries are created after the static analysis, so both fields are declared unknown:
     * otherwise the analysis, which never sees an allocation, folds them to null.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private byte[] bytes;
    @UnknownObjectField(availability = AfterCompilation.class) private UninterruptibleUtils.AtomicPointer<PointerBase> ddict = new UninterruptibleUtils.AtomicPointer<>();

    /*
     * Each digested dictionary of the isolate lives in its own mapping, which starts with a header
     * that links it into a list for teardown: the next mapping and the size of this one.
     */
    private static final int MAPPING_NEXT_OFFSET = 0;
    private static final int MAPPING_SIZE_OFFSET = MAPPING_NEXT_OFFSET + Long.BYTES;
    private static final int MAPPING_HEADER_SIZE = MAPPING_SIZE_OFFSET + Long.BYTES;

    /** The most recently created mapping of a digested dictionary of this isolate. */
    private static final UninterruptibleUtils.AtomicPointer<Pointer> mappings = new UninterruptibleUtils.AtomicPointer<>();

    @Platforms(Platform.HOSTED_ONLY.class)
    public ZstdDictionary(byte[] bytes) {
        this.bytes = bytes;
    }

    public int getLength() {
        return bytes.length;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public byte[] getBytes() {
        return bytes;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void forEachImageHeapArray(Consumer<byte[]> consumer) {
        consumer.accept(bytes);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void registerAsImmutable(Consumer<Object> registerAsImmutable) {
        registerAsImmutable.accept(this);
        registerAsImmutable.accept(bytes);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    PointerBase getDDict() {
        PointerBase existing = ddict.get();
        if (existing.isNonNull()) {
            return existing;
        }
        /* The digested form references the dictionary bytes, which live in the image heap. */
        UnsignedWord dictSize = Word.unsigned(bytes.length);
        UnsignedWord ddictSize = Zstd.ZSTD_estimateDDictSize(dictSize, Zstd.ZSTD_dlm_byRef);
        UnsignedWord mappingSize = ddictSize.add(MAPPING_HEADER_SIZE);
        Pointer mapping = HeapBlobMemory.allocate(mappingSize);
        if (mapping.isNull()) {
            throw VMError.shouldNotReachHere("zstd: cannot allocate dictionary");
        }
        Pointer dictStart = NonmovableArrays.getArrayBase(NonmovableArrays.fromImageHeap(bytes));
        PointerBase created = Zstd.ZSTD_initStaticDDict(mapping.add(MAPPING_HEADER_SIZE), ddictSize, dictStart, dictSize, Zstd.ZSTD_dlm_byRef, Zstd.ZSTD_dct_auto);
        if (created.isNull()) {
            HeapBlobMemory.free(mapping, mappingSize);
            throw VMError.shouldNotReachHere("zstd: cannot digest dictionary");
        }
        if (ddict.compareAndSet(Word.nullPointer(), created)) {
            mapping.writeWord(MAPPING_SIZE_OFFSET, mappingSize);
            Pointer head;
            do {
                head = mappings.get();
                mapping.writeWord(MAPPING_NEXT_OFFSET, head);
            } while (!mappings.compareAndSet(head, mapping));
            return created;
        }
        HeapBlobMemory.free(mapping, mappingSize);
        return ddict.get();
    }

    /**
     * Releases the digested dictionaries of the current isolate. Called during isolate teardown,
     * after which no blob is inflated anymore.
     */
    @Uninterruptible(reason = "Executes during isolate teardown.")
    public static void tearDown() {
        Pointer mapping = mappings.get();
        mappings.set(Word.nullPointer());
        while (mapping.isNonNull()) {
            Pointer next = mapping.readWord(MAPPING_NEXT_OFFSET);
            UnsignedWord size = mapping.readWord(MAPPING_SIZE_OFFSET);
            HeapBlobMemory.free(mapping, size);
            mapping = next;
        }
    }
}
