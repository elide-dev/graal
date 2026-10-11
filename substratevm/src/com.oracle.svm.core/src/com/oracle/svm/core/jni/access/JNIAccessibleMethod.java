/*
 * Copyright (c) 2017, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jni.access;

import java.lang.reflect.Modifier;

import org.graalvm.collections.EconomicSet;
import org.graalvm.nativeimage.Platform.HOSTED_ONLY;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordBase;

import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.BuildPhaseProvider.ReadyForCompilation;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.code.ImageCodeInfo;
import com.oracle.svm.core.code.RuntimeMetadataDecoderImpl;
import com.oracle.svm.core.graal.nodes.LoadMethodByIndexNode;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.meta.MethodRef;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.core.jni.CallVariant;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.shared.util.ReflectionUtil;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * Information on a method that can be looked up and called via JNI.
 *
 * <p>
 * With {@linkplain #storesCodeOffsets() relative code pointers}, the entry points below are stored
 * in the image heap as offsets from the code base, so the dynamic linker has nothing to patch: an
 * image with every preserved method registered for JNI otherwise carries hundreds of thousands of
 * load-time relocations here, one per entry point. The offsets are never rewritten: method IDs are
 * shared by all isolates of an image (libgraal runs one per compiler thread), and each isolate has
 * its own copy of the image heap. The getters below add the code base. The JNI call trampolines,
 * which jump through the variant wrapper fields before entering the isolate, add the code start
 * that they load through {@link #codeInfo}.
 */
public final class JNIAccessibleMethod extends JNIAccessibleMember implements PreservableJNIElement {
    public static final int VTABLE_INDEX_STATICALLY_BOUND_METHOD = -1;
    public static final int VTABLE_INDEX_NOT_YET_COMPUTED = -2;
    public static final int INTERFACE_TYPEID_CLASS_TABLE = -1;
    public static final int INTERFACE_TYPEID_NOT_YET_COMPUTED = -2;
    public static final int INTERFACE_TYPEID_UNNEEDED = -3;
    public static final int NEW_OBJECT_TARGET_INVALID_FOR_ABSTRACT_TYPE = -1;

    public static JNIAccessibleMethod negativeMethodQuery(JNIAccessibleClass jniClass) {
        return new JNIAccessibleMethod(jniClass, RuntimeMetadataDecoderImpl.NEGATIVE_FLAG_MASK, false);
    }

    @Platforms(HOSTED_ONLY.class)
    public static ResolvedJavaField getCallVariantWrapperField(MetaAccessProvider metaAccess, CallVariant variant, boolean nonVirtual) {
        StringBuilder name = new StringBuilder(32);
        if (variant == CallVariant.VARARGS) {
            name.append("varargs");
        } else if (variant == CallVariant.ARRAY) {
            name.append("array");
        } else if (variant == CallVariant.VA_LIST) {
            name.append("valist");
        } else {
            throw VMError.shouldNotReachHereUnexpectedInput(variant); // ExcludeFromJacocoGeneratedReport
        }
        if (nonVirtual) {
            name.append("Nonvirtual");
        }
        name.append("Wrapper");
        return metaAccess.lookupJavaField(ReflectionUtil.lookupField(JNIAccessibleMethod.class, name.toString()));
    }

    private int modifiers;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private int vtableIndex = VTABLE_INDEX_NOT_YET_COMPUTED;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private int interfaceTypeID = INTERFACE_TYPEID_NOT_YET_COMPUTED;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef nonvirtualTarget;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private WordBase newObjectTarget; // for constructors
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef callWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef varargsWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef arrayWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef valistWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef varargsNonvirtualWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef arrayNonvirtualWrapper;
    @UnknownPrimitiveField(availability = ReadyForCompilation.class)//
    private MethodRef valistNonvirtualWrapper;
    /**
     * The code info whose code start the entry points are relative to, read only by the JNI call
     * trampolines; null without {@linkplain #storesCodeOffsets() code offsets}.
     */
    @UnknownObjectField(availability = ReadyForCompilation.class, canBeNull = true)//
    private ImageCodeInfo codeInfo;

    /**
     * Whether entry points are stored as offsets from the code base. Not with layered images, where
     * the code base covers only the initial layer's code section. Both conditions fold.
     */
    @Fold
    public static boolean storesCodeOffsets() {
        return SubstrateOptions.useRelativeCodePointers() && !ImageLayerBuildingSupport.buildingImageLayer();
    }

    @Platforms(HOSTED_ONLY.class)
    public JNIAccessibleMethod(JNIAccessibleClass declaringClass, int modifiers, boolean preserved) {
        super(declaringClass);
        assert (modifiers & RuntimeMetadataDecoderImpl.PRESERVED_FLAG_MASK) == 0;
        this.modifiers = modifiers | (preserved ? RuntimeMetadataDecoderImpl.PRESERVED_FLAG_MASK : 0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isNegative() {
        return (modifiers & RuntimeMetadataDecoderImpl.NEGATIVE_FLAG_MASK) != 0;
    }

    @AlwaysInline("Work around an issue with the LLVM backend with which the return value was accessed incorrectly.")
    @Uninterruptible(reason = "Must not throw any exceptions.", callerMustBe = true)
    CodePointer getCallWrapperAddress() {
        return (CodePointer) toAddress(callWrapper);
    }

    @AlwaysInline("Work around an issue with the LLVM backend with which the return value was accessed incorrectly.")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    CodePointer getJavaCallAddress(Object instance, boolean nonVirtual) {
        if (!nonVirtual) {
            assert vtableIndex != JNIAccessibleMethod.VTABLE_INDEX_NOT_YET_COMPUTED && interfaceTypeID != INTERFACE_TYPEID_NOT_YET_COMPUTED;
            if (vtableIndex != JNIAccessibleMethod.VTABLE_INDEX_STATICALLY_BOUND_METHOD) {
                return LoadMethodByIndexNode.loadMethodByIndex(instance.getClass(), vtableIndex, interfaceTypeID);
            }
        }
        return (CodePointer) toAddress(nonvirtualTarget);
    }

    /** Null if this is not a constructor; callers use that to distinguish NewObject calls. */
    PointerBase getNewObjectAddress() {
        long value = newObjectTarget.rawValue();
        if (value == 0 || value == NEW_OBJECT_TARGET_INVALID_FOR_ABSTRACT_TYPE) {
            return (PointerBase) newObjectTarget;
        }
        return toAddress(newObjectTarget);
    }

    /** Turns a stored entry point into an address; see {@link #storesCodeOffsets()}. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer toAddress(WordBase entryPoint) {
        Pointer p = (Pointer) entryPoint;
        return storesCodeOffsets() ? p.add(KnownIntrinsics.codeBase()) : p;
    }

    public Class<?> getDeclaringClassObject() {
        return getDeclaringClass().getClassObject();
    }

    boolean isPublic() {
        return Modifier.isPublic(modifiers);
    }

    public boolean isNative() {
        return Modifier.isNative(modifiers);
    }

    boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    public int getModifiers() {
        return modifiers;
    }

    @Platforms(HOSTED_ONLY.class)
    public void finishBeforeCompilation(EconomicSet<Class<?>> hidingSubclasses, int vtableIndexEntry, int interfaceTypeIDEntry, MethodRef nonvirtualEntry, WordBase newObjectEntry,
                    MethodRef callWrapperEntry, MethodRef varargs, MethodRef array, MethodRef valist, MethodRef varargsNonvirtual, MethodRef arrayNonvirtual,
                    MethodRef valistNonvirtual) {
        assert this.vtableIndex == VTABLE_INDEX_NOT_YET_COMPUTED && (vtableIndexEntry == VTABLE_INDEX_STATICALLY_BOUND_METHOD || vtableIndexEntry >= 0);
        assert this.interfaceTypeID == INTERFACE_TYPEID_NOT_YET_COMPUTED && interfaceTypeIDEntry != INTERFACE_TYPEID_NOT_YET_COMPUTED;

        this.vtableIndex = vtableIndexEntry;
        this.interfaceTypeID = interfaceTypeIDEntry;
        this.nonvirtualTarget = nonvirtualEntry;
        this.newObjectTarget = newObjectEntry;
        this.callWrapper = callWrapperEntry;
        this.varargsWrapper = varargs;
        this.arrayWrapper = array;
        this.valistWrapper = valist;
        this.varargsNonvirtualWrapper = varargsNonvirtual;
        this.arrayNonvirtualWrapper = arrayNonvirtual;
        this.valistNonvirtualWrapper = valistNonvirtual;
        setHidingSubclasses(hidingSubclasses);
    }

    /** @see #codeInfo */
    @Platforms(HOSTED_ONLY.class)
    public void setCodeInfo(ImageCodeInfo info) {
        assert storesCodeOffsets() && codeInfo == null;
        this.codeInfo = info;
    }

    /** The field through which the JNI call trampolines find the code start. */
    @Platforms(HOSTED_ONLY.class)
    public static ResolvedJavaField getCodeInfoField(MetaAccessProvider metaAccess) {
        return metaAccess.lookupJavaField(ReflectionUtil.lookupField(JNIAccessibleMethod.class, "codeInfo"));
    }

    @Override
    public boolean isPreserved() {
        return (modifiers & RuntimeMetadataDecoderImpl.PRESERVED_FLAG_MASK) != 0;
    }

    @Override
    public void setNotPreserved() {
        modifiers = modifiers & (~RuntimeMetadataDecoderImpl.PRESERVED_FLAG_MASK);
    }
}
