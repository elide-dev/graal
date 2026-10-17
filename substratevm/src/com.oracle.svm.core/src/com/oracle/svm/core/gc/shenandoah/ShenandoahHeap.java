/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.gc.shenandoah;

import static com.oracle.svm.core.gc.shenandoah.ShenandoahOptions.ShenandoahRegionSize;
import static com.oracle.svm.core.heap.RuntimeCodeCacheCleaner.CLASSES_ASSUMED_REACHABLE;
import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.RuntimeStateTrimConfig.Mode;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.shared.BuildPhaseProvider.ReadyForCompilation;
import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.SubstrateDiagnostics;
import com.oracle.svm.core.SubstrateDiagnostics.DiagnosticThunk;
import com.oracle.svm.core.SubstrateDiagnostics.ErrorContext;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.guest.staging.SubstrateGCOptions;
import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.VMInspectionOptions;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.gc.shared.NativeGCStackWalker;
import com.oracle.svm.core.gc.shared.NativeGCThreadTransitions;
import com.oracle.svm.core.gc.shared.NativeGCThreadsLock;
import com.oracle.svm.core.gc.shared.NativeGCVMOperationSupport;
import com.oracle.svm.core.gc.shared.NativeGCVMOperationSupport.NativeGCVMOperationData;
import com.oracle.svm.core.gc.shared.NativeGCVMOperationSupport.NativeGCVMOperationWrapperData;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahLibrary;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahStructs.ShenandoahInitState;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahStructs.ShenandoahInternalState;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahStructs.ShenandoahRegionInfo;
import com.oracle.svm.core.graal.RuntimeCompilation;
import com.oracle.svm.guest.staging.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.heap.FillerArray;
import com.oracle.svm.core.heap.FillerObject;
import com.oracle.svm.core.heap.GC;
import com.oracle.svm.core.heap.GCCause;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.InstanceReferenceMapEncoder;
import com.oracle.svm.core.heap.NoAllocationVerifier;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.guest.staging.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.heap.RuntimeCodeInfoGCSupport;
import com.oracle.svm.core.heap.StoredContinuation;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubUtils;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.shared.singletons.MultiLayeredImageSingleton;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.option.NotifyGCRuntimeOptionKey;
import com.oracle.svm.guest.staging.option.RuntimeOptionKey;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.core.metaspace.Metaspace;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.core.thread.Safepoint;
import com.oracle.svm.guest.staging.core.thread.ThreadStatus;
import com.oracle.svm.core.thread.ThreadsLock;
import com.oracle.svm.core.thread.VMOperationControl;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.thread.VMThreads.SafepointBehavior;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocal;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalBytes;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.VMThreadLocalSupport;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.nodes.extended.MembarNode;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import org.graalvm.word.impl.Word;
import jdk.vm.ci.meta.JavaKind;

@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class ShenandoahHeap extends Heap {
    /* Keep frequently accessed allocation and barrier fields within compact displacement range. */
    public static final FastThreadLocalBytes<Word> barrierAndAllocationDataTL = FastThreadLocalFactory.createBytes(ShenandoahConstants::barrierAndAllocationDataSize,
                                                                                                                   "ShenandoahHeap.barrierAndAllocationData")
                    .setMaxOffset(FastThreadLocal.BYTE_OFFSET - ShenandoahConstants.cardTableOffsetRel());
    public static final FastThreadLocalBytes<Word> javaThreadTL = FastThreadLocalFactory.createBytes(ShenandoahConstants::javaThreadSize, "ShenandoahHeap.javaThread");

    private final ShenandoahGC gc = new ShenandoahGC();
    private final ShenandoahImageHeapInfo imageHeapInfo = new ShenandoahImageHeapInfo();
    private final ShenandoahRuntimeCodeInfoGCSupport runtimeCodeInfoGCSupport = new ShenandoahRuntimeCodeInfoGCSupport();
    private final NativeGCStackWalker stackWalker = new NativeGCStackWalker();
    private final NativeGCThreadTransitions threadTransitions = new NativeGCThreadTransitions();
    private final NativeGCThreadsLock threadsLock = new NativeGCThreadsLock();
    private final NativeGCVMOperationSupport vmOperationSupport = new NativeGCVMOperationSupport();
    private final ShenandoahVMOperations vmOperations = new ShenandoahVMOperations();
    private final ShenandoahObjectHeader objectHeader = new ShenandoahObjectHeader();

    private boolean isInitialized = false;
    private List<Class<?>> classList;

    @UnknownObjectField(availability = ReadyForCompilation.class) private byte[] accessedFieldOffsets;

    @Platforms(Platform.HOSTED_ONLY.class)
    public ShenandoahHeap() {
    }

    @Fold
    public static ShenandoahHeap get() {
        return ImageSingletons.lookup(ShenandoahHeap.class);
    }

    @Fold
    public static ShenandoahImageHeapInfo getImageHeapInfo() {
        return ShenandoahHeap.get().imageHeapInfo;
    }

    @Uninterruptible(reason = "Called during startup.")
    private void initialize(IsolateThread isolateThread) {
        VMError.guarantee(ThreadsLock.hasWriteAccess(), "Only the first thread may initialize the heap");
        assert !isInitialized;
        isInitialized = true;

        ShenandoahHeap heap = ImageSingletons.lookup(ShenandoahHeap.class);
        VMThreadLocalSupport threadLocalSupport = ImageSingletons.lookup(VMThreadLocalSupport.class);

        Pointer heapBase = KnownIntrinsics.heapBase();
        assert heap.getImageHeapOffsetInAddressSpace() % ShenandoahRegionSize.getValue() == 0 : "null regions must be full regions";
        int closedImageHeapRegions = imageHeapInfo.getNumClosedRegions();
        int openImageHeapRegions = imageHeapInfo.getNumOpenRegions();
        Word imageHeapRegionTypes = Word.objectToUntrackedWord(imageHeapInfo.getRegionTypes());
        Word imageHeapRegionFreeSpaces = Word.objectToUntrackedWord(imageHeapInfo.getRegionFreeSpaces());
        Word dynamicHubClass = Word.objectToUntrackedWord(DynamicHub.class);
        Word fillerObjectClass = Word.objectToUntrackedWord(FillerObject.class);
        Word fillerArrayClass = Word.objectToUntrackedWord(FillerArray.class);
        Word stringClass = Word.objectToUntrackedWord(String.class);
        Word systemClass = Word.objectToUntrackedWord(System.class);
        Word staticObjectFields = Word.objectToUntrackedWord(StaticFieldsSupport.getStaticObjectFieldsAtRuntime(MultiLayeredImageSingleton.UNKNOWN_LAYER_NUMBER));
        Word staticPrimitiveFields = Word.objectToUntrackedWord(StaticFieldsSupport.getStaticPrimitiveFieldsAtRuntime(MultiLayeredImageSingleton.UNKNOWN_LAYER_NUMBER));
        Word vmOperationThread = Word.objectToUntrackedWord(VMOperationControl.getDedicatedVMOperationThread());
        Word safepointMaster = Word.objectToUntrackedWord(Safepoint.singleton());
        Word runtimeCodeInfoMemory = Word.objectToUntrackedWord(RuntimeCodeInfoMemory.singleton());
        int referenceMapCompressedOffsetShift = InstanceReferenceMapEncoder.REFERENCE_MAP_COMPRESSED_OFFSET_SHIFT;
        Word threadLocalsReferenceMap = NonmovableArrays.addressOf(threadLocalSupport.getThreadLocalsReferenceMap(), threadLocalSupport.getThreadLocalsReferenceMapIndex());
        Word classesAssumedReachableForCodeUnloading = getClassesAssumedReachableForCodeUnloading();
        boolean perfDataSupport = VMInspectionOptions.hasJvmstatSupport();
        boolean closedTypeWorldHubLayout = SubstrateOptions.useClosedTypeWorldHubLayout();
        boolean useInterfaceHashing = SubstrateOptions.useInterfaceHashing();
        int interfaceHashingMaxId = SubstrateOptions.interfaceHashingMaxId();
        int dynamicHubHashingInterfaceMask = DynamicHubUtils.HASHING_INTERFACE_MASK;
        int dynamicHubHashingShiftOffset = DynamicHubUtils.HASHING_SHIFT_OFFSET;
        Word offsets = Word.objectToUntrackedWord(accessedFieldOffsets).add(getByteArrayBaseOffset());
        int offsetsLength = accessedFieldOffsets.length;
        CFunctionPointer collectForAllocationOp = getFunctionPointer(vmOperations.funcCollectForAllocation);
        CFunctionPointer collectFullOp = getFunctionPointer(vmOperations.funcCollectFull);
        CFunctionPointer collectDegeneratedOp = getFunctionPointer(vmOperations.funcCollectDegenerated);
        CFunctionPointer initMarkOp = getFunctionPointer(vmOperations.funcInitMark);
        CFunctionPointer finalMarkOp = getFunctionPointer(vmOperations.funcFinalMark);
        CFunctionPointer initUpdateRefsOp = getFunctionPointer(vmOperations.funcInitUpdateRefs);
        CFunctionPointer finalUpdateRefsOp = getFunctionPointer(vmOperations.funcFinalUpdateRefs);
        CFunctionPointer finalRootsOp = getFunctionPointer(vmOperations.funcFinalRoots);
        CFunctionPointer handshakeFallbackOp = getFunctionPointer(vmOperations.funcHandshakeFallback);
        CFunctionPointer waitForVMOperationExecutionStatus = getFunctionPointer(vmOperationSupport.funcWaitForVMOperationExecutionStatus);
        CFunctionPointer updateVMOperationExecutionStatus = getFunctionPointer(vmOperationSupport.funcUpdateVMOperationExecutionStatus);
        CFunctionPointer isVMOperationFinished = getFunctionPointer(vmOperationSupport.funcIsVMOperationFinished);
        CFunctionPointer yieldToQueuedVMOperations = getFunctionPointer(vmOperationSupport.funcYieldToQueuedVMOperations);
        CFunctionPointer fetchThreadStackFrames = getFunctionPointer(stackWalker.funcFetchThreadStackFrames);
        CFunctionPointer freeThreadStackFrames = getFunctionPointer(stackWalker.funcFreeThreadStackFrames);
        CFunctionPointer fetchContinuationStackFrames = getFunctionPointer(stackWalker.funcFetchContinuationStackFrames);
        CFunctionPointer freeContinuationStackFrames = getFunctionPointer(stackWalker.funcFreeContinuationStackFrames);
        CFunctionPointer fetchCodeInfos = getFunctionPointer(stackWalker.funcFetchCodeInfos);
        CFunctionPointer freeCodeInfos = getFunctionPointer(stackWalker.funcFreeCodeInfos);
        CFunctionPointer cleanRuntimeCodeCache = getFunctionPointer(runtimeCodeInfoGCSupport.funcCleanCodeCache);
        CFunctionPointer transitionVMToNative = getFunctionPointer(threadTransitions.funcVMToNative);
        CFunctionPointer fastTransitionNativeToVM = getFunctionPointer(threadTransitions.funcFastNativeToVM);
        CFunctionPointer slowTransitionNativeToVM = getFunctionPointer(threadTransitions.funcSlowNativeToVM);
        CFunctionPointer lockThreadsRead = getFunctionPointer(threadsLock.funcLockThreadsRead);
        CFunctionPointer unlockThreadsRead = getFunctionPointer(threadsLock.funcUnlockThreadsRead);

        ShenandoahInitState initState = ShenandoahLibrary.create(isolateThread, heapBase,
                        closedImageHeapRegions, openImageHeapRegions, imageHeapRegionTypes, imageHeapRegionFreeSpaces,
                        dynamicHubClass, fillerObjectClass, fillerArrayClass, stringClass, systemClass,
                        staticObjectFields, staticPrimitiveFields, vmOperationThread, safepointMaster, runtimeCodeInfoMemory,
                        referenceMapCompressedOffsetShift, threadLocalsReferenceMap,
                        classesAssumedReachableForCodeUnloading, perfDataSupport, closedTypeWorldHubLayout,
                        useInterfaceHashing, interfaceHashingMaxId, dynamicHubHashingInterfaceMask, dynamicHubHashingShiftOffset,
                        offsets, offsetsLength,
                        collectForAllocationOp, collectFullOp, collectDegeneratedOp, initMarkOp, finalMarkOp, initUpdateRefsOp, finalUpdateRefsOp, finalRootsOp, handshakeFallbackOp,
                        waitForVMOperationExecutionStatus, updateVMOperationExecutionStatus, isVMOperationFinished, yieldToQueuedVMOperations,
                        fetchThreadStackFrames, freeThreadStackFrames,
                        fetchContinuationStackFrames, freeContinuationStackFrames,
                        fetchCodeInfos, freeCodeInfos, cleanRuntimeCodeCache,
                        transitionVMToNative, fastTransitionNativeToVM, slowTransitionNativeToVM,
                        lockThreadsRead, unlockThreadsRead);

        VMError.guarantee(initState.isNonNull(), "Fatal error while initializing Shenandoah");
        validateInitState(initState);
    }

    @Uninterruptible(reason = "Called during startup.")
    private static Word getClassesAssumedReachableForCodeUnloading() {
        if (RuntimeCompilation.isEnabled()) {
            return Word.objectToUntrackedWord(CLASSES_ASSUMED_REACHABLE);
        }
        return Word.nullPointer();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static CFunctionPointer getFunctionPointer(CEntryPointLiteral<CFunctionPointer> f) {
        if (f == null) {
            return Word.nullPointer();
        }
        return f.getFunctionPointer();
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void suspendAllocation() {
        // Retire the TLAB so that the next allocation is forced to take the slow path.
        ShenandoahLibrary.retireTlab();
    }

    @Override
    public void resumeAllocation() {
        // Nothing to do - the next allocation will refill the TLAB.
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isAllocationDisallowed() {
        return NoAllocationVerifier.isActive() || SafepointBehavior.ignoresSafepoints();
    }

    @Fold
    @Override
    public GC getGC() {
        return gc;
    }

    @Fold
    @Override
    public RuntimeCodeInfoGCSupport getRuntimeCodeInfoGCSupport() {
        return runtimeCodeInfoGCSupport;
    }

    @Override
    public void walkObjects(ObjectVisitor visitor) {
        walkImageHeapObjects(visitor);
        walkCollectedHeapObjects(visitor);
    }

    @Override
    public void walkImageHeapObjects(ObjectVisitor visitor) {
        if (visitor == null) {
            return;
        }

        byte[] regionTypes = imageHeapInfo.getRegionTypes();
        int imageHeapRegions = imageHeapInfo.getNumRegions();
        for (int region = 0; region < imageHeapRegions; region++) {
            if (!ShenandoahRegionType.isContinuesHumongous(regionTypes[region])) {
                Pointer cur = imageHeapInfo.getRegionStart(region);
                Pointer top = imageHeapInfo.getRegionTop(region);
                while (cur.belowThan(top)) {
                    Object o = cur.toObject();
                    visitor.visitObject(o);
                    cur = LayoutEncoding.getImageHeapObjectEnd(o);
                }
            }
        }
    }

    @Override
    public void walkCollectedHeapObjects(ObjectVisitor visitor) {
        if (visitor == null) {
            return;
        }
        ShenandoahHeapWalker.walkCollectedHeap(visitor);
    }

    @Fold
    public static int getNullRegionSize() {
        int buildTimePageSize = SubstrateOptions.getPageSize();
        int result = Math.max(buildTimePageSize * ShenandoahConstants.cardSize(), ShenandoahRegionSize.getValue());
        assert result % ShenandoahRegionSize.getValue() == 0 : "null region size must be region-aligned";
        return result;
    }

    @Fold
    static int getMetaspaceOffsetInAddressSpace() {
        int result = getNullRegionSize();
        assert result % ShenandoahRegionSize.getValue() == 0 : "start of metaspace must be region-aligned";
        return result;
    }

    @Fold
    static int getReservedMetaspaceSize() {
        if (!Metaspace.isSupported()) {
            return 0;
        }

        int unalignedValue = SubstrateGCOptions.ConcealedOptions.getMaxMetaspaceSize();
        return NumUtil.roundUp(unalignedValue, ShenandoahOptions.ShenandoahRegionSize.getValue());
    }

    @Fold
    @Override
    public int getHeapBaseAlignment() {
        int buildTimePageSize = SubstrateOptions.getPageSize();
        return Math.max(buildTimePageSize * ShenandoahConstants.cardSize(), ShenandoahRegionSize.getValue());
    }

    @Fold
    @Override
    public int getImageHeapAlignment() {
        return ShenandoahRegionSize.getValue();
    }

    @Fold
    @Override
    public int getImageHeapOffsetInAddressSpace() {
        int result = getNullRegionSize() + getReservedMetaspaceSize();
        assert result % getImageHeapAlignment() == 0 : "start of image heap must be aligned";
        return result;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isInImageHeap(Object object) {
        return isInPrimaryImageHeap(object);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isInImageHeap(Pointer pointer) {
        return isInPrimaryImageHeap(pointer);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isInPrimaryImageHeap(Object object) {
        Word pointer = Word.objectToUntrackedWord(object);
        return isInPrimaryImageHeap(pointer);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public boolean isInPrimaryImageHeap(Pointer pointer) {
        return pointer.aboveOrEqual(imageHeapInfo.getImageHeapStart()) && pointer.belowThan(imageHeapInfo.getImageHeapEnd());
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public int getClassCount() {
        return imageHeapInfo.getDynamicHubCount();
    }

    @Override
    protected List<Class<?>> getClassesInImageHeap() {
        if (classList == null) {
            ArrayList<Class<?>> classes = findAllDynamicHubs();
            /* Ensure that other threads see consistent values once the list is published. */
            MembarNode.memoryBarrier(MembarNode.FenceKind.STORE_STORE);
            classList = classes;
        }
        return classList;
    }

    private ArrayList<Class<?>> findAllDynamicHubs() {
        byte[] regionTypes = imageHeapInfo.getRegionTypes();
        int hubCount = getClassCount();

        /* DynamicHubs are somewhere in the closed image heap. */
        ArrayList<Class<?>> classes = new ArrayList<>(hubCount);
        for (int region = 0; region < imageHeapInfo.getNumClosedRegions(); region++) {
            if (!ShenandoahRegionType.isHumongous(regionTypes[region])) {
                Pointer cur = imageHeapInfo.getRegionStart(region);
                Pointer top = imageHeapInfo.getRegionTop(region);
                while (cur.belowThan(top)) {
                    Object o = cur.toObject();
                    if (o instanceof Class) {
                        classes.add((Class<?>) o);
                        if (classes.size() == hubCount) {
                            return classes;
                        }
                    }
                    cur = LayoutEncoding.getImageHeapObjectEnd(o);
                }
            }
        }

        throw VMError.shouldNotReachHere("Found fewer DynamicHubs in the image heap than expected.");
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @Override
    public ObjectHeader getObjectHeader() {
        return objectHeader;
    }

    @Uninterruptible(reason = "Tear-down in progress.")
    @Override
    public boolean tearDown() {
        return ShenandoahLibrary.tearDown();
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void setAccessedFieldOffsets(byte[] fieldOffsets) {
        assert this.accessedFieldOffsets == null;
        this.accessedFieldOffsets = fieldOffsets;
    }

    @Fold
    static int getByteArrayBaseOffset() {
        return ObjectLayout.singleton().getArrayBaseOffset(JavaKind.Byte);
    }

    @Override
    public void prepareForSafepoint() {
        ShenandoahLibrary.prepareForSafepoint();
    }

    @Override
    public void endSafepoint() {
        ShenandoahLibrary.endSafepoint();
    }

    @Override
    public boolean isRuntimeStateTrimSupported(Mode mode) {
        return false; // TODO: can we implement this for Shenandoah?
    }

    @Override
    public void trimRuntimeState(Mode mode) {
        // TODO: can we implement this for Shenandoah?
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void validateInitState(ShenandoahInitState state) {
        VMError.guarantee(ShenandoahConstants.tlabTopOffset() == state.tlabTopOffset(), "Failed while validating the Shenandoah state: tlabTopOffset");
        VMError.guarantee(ShenandoahConstants.tlabEndOffset() == state.tlabEndOffset(), "Failed while validating the Shenandoah state: tlabEndOffset");
        VMError.guarantee(ShenandoahConstants.dirtyCardValue() == state.dirtyCardValue(), "Failed while validating the Shenandoah state: dirtyCardValue");
        VMError.guarantee(ShenandoahConstants.cardTableShift() == state.cardTableShift(), "Failed while validating the Shenandoah state: cardTableShift");
        VMError.guarantee(ShenandoahConstants.cardTableOffsetRel() == state.cardTableOffset(), "Failed while validating the Shenandoah state: cardTableOffset");
        VMError.guarantee(ShenandoahConstants.logOfHeapRegionGrainBytes() == state.logOfHeapRegionGrainBytes(), "Failed while validating the Shenandoah state: logOfHeapRegionGrainBytes");
        VMError.guarantee(ShenandoahConstants.barrierAndAllocationDataSize() == state.barrierAndAllocationDataSize(), "Failed while validating the Shenandoah state: barrierAndAllocationDataSize");
        VMError.guarantee(ShenandoahConstants.javaThreadSize() == state.javaThreadSize(), "Failed while validating the Shenandoah state: javaThreadSize");
        VMError.guarantee(ShenandoahConstants.satbIndexOffsetRel() == state.satbIndexOffset(), "Failed while validating the Shenandoah state: satbIndexOffset");
        VMError.guarantee(ShenandoahConstants.satbBufferOffsetRel() == state.satbBufferOffset(), "Failed while validating the Shenandoah state: satbBufferOffset");
        VMError.guarantee(ShenandoahConstants.markOffset() == state.markOffset(), "Failed while validating the Shenandoah state: markOffset");
        VMError.guarantee(ShenandoahConstants.gcStateOffsetRel() == state.gcStateOffset(), "Failed while validating the Shenandoah state: gcStateOffset");
        VMError.guarantee(ShenandoahConstants.csetMapOffsetRel() == state.csetMapOffset(), "Failed while validating the Shenandoah state: csetMapOffset");
        VMError.guarantee(SizeOf.get(NativeGCVMOperationData.class) <= state.vmOperationDataSize(), "Failed while validating the Shenandoah state: vmOperationDataSize");
        VMError.guarantee(SizeOf.get(NativeGCVMOperationWrapperData.class) <= state.vmOperationWrapperDataSize(), "Failed while validating the Shenandoah state: vmOperationWrapperDataSize");
    }

    @Uninterruptible(reason = "Called during startup.")
    @Override
    public void attachThread(IsolateThread isolateThread) {
        if (isInitialized) {
            ShenandoahLibrary.attachThread(isolateThread);
        } else {
            /* The thread gets attached as a side effect of the initialization. */
            initialize(isolateThread);
        }
    }

    @Override
    @Uninterruptible(reason = "Thread is detaching and holds the ThreadsLock with exclusive write access.")
    public void detachThread(IsolateThread isolateThread) {
        ShenandoahLibrary.detachThread(isolateThread);
    }

    @Override
    public void doReferenceHandling() {
        /* Nothing to do, Shenandoah only supports a dedicated reference handler thread. */
    }

    @Override
    public boolean hasReferencePendingList() {
        return ShenandoahLibrary.hasReferencePendingList();
    }

    @Override
    public void waitForReferencePendingList() throws InterruptedException {
        /*
         * The order is crucial here to prevent transient issues. First, we call into C++ to get the
         * current wakeup count, then we check if the thread was interrupted. This ensures that the
         * C++ code is able to properly detect the case where the thread is interrupted right before
         * blocking in ShenandoahLibrary.waitForReferencePendingList().
         */
        long initialWakeupCount = ShenandoahLibrary.getReferencePendingListWakeupCount();

        /* Throw an InterruptedException if the thread is interrupted before or after waiting. */
        if (Thread.interrupted() || !waitForPendingReferenceList(initialWakeupCount) && Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    private static boolean waitForPendingReferenceList(long initialWakeupCount) {
        Thread currentThread = Thread.currentThread();
        int oldThreadStatus = PlatformThreads.getThreadStatus(currentThread);
        PlatformThreads.setThreadStatus(currentThread, ThreadStatus.PARKED);
        try {
            return ShenandoahLibrary.waitForReferencePendingList(initialWakeupCount);
        } finally {
            PlatformThreads.setThreadStatus(currentThread, oldThreadStatus);
        }
    }

    @Override
    public void wakeUpReferencePendingListWaiters() {
        ShenandoahLibrary.wakeUpReferencePendingListWaiters();
    }

    @Override
    @Uninterruptible(reason = "Prevent stack overflow exceptions and recurring callback execution.", calleeMustBe = false)
    public Reference<?> getAndClearReferencePendingList() {
        Word result = ShenandoahLibrary.getAndClearReferencePendingList();
        return (Reference<?>) result.toObject();
    }

    @Override
    public boolean printLocationInfo(Log log, UnsignedWord value, boolean allowJavaHeapAccess, boolean allowUnsafeOperations) {
        Pointer heapBase = KnownIntrinsics.heapBase();
        if (value.equal(heapBase)) {
            log.string("is the heap base");
            return true;
        } else if (value.aboveThan(heapBase) && value.belowThan(heapBase.add(getNullRegionSize()))) {
            log.string("points into the protected null region after the heap base");
            return true;
        }

        if (objectHeader.isEncodedObjectHeader((Word) value)) {
            log.string("is the encoded object header for an object of type ");
            DynamicHub hub = objectHeader.dynamicHubFromObjectHeader((Word) value);
            log.string(hub.getName());
            return true;
        }

        Pointer ptr = (Pointer) value;
        if (printHeapLocationInfo(log, ptr)) {
            if (allowJavaHeapAccess && objectHeader.pointsToObjectHeader(ptr)) {
                log.indent(true);
                SubstrateDiagnostics.printObjectInfo(log, ptr.toObject());
                log.redent(false);
            }
            return true;
        }

        return printGCInternalLocationInfo(log, ptr);
    }

    private static boolean printHeapLocationInfo(Log log, Pointer ptr) {
        ShenandoahCommittedMemoryProvider memoryProvider = ImageSingletons.lookup(ShenandoahCommittedMemoryProvider.class);
        ShenandoahRegionInfo r = UnsafeStackValue.get(ShenandoahRegionInfo.class);
        for (int i = 0; i < memoryProvider.getMaxRegions(); i++) {
            if (ShenandoahLibrary.getRegionInfo(i, r)) {
                if (ptr.aboveOrEqual(r.bottom())) {
                    if (ptr.belowThan(r.top())) {
                        log.string("points into region ").signed(i).string(" (").string(ShenandoahRegionType.toString(r.regionType())).string(")");
                        return true;
                    } else if (ptr.belowThan(r.end())) {
                        log.string("points into unused space of region ").signed(i).string(" (").string(ShenandoahRegionType.toString(r.regionType())).string(")");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean printGCInternalLocationInfo(Log log, Pointer ptr) {
        ShenandoahInternalState state = UnsafeStackValue.get(ShenandoahInternalState.class);
        fillGCInternalState(state);

        throw VMError.shouldNotReachHere("Unimplemented: check if this is a GC-internal location and print some debugging output in that case");
    }

    @Override
    public void optionValueChanged(NotifyGCRuntimeOptionKey<?> key) {
        /*
         * There is no need to inform Shenandoah about options that can only be set during isolate
         * startup.
         */
        if (!SubstrateUtil.HOSTED && !key.isIsolateCreationOnly()) {
            assert isInImageHeap(key.getName());
            Word optionName = Word.objectToUntrackedWord(key.getName());
            long value = convertOptionValueToLong(key);
            ShenandoahLibrary.updateOptionValue(optionName, value);
        }
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public long getThreadAllocatedMemory(IsolateThread thread) {
        return ShenandoahLibrary.getThreadAllocatedMemory(thread);
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnsignedWord getUsedMemoryAfterLastGC() {
        return ShenandoahLibrary.getUsedMemoryAfterLastGC();
    }

    @Override
    @Uninterruptible(reason = "Ensure that no GC can occur between modification of the object and this call.", callerMustBe = true)
    public void dirtyAllReferencesOf(Object obj) {
        if (obj == null) {
            return;
        }

        VMError.guarantee(obj instanceof StoredContinuation);
        ShenandoahLibrary.dirtyAllReferencesOf(Word.objectToUntrackedWord(obj));
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void keepReferentAlive(Object referent) {
        if (referent == null) {
            return;
        }
        // Keep the just-read referent alive during concurrent marking (SATB keep-alive). The stub
        // (svm_gc_pre_write_barrier -> satb_enqueue) itself checks whether marking is active, so it
        // is a no-op outside of marking.
        ShenandoahLibrary.preWriteBarrierStub(Word.objectToUntrackedWord(referent));
    }

    @Override
    @Uninterruptible(reason = "Must be atomic with regard to garbage collection.")
    public Object resolveGCPointer(Object obj) {
        /*
         * Resolve a (possibly from-space) reference to its canonical to-space location WITHOUT
         * keeping it alive: pure mark-word forwarding-pointer decode, mirroring the C++
         * ShenandoahForwarding::get_forwardee_raw_unchecked. Needed for identity comparisons on
         * barrier-less reads (Reference.refersTo): during concurrent evacuation the raw referent
         * field may still hold the from-space copy while the caller's reference has already been
         * healed to to-space; comparing the raw pointers would yield a false negative (observed as
         * ThreadLocalMap.getEntry missing a live entry -> ThreadLocal.get() == null in jenetics).
         */
        if (obj == null) {
            return null;
        }
        Pointer p = Word.objectToUntrackedWord(obj);
        Word mark = p.readWord(ShenandoahConstants.markOffset());
        if (mark.and(ShenandoahConstants.MARK_FORWARDED_MASK).notEqual(0)) {
            Pointer fwd = (Pointer) mark.and(Word.signed(~(long) ShenandoahConstants.MARK_FORWARDED_MASK));
            if (fwd.isNonNull()) {
                return fwd.toObjectNonNull();
            }
        }
        return obj;
    }

    @Override
    public long getMillisSinceLastWholeHeapExamined() {
        return ShenandoahLibrary.getMillisSinceLastWholeHeapExamined();
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public long getIdentityHashSalt(Object obj) {
        ReplacementsUtil.staticAssert(false, "identity hash codes are never computed from addresses");
        return 0;
    }

    private static long convertOptionValueToLong(RuntimeOptionKey<?> key) {
        Class<?> valueType = key.getDescriptor().getOptionValueType();
        if (valueType == Boolean.class) {
            return ((Boolean) key.getValue()) ? 1L : 0L;
        } else if (valueType == Integer.class || valueType == Long.class) {
            return ((Number) key.getValue()).longValue();
        } else {
            throw VMError.shouldNotReachHere("Option " + key.getName() + " has an unexpected type: " + valueType);
        }
    }

    private static void fillGCInternalState(ShenandoahInternalState state) {
        int size = SizeOf.get(ShenandoahInternalState.class);
        UnmanagedMemoryUtil.fill((Pointer) state, Word.unsigned(size), (byte) 0);
        ShenandoahLibrary.getGCInternalState(state);
    }

    @Override
    @Uninterruptible(reason = "Called during early startup.")
    public boolean verifyImageHeapMapping() {
        /* Read & write some data at the beginning and end of each open region. */
        for (int region = imageHeapInfo.getNumClosedRegions(); region < imageHeapInfo.getNumRegions(); region++) {
            Pointer begin = imageHeapInfo.getRegionStart(region);
            Pointer end = imageHeapInfo.getRegionTop(region).subtract(1);

            byte val = begin.readByte(0);
            begin.writeByte(0, val);

            val = end.readByte(0);
            end.writeByte(0, val);
        }
        return true;
    }

    public static final class DumpHeapSettingsAndGCInternalState extends DiagnosticThunk {
        @Override
        public int maxInvocationCount() {
            return 1;
        }

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while printing diagnostics.")
        public void printDiagnostics(Log log, ErrorContext context, int maxDiagnosticLevel, int invocationCount) {
            log.string("Heap settings and statistics:").indent(true);
            log.string("Reserved hub pointer bits: 0b").number(Heap.getHeap().getObjectHeader().getReservedHubBitsMask(), 2, false).newline();
            log.string("Region size: ").signed(ShenandoahRegionSize.getValue()).newline();
            log.string("Card table granularity: ").signed(ShenandoahConstants.cardSize()).newline();

            ShenandoahInternalState state = UnsafeStackValue.get(ShenandoahInternalState.class);
            fillGCInternalState(state);

            log.string("Full collections: ").unsigned(state.fullCollections()).newline();
            log.string("Total collections: ").unsigned(state.totalCollections()).newline();
            log.string("Card table: ").zhex(state.cardTableStart()).string(" - ").zhex(state.cardTableStart().add(state.cardTableSize()).subtract(1)).newline();
            log.indent(false);
        }
    }

    public static final class DumpRegionInformation extends DiagnosticThunk {
        private static final int MAX_REGIONS_TO_PRINT = 128 * 1024;

        @Override
        public int maxInvocationCount() {
            return 1;
        }

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while printing diagnostics.")
        public void printDiagnostics(Log log, ErrorContext context, int maxDiagnosticLevel, int invocationCount) {
            int maxRegions = ImageSingletons.lookup(ShenandoahCommittedMemoryProvider.class).getMaxRegions();
            ShenandoahRegionInfo regionInfo = UnsafeStackValue.get(ShenandoahRegionInfo.class);

            log.string("Heap regions:").indent(true);
            int printed = 0;
            for (int i = 0; i < maxRegions; i++) {
                if (printed >= MAX_REGIONS_TO_PRINT) {
                    log.string("... (truncated)").newline();
                    break;
                }

                if (ShenandoahLibrary.getRegionInfo(i, regionInfo)) {
                    printRegion(log, i, regionInfo);
                    printed++;
                }
            }
            log.indent(false);
        }

        @SuppressWarnings("unused")
        private static void printRegion(Log log, int regionIndex, ShenandoahRegionInfo r) {
            log = log.unsigned(regionIndex).string(" : bottom=").hex(r.bottom()).string(" top=").hex(r.end());
            log.string(" end=").hex(r.end()).string(" type=").unsigned(r.regionType()).newline();
        }
    }

    public static final class DumpCurrentGCThreadName extends DiagnosticThunk {
        @Override
        public int maxInvocationCount() {
            return 1;
        }

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while printing diagnostics.")
        public void printDiagnostics(Log log, ErrorContext context, int maxDiagnosticLevel, int invocationCount) {
            if (SubstrateDiagnostics.isThreadOnlyAttachedForCrashHandler(CurrentIsolate.getCurrentThread())) {
                // The failing thread is an unattached thread, so it might be a GC thread.
                CCharPointer name = ShenandoahLibrary.getCurrentThreadName();
                if (name.isNonNull()) {
                    log.string("Internal name of crashing thread: ").string(name).newline();
                }
            }
        }
    }
}

@TargetClass(value = Runtime.class, onlyWith = UseShenandoahGC.class)
@SuppressWarnings({"static-method"})
final class Target_java_lang_Runtime {
    @Substitute
    private long freeMemory() {
        return ShenandoahLibrary.getFreeMemory();
    }

    @Substitute
    private long totalMemory() {
        return ShenandoahLibrary.getTotalMemory();
    }

    @Substitute
    private long maxMemory() {
        return ShenandoahLibrary.getMaxMemory();
    }

    @Substitute
    private void gc() {
        ShenandoahLibrary.collect(GCCause.JavaLangSystemGC.getId());
    }
}
