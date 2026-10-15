/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.code;

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.compress.CompressedBlob;
import com.oracle.svm.core.compress.HeapBlobProfile;
import com.oracle.svm.core.nmt.NmtCategory;
import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.core.AssertionsSupport;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.core.c.NonmovableObjectArray;
import com.oracle.svm.core.code.FrameInfoDecoder.ConstantAccess;
import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.guest.staging.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.nodes.PauseNode;

/**
 * Provides functionality to query information about a unit of compiled code from a {@link CodeInfo}
 * object. This helper class is necessary to ensure that {@link CodeInfo} objects are used
 * correctly, as they are garbage collected even though they live in unmanaged memory. For that
 * purpose, every {@link CodeInfo} object has a tether object. The garbage collector can free a
 * {@link CodeInfo} object if its tether object is unreachable at a safepoint, that is, in
 * <b>ANY</b> method that is <b>NOT</b> annotated with {@link Uninterruptible}. Even a blocking VM
 * operation that needs a safepoint won't guarantee that the {@link CodeInfo} object is kept alive
 * because GCs can be triggered within VM operations as well.
 * <p>
 * For better type-safety (i.e., to indicate if the tether of a {@link CodeInfo} object was already
 * acquired), we distinguish between {@link UntetheredCodeInfo} and {@link CodeInfo}.
 * <p>
 * {@link UntetheredCodeInfo} objects could be freed at any safepoint. To prevent that, it is
 * possible to call {@link #acquireTether(UntetheredCodeInfo)} from uninterruptible code to create a
 * strong reference to the tether object, which prevents the garbage collector from freeing the
 * object. Subsequently, the {@link UntetheredCodeInfo} should be
 * {@link #convert(UntetheredCodeInfo, Object) converted} to a {@link CodeInfo} object.
 * <p>
 * {@link CodeInfo} objects can be safely passed to interruptible code as their tether was already
 * acquired (calling a separate method with {@link Uninterruptible#calleeMustBe()} == false is
 * recommended). When no further data needs to be accessed, the tether must be
 * {@link #releaseTether(UntetheredCodeInfo, Object) released}. For concrete code examples, see the
 * usages of these methods.
 * <p>
 * Callers of these methods must take into account that frames on the stack can be deoptimized at
 * any safepoint check. When a method is deoptimized, its code can also be
 * {@linkplain CodeInfoTable#invalidateInstalledCode invalidated}, successive
 * {@linkplain CodeInfoTable#lookupCodeInfo lookups of instruction pointers} within the deoptimized
 * code will fail. So, only the initially looked up {@link CodeInfo} object must be used.
 */
public final class CodeInfoAccess {
    private CodeInfoAccess() {
    }

    @Fold
    static boolean haveAssertions() {
        return AssertionsSupport.singleton().desiredAssertionStatus(CodeInfoAccess.class);
    }

    @Uninterruptible(reason = "The handle should only be accessed from uninterruptible code to prevent that the GC frees the CodeInfo.", callerMustBe = true)
    public static Object acquireTether(UntetheredCodeInfo info) {
        Object tether = UntetheredCodeInfoAccess.getTetherUnsafe(info);
        /*
         * Do not interact with the tether object during GCs, as the reference might be forwarded
         * and therefore not safe to access. Tethering is not needed then, either.
         */
        assert UntetheredCodeInfoAccess.isAOTImageCode(info) || VMOperation.isGCInProgress() || ((CodeInfoTether) tether).incrementCount() > 0;
        return tether;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.")
    @NeverInline("Prevent elimination of object reference in caller.")
    public static void releaseTether(UntetheredCodeInfo info, Object tether) {
        assert VMOperation.isGCInProgress() || UntetheredCodeInfoAccess.getTetherUnsafe(info) == null || UntetheredCodeInfoAccess.getTetherUnsafe(info) == tether;
        assert UntetheredCodeInfoAccess.isAOTImageCode(info) || VMOperation.isGCInProgress() || ((CodeInfoTether) tether).decrementCount() >= 0;
    }

    /**
     * Try to avoid using this method. It is similar to
     * {@link #releaseTether(UntetheredCodeInfo, Object)} but with less verification.
     */
    @Uninterruptible(reason = "Called during teardown.", callerMustBe = true)
    @NeverInline("Prevent elimination of object reference in caller.")
    public static void releaseTetherUnsafe(@SuppressWarnings("unused") UntetheredCodeInfo info, Object tether) {
        assert VMOperation.isGCInProgress() || ((CodeInfoTether) tether).decrementCount() >= 0;
    }

    @Uninterruptible(reason = "Should be called from the same method as acquireTether.", callerMustBe = true)
    public static CodeInfo convert(UntetheredCodeInfo untetheredInfo, Object tether) {
        assert UntetheredCodeInfoAccess.getTetherUnsafe(untetheredInfo) == null || UntetheredCodeInfoAccess.getTetherUnsafe(untetheredInfo) == tether;
        return unsafeConvert(untetheredInfo);
    }

    /**
     * Try to avoid using this method. It is similar to {@link #convert(UntetheredCodeInfo, Object)}
     * but with less verification.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static CodeInfo unsafeConvert(UntetheredCodeInfo untetheredInfo) {
        assert isValid(untetheredInfo);
        return (CodeInfo) untetheredInfo;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isValid(UntetheredCodeInfo info) {
        return SubstrateUtil.HOSTED || !haveAssertions() || VMOperation.isGCInProgress() || UntetheredCodeInfoAccess.getTetherUnsafe(info) == null ||
                        ((CodeInfoTether) UntetheredCodeInfoAccess.getTetherUnsafe(info)).getCount() > 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void setState(CodeInfo info, int state) {
        assert getState(info) < state;
        cast(info).setState(state);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int getState(CodeInfo info) {
        return cast(info).getState();
    }

    /**
     * Returns true if the code was invalidated, so that its frames must be deoptimized. Code that
     * was only made non-entrant (for example, by Truffle tier-up) can keep running.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isInvalidated(CodeInfo info) {
        return cast(info).getInvalidated();
    }

    public static String stateToString(int codeInfoState) {
        switch (codeInfoState) {
            case CodeInfo.STATE_CREATED:
                return "created";
            case CodeInfo.STATE_CODE_CONSTANTS_LIVE:
                return "code constants live";
            case CodeInfo.STATE_NON_ENTRANT:
                return "non-entrant";
            case CodeInfo.STATE_PENDING_REMOVAL_FROM_CODE_CACHE:
                return "pending removal from code cache";
            case CodeInfo.STATE_REMOVED_FROM_CODE_CACHE:
                return "removed from code cache";
            case CodeInfo.STATE_PENDING_FREE:
                return "pending free";
            case CodeInfo.STATE_FREED:
                return "invalid (freed)";
            default:
                return "invalid state";
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isAlive(CodeInfo info) {
        return isAliveState(cast(info).getState());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isAliveState(int state) {
        return state == CodeInfo.STATE_CODE_CONSTANTS_LIVE || state == CodeInfo.STATE_NON_ENTRANT;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean hasLiveCodeConstants(CodeInfo info) {
        return isLiveCodeConstantsState(cast(info).getState());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isLiveCodeConstantsState(int state) {
        return isAliveState(state) || state == CodeInfo.STATE_REMOVED_FROM_CODE_CACHE;
    }

    /** @see CodeInfoImpl#getCodeStart */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static CodePointer getCodeStart(CodeInfo info) {
        return cast(info).getCodeStart();
    }

    /** @see CodeInfoImpl#setCodeStart */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void setCodeStart(CodeInfo info, CodePointer codeStart) {
        CodeInfoImpl impl = cast(info);
        impl.setCodeStart(codeStart);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getCodeEntryPointOffset(CodeInfo info) {
        return cast(info).getCodeEntryPointOffset();
    }

    /** @see CodeInfoImpl#getCodeSize */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getCodeSize(CodeInfo info) {
        return cast(info).getCodeSize();
    }

    /** @see CodeInfoImpl#setCodeSize */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void setCodeSize(CodeInfo info, UnsignedWord codeSize) {
        CodeInfoImpl impl = cast(info);
        impl.setCodeSize(codeSize);
    }

    /** @see CodeInfoImpl#getDataSize */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getDataSize(CodeInfo info) {
        return cast(info).getDataSize();
    }

    /** @see CodeInfoImpl#getDataOffset */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getDataOffset(CodeInfo info) {
        return cast(info).getDataOffset();
    }

    /** @see CodeInfoImpl#getCodeAndDataMemorySize */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getCodeAndDataMemorySize(CodeInfo info) {
        return cast(info).getCodeAndDataMemorySize();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static UnsignedWord getNativeMetadataSize(CodeInfo info) {
        CodeInfoImpl impl = cast(info);
        UnsignedWord size = SizeOf.unsigned(CodeInfo.class);
        if (!impl.getAllObjectsAreInImageHeap()) {
            size = size.add(NonmovableArrays.byteSizeOf(impl.getObjectFields()))
                            .add(NonmovableArrays.byteSizeOf(impl.getCodeInfoIndex()))
                            .add(NonmovableArrays.byteSizeOf(impl.getCodeInfoEncodings()))
                            .add(NonmovableArrays.byteSizeOf(impl.getCodeInfoDefaultFrameInfoIndexes()))
                            .add(NonmovableArrays.byteSizeOf(impl.getStackReferenceMapEncoding()))
                            .add(NonmovableArrays.byteSizeOf(impl.getFrameInfoEncodings()))
                            .add(NonmovableArrays.byteSizeOf(impl.getObjectConstants()))
                            .add(NonmovableArrays.byteSizeOf(impl.getClasses()))
                            .add(NonmovableArrays.byteSizeOf(impl.getMemberNames()))
                            .add(NonmovableArrays.byteSizeOf(impl.getOtherStrings()))
                            .add(NonmovableArrays.byteSizeOf(impl.getMethodTable()))
                            .add(NonmovableArrays.byteSizeOf(impl.getDeoptimizationStartOffsets()))
                            .add(NonmovableArrays.byteSizeOf(impl.getDeoptimizationEncodings()))
                            .add(NonmovableArrays.byteSizeOf(impl.getDeoptimizationObjectConstants()))
                            .add(NonmovableArrays.byteSizeOf(impl.getCodeConstantsReferenceMapEncoding()));
        }
        return size;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean contains(CodeInfo info, CodePointer ip) {
        CodeInfoImpl impl = cast(info);
        return ((UnsignedWord) ip).subtract((UnsignedWord) impl.getCodeStart()).belowThan(impl.getCodeSize());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long relativeIP(CodeInfo info, CodePointer ip) {
        assert contains(info, ip);
        CodeInfoImpl impl = cast(info);
        UnsignedWord baseOffset = ((UnsignedWord) ip).subtract((UnsignedWord) impl.getCodeStart());
        return baseOffset.add(impl.getRelativeIPOffset()).rawValue();
    }

    /**
     * Converts a build-time relative IP used by code info encodings back to a runtime code pointer.
     * This is the inverse of {@link #relativeIP(CodeInfo, CodePointer)}.
     */
    public static CodePointer absoluteIP(CodeInfo info, long relativeIP) {
        CodeInfoImpl impl = cast(info);
        UnsignedWord relativeIPWord = Word.unsigned(relativeIP);
        UnsignedWord offset = relativeIPWord.subtract(impl.getRelativeIPOffset());
        assert relativeIP >= 0 && relativeIPWord.aboveOrEqual(impl.getRelativeIPOffset()) && offset.belowThan(impl.getCodeSize()) : "relativeIP=" + relativeIP + ", relativeIPOffset=" +
                        impl.getRelativeIPOffset().rawValue() + ", codeSize=" + impl.getCodeSize().rawValue();
        return absoluteIPFromCodeStartOffset(impl, offset);
    }

    /**
     * Converts an offset measured directly from {@link CodeInfoImpl#getCodeStart()} into a runtime
     * code pointer. Use this for code-section coordinates that are not encoded relative IPs and
     * therefore must not be adjusted by {@link CodeInfoImpl#getRelativeIPOffset()}.
     */
    public static CodePointer absoluteIPFromCodeStartOffset(CodeInfo info, long codeStartOffset) {
        CodeInfoImpl impl = cast(info);
        UnsignedWord offset = Word.unsigned(codeStartOffset);
        assert codeStartOffset >= 0 && offset.belowThan(impl.getCodeSize()) : "codeStartOffset=" + codeStartOffset + ", codeSize=" + impl.getCodeSize().rawValue();
        return absoluteIPFromCodeStartOffset(impl, offset);
    }

    private static CodePointer absoluteIPFromCodeStartOffset(CodeInfoImpl impl, UnsignedWord codeStartOffset) {
        return (CodePointer) ((UnsignedWord) impl.getCodeStart()).add(codeStartOffset);
    }

    @SuppressWarnings("unchecked")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static <T> T getObjectField(CodeInfo info, int index) {
        return (T) NonmovableArrays.getObject(cast(info).getObjectFields(), index);
    }

    public static String getName(CodeInfo info) {
        return getObjectField(info, CodeInfoImpl.NAME_OBJFIELD);
    }

    public static long lookupDeoptimizationEntrypoint(CodeInfo info, long method, long encodedBci, CodeInfoQueryResult codeInfo, ConstantAccess constantAccess) {
        return CodeInfoDecoder.lookupDeoptimizationEntrypoint(info, method, encodedBci, codeInfo, constantAccess);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long lookupTotalFrameSize(CodeInfo info, long relativeIP) {
        return CodeInfoQueryResult.getTotalFrameSize(lookupEncodedFrameSize(info, relativeIP));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long lookupTotalFrameSize(CodeInfo info, CodePointer ip) {
        return CodeInfoQueryResult.getTotalFrameSize(lookupEncodedFrameSize(info, ip));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getStackReferenceMapEncoding(CodeInfo info) {
        return cast(info).getStackReferenceMapEncoding();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long lookupStackReferenceMapIndex(CodeInfo info, long relativeIP) {
        return CodeInfoDecoder.lookupStackReferenceMapIndex(info, relativeIP);
    }

    public static void lookupCodeInfo(CodeInfo info, CodePointer ip, CodeInfoQueryResult codeInfoQueryResult) {
        lookupCodeInfo(info, ip, codeInfoQueryResult, FrameInfoDecoder.SubstrateConstantAccess);
    }

    public static void lookupCodeInfo(CodeInfo info, CodePointer ip, CodeInfoQueryResult codeInfoQueryResult, ConstantAccess constantAccess) {
        long relativeIP = CodeInfoAccess.relativeIP(info, ip);
        CodeInfoDecoder.lookupCodeInfo(info, relativeIP, codeInfoQueryResult, constantAccess);
    }

    public static void lookupCodeInfo(CodeInfo info, long relativeIP, CodeInfoQueryResult codeInfoQueryResult, ConstantAccess constantAccess) {
        CodeInfoDecoder.lookupCodeInfo(info, relativeIP, codeInfoQueryResult, constantAccess);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void lookupCodeInfo(CodeInfo info, CodePointer ip, SimpleCodeInfoQueryResult codeInfoQueryResult) {
        long relativeIP = CodeInfoAccess.relativeIP(info, ip);
        CodeInfoDecoder.lookupCodeInfo(info, relativeIP, codeInfoQueryResult);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long lookupEncodedFrameSize(CodeInfo info, CodePointer ip) {
        long relativeIP = CodeInfoAccess.relativeIP(info, ip);
        return lookupEncodedFrameSize(info, relativeIP);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long lookupEncodedFrameSize(CodeInfo info, long relativeIP) {
        SimpleCodeInfoQueryResult codeInfoQueryResult = UnsafeStackValue.get(SimpleCodeInfoQueryResult.class);
        CodeInfoDecoder.lookupCodeInfo(info, relativeIP, codeInfoQueryResult);
        return codeInfoQueryResult.getEncodedFrameSize();
    }

    @Uninterruptible(reason = "Nonmovable object arrays are not visible to GC until installed.")
    public static void setFrameInfo(CodeInfo info, NonmovableArray<Byte> encodings) {
        CodeInfoImpl impl = cast(info);
        impl.setFrameInfoEncodings(encodings);
    }

    private static final int FRAME_INFO_CHUNK_ABSENT = 0;
    private static final int FRAME_INFO_CHUNK_INFLATING = 1;
    private static final int FRAME_INFO_CHUNK_READY = 2;

    /**
     * Installs compressed frame information of image code: the uncompressed array is allocated in
     * unmanaged memory (its pages are only touched when chunks are inflated) and chunks are inflated
     * on demand by {@link #ensureFrameInfoAvailable}.
     */
    @Uninterruptible(reason = "Executes during isolate creation.")
    static void setCompressedFrameInfo(CodeInfo info, CompressedBlob blob) {
        CodeInfoImpl impl = cast(info);
        /*
         * Accounted as image heap: it is image-heap content, and the Code category must start at 0.
         * Chunks are always inflated before they are read, so the array is not zeroed: zeroing it
         * would fault in all of its pages at isolate creation even if no chunk is ever inflated.
         */
        NonmovableArray<Byte> encodings = NonmovableArrays.createUninitializedByteArray(blob.getUncompressedLength(), NmtCategory.ImageHeap);
        NonmovableArray<Integer> states = NonmovableArrays.createIntArray(blob.getChunkCount(), NmtCategory.ImageHeap);
        UnmanagedMemoryUtil.fill(NonmovableArrays.getArrayBase(states), Word.unsigned(blob.getChunkCount()).multiply(Integer.BYTES), (byte) 0);
        impl.setFrameInfoEncodings(encodings);
        impl.setFrameInfoChunkStates(states);
    }

    /**
     * Makes the frame information starting at {@code index} readable. Compressed frame information
     * is inflated chunk by chunk on first use; a frame slice is never longer than a chunk, so the
     * chunk holding the index and the following one cover every sequential read. Readers that jump
     * to another offset, e.g. to a shared frame, call this again for the target offset.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void ensureFrameInfoAvailable(CodeInfo info, long index) {
        CodeInfoImpl impl = cast(info);
        NonmovableArray<Integer> states = impl.getFrameInfoChunkStates();
        if (states.isNull()) {
            return;
        }
        CompressedBlob blob = ImageCodeInfo.getFrameInfoBlob(info);
        int length = blob.getUncompressedLength();
        if (index < 0 || index >= length) {
            return;
        }
        int first = blob.getChunkIndex((int) index);
        ensureFrameInfoChunk(impl, blob, states, first);
        long windowEnd = index + blob.getChunkLength(0);
        if (windowEnd > length) {
            windowEnd = length;
        }
        windowEnd--;
        int last = blob.getChunkIndex((int) windowEnd);
        if (last != first) {
            ensureFrameInfoChunk(impl, blob, states, last);
        }
    }

    /**
     * Reports the chunks of compressed frame information that were inflated so far, with the hash of
     * their contents, for heap-blob profiles.
     */
    static void recordInflatedFrameInfoChunks(CodeInfo info, CompressedBlob blob, HeapBlobProfile.Recorder recorder) {
        CodeInfoImpl impl = cast(info);
        NonmovableArray<Integer> states = impl.getFrameInfoChunkStates();
        if (states.isNull()) {
            return;
        }
        for (int chunk = 0; chunk < blob.getChunkCount(); chunk++) {
            if (NonmovableArrays.getInt(states, chunk) == FRAME_INFO_CHUNK_READY) {
                Pointer data = NonmovableArrays.addressOf(impl.getFrameInfoEncodings(), blob.getChunkStart(chunk));
                recorder.chunk(HeapBlobProfile.FRAME_INFORMATION, chunk, HeapBlobProfile.hash(data, blob.getChunkLength(chunk)));
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void ensureFrameInfoChunk(CodeInfoImpl impl, CompressedBlob blob, NonmovableArray<Integer> states, int chunk) {
        Pointer state = NonmovableArrays.addressOf(states, chunk);
        /* A compare-and-swap is used for the read as well: it is a full fence on every platform. */
        if (state.compareAndSwapInt(0, FRAME_INFO_CHUNK_READY, FRAME_INFO_CHUNK_READY, LocationIdentity.ANY_LOCATION) == FRAME_INFO_CHUNK_READY) {
            return;
        }
        if (state.compareAndSwapInt(0, FRAME_INFO_CHUNK_ABSENT, FRAME_INFO_CHUNK_INFLATING, LocationIdentity.ANY_LOCATION) == FRAME_INFO_CHUNK_ABSENT) {
            int newState = FRAME_INFO_CHUNK_ABSENT;
            try {
                Pointer dst = NonmovableArrays.addressOf(impl.getFrameInfoEncodings(), blob.getChunkStart(chunk));
                blob.inflateChunkTo(chunk, dst, Word.unsigned(blob.getChunkLength(chunk)));
                newState = FRAME_INFO_CHUNK_READY;
            } finally {
                /* Never leave the chunk marked as inflating, or every later reader would spin. */
                state.compareAndSwapInt(0, FRAME_INFO_CHUNK_INFLATING, newState, LocationIdentity.ANY_LOCATION);
            }
        } else {
            /* Another thread is inflating this chunk; inflation takes microseconds. */
            while (state.compareAndSwapInt(0, FRAME_INFO_CHUNK_READY, FRAME_INFO_CHUNK_READY, LocationIdentity.ANY_LOCATION) != FRAME_INFO_CHUNK_READY) {
                PauseNode.pause();
            }
        }
    }

    @Uninterruptible(reason = "Nonmovable arrays are not visible to GC until installed.")
    public static void setCodeInfo(CodeInfo info, NonmovableArray<Byte> index, NonmovableArray<Byte> encodings, int indexEntriesPerBlock,
                    NonmovableArray<Byte> defaultFrameInfoIndexes, NonmovableArray<Byte> referenceMapEncoding) {
        CodeInfoImpl impl = cast(info);
        impl.setCodeInfoIndex(index);
        impl.setCodeInfoEncodings(encodings);
        impl.setCodeInfoIndexEntriesPerBlock(indexEntriesPerBlock);
        impl.setCodeInfoDefaultFrameInfoIndexes(defaultFrameInfoIndexes);
        impl.setStackReferenceMapEncoding(referenceMapEncoding);
    }

    @Uninterruptible(reason = "Nonmovable object arrays are not visible to GC until installed.")
    public static void setEncodings(CodeInfo info, NonmovableObjectArray<Object> objectConstants, NonmovableObjectArray<Class<?>> classes,
                    NonmovableObjectArray<String> memberNames, NonmovableObjectArray<String> otherStrings, NonmovableArray<Byte> methodTable, int methodTableFirstId, int methodTableEntryCount) {
        CodeInfoImpl impl = cast(info);
        impl.setObjectConstants(objectConstants);
        impl.setClasses(classes);
        impl.setMemberNames(memberNames);
        impl.setOtherStrings(otherStrings);
        impl.setMethodTable(methodTable);
        impl.setMethodTableFirstId(methodTableFirstId);
        impl.setMethodCount(methodTableEntryCount);
        if (!SubstrateUtil.HOSTED) {
            // notify the GC about the frame metadata that is now live
            Heap.getHeap().getRuntimeCodeInfoGCSupport().registerFrameMetadata(impl);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int getTier(CodeInfo info) {
        return cast(info).getTier();
    }

    static NonmovableArray<Integer> getDeoptimizationStartOffsets(CodeInfo info) {
        return cast(info).getDeoptimizationStartOffsets();
    }

    static NonmovableArray<Byte> getDeoptimizationEncodings(CodeInfo info) {
        return cast(info).getDeoptimizationEncodings();
    }

    static NonmovableObjectArray<Object> getDeoptimizationObjectConstants(CodeInfo info) {
        return cast(info).getDeoptimizationObjectConstants();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static CodePointer getCodeEnd(CodeInfo info) {
        CodeInfoImpl impl = cast(info);
        return (CodePointer) ((UnsignedWord) impl.getCodeStart()).add(impl.getCodeSize());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getCodeInfoIndex(CodeInfo info) {
        return cast(info).getCodeInfoIndex();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getCodeInfoEncodings(CodeInfo info) {
        return cast(info).getCodeInfoEncodings();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int getCodeInfoIndexEntriesPerBlock(CodeInfo info) {
        return cast(info).getCodeInfoIndexEntriesPerBlock();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getCodeInfoDefaultFrameInfoIndexes(CodeInfo info) {
        return cast(info).getCodeInfoDefaultFrameInfoIndexes();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean usesFinalImageCodeInfoEncoding(CodeInfo info) {
        return cast(info).getCodeInfoDefaultFrameInfoIndexes().isNonNull();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getFrameInfoEncodings(CodeInfo info) {
        return cast(info).getFrameInfoEncodings();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableObjectArray<Object> getObjectConstants(CodeInfo info) {
        return cast(info).getObjectConstants();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableObjectArray<Class<?>> getClasses(CodeInfo info) {
        return cast(info).getClasses();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableObjectArray<String> getMemberNames(CodeInfo info) {
        return cast(info).getMemberNames();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableObjectArray<String> getOtherStrings(CodeInfo info) {
        return cast(info).getOtherStrings();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static NonmovableArray<Byte> getMethodTable(CodeInfo info) {
        return cast(info).getMethodTable();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int getMethodTableFirstId(CodeInfo info) {
        return cast(info).getMethodTableFirstId();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int getMethodCount(CodeInfo info) {
        return cast(info).getMethodCount();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isAOTImageCode(CodeInfo info) {
        return cast(info).getIsAOTImageCode();
    }

    /**
     * In some callers, we can't necessarily assume that we have a valid {@link CodeInfo} object.
     * So, this method explicitly avoids any {@link CodeInfo} field accesses.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean isAOTImageCodeSlow(CodeInfo info) {
        for (CodeInfo imageCodeInfo = CodeInfoTable.getFirstImageCodeInfo(); imageCodeInfo.isNonNull(); imageCodeInfo = getNextImageCodeInfo(imageCodeInfo)) {
            if (info == imageCodeInfo) {
                return true;
            }
        }
        return false;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static CodeInfo getNextImageCodeInfo(CodeInfo info) {
        assert isAOTImageCode(info);
        return cast(info).getNextImageCodeInfo();
    }

    /** @see CodeInfoImpl#setNextImageCodeInfo */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static void setNextImageCodeInfo(CodeInfo info, CodeInfo next) {
        assert isAOTImageCode(info);
        CodeInfoImpl impl = cast(info);
        assert impl.getNextImageCodeInfo().isNull();
        impl.setNextImageCodeInfo(next);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static CodeInfoImpl cast(UntetheredCodeInfo info) {
        assert isValid(info);
        return (CodeInfoImpl) info;
    }

    public static void printCodeInfo(Log log, CodeInfo info, boolean allowJavaHeapAccess) {
        String name = null;
        HasInstalledCode hasInstalledCode = HasInstalledCode.Unknown;
        SubstrateInstalledCode installedCode = null;
        if (allowJavaHeapAccess) {
            name = CodeInfoAccess.getName(info);
            installedCode = RuntimeCodeInfoAccess.getInstalledCode(info);
            hasInstalledCode = (installedCode == null) ? HasInstalledCode.No : HasInstalledCode.Yes;
        }

        printCodeInfo(log, info, CodeInfoAccess.getState(info), name, CodeInfoAccess.getCodeStart(info), CodeInfoAccess.getCodeEnd(info), hasInstalledCode, installedCode);
    }

    public static void printCodeInfo(Log log, UntetheredCodeInfo info, int state, String name, CodePointer codeStart, CodePointer codeEnd, HasInstalledCode hasInstalledCode,
                    SubstrateInstalledCode installedCode) {
        long installedCodeAddress = 0;
        long installedCodeEntryPoint = 0;
        if (installedCode != null) {
            assert hasInstalledCode == HasInstalledCode.Yes : hasInstalledCode;
            installedCodeAddress = installedCode.getAddress();
            installedCodeEntryPoint = installedCode.getEntryPoint();
        }

        printCodeInfo(log, info, state, name, codeStart, codeEnd, hasInstalledCode, installedCodeAddress, installedCodeEntryPoint);
    }

    public static void printCodeInfo(Log log, UntetheredCodeInfo codeInfo, int state, String name, CodePointer codeStart, CodePointer codeEnd, HasInstalledCode hasInstalledCode,
                    long installedCodeAddress, long installedCodeEntryPoint) {
        log.string("CodeInfo (").zhex(codeInfo).string(" - ").zhex(((UnsignedWord) codeInfo).add(CodeInfoAccess.getSizeOfCodeInfo()).subtract(1)).string("), ")
                        .string(CodeInfoAccess.stateToString(state));
        if (name != null) {
            log.string(" - ").string(name);
        }
        log.string(", ip: (").zhex(codeStart).string(" - ").zhex(codeEnd).string(")");

        switch (hasInstalledCode) {
            case Yes:
                log.string(", installedCode: (address: ").zhex(installedCodeAddress).string(", entryPoint: ").zhex(installedCodeEntryPoint).string(")");
                break;
            case No:
                log.string(", installedCode: null.");
                break;
            case Unknown:
                // nothing to do.
                break;
            default:
                throw VMError.shouldNotReachHere("Unexpected value for HasInstalledCode");
        }
    }

    @Fold
    public static UnsignedWord getSizeOfCodeInfo() {
        return SizeOf.unsigned(CodeInfoImpl.class);
    }

    public enum HasInstalledCode {
        Yes,
        No,
        Unknown
    }
}
