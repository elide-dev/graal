/*
 * Copyright (c) 2023, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.interpreter;

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.meta.KnownOffsets;
import com.oracle.svm.core.graal.snippets.OpenTypeWorldDispatchTableSnippets;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.invoke.Target_java_lang_invoke_MemberName;
import com.oracle.svm.core.methodhandles.MethodHandleInterpreterUtils;
import com.oracle.svm.guest.staging.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

/**
 * Reads the direct call entry of the method that a {@code MethodHandle.invokeBasic},
 * {@code linkToStatic}, {@code linkToSpecial}, {@code linkToVirtual} or {@code linkToInterface}
 * call in image code links to (elide-dev/graal#112).
 * The signature-polymorphic wrappers of these intrinsics (see
 * {@code PolymorphicSignatureWrapperMethod}) call the entry directly when there is one, with the
 * arguments they were given, instead of boxing them for {@link CremaSupportImpl}. The entry is
 * published by Ristretto for the methods it compiles, and by
 * {@link InterpreterToVM#publishImageMethodDirectCallEntry} for image methods.
 * <p>
 * The readers do not reach a safepoint: invalidated code withdraws its entry at a safepoint, so
 * the caller can call an entry it has read before it reaches one.
 * <p>
 * For {@code linkToVirtual} and {@code linkToInterface}, {@link #selectVirtualTarget} selects the
 * method from the receiver's class as the interpreter does, through the interpreter vtable of the
 * receiver's type. Ristretto code calls it as a foreign call ({@link #linkToVirtualEntryHolder}),
 * and calls the direct call entry of the selected method in the same way as for the other
 * intrinsics.
 */
public final class MethodHandleLinkage {
    private static final String REASON = "No safepoint between reading a direct call entry and calling it.";

    private MethodHandleLinkage() {
    }

    /** The entry of {@code linkToStatic(..., memberName)}, or null. */
    @Uninterruptible(reason = REASON)
    public static CodePointer linkToStaticEntry(Object memberName) {
        return Word.pointer(entryOf(memberName));
    }

    /**
     * The entry of {@code linkToSpecial(receiver, ..., memberName)}, or null. A null receiver takes
     * the slow path, which throws the {@link NullPointerException}.
     */
    @Uninterruptible(reason = REASON)
    public static CodePointer linkToSpecialEntry(Object receiver, Object memberName) {
        return Word.pointer(receiver == null ? 0 : entryOf(memberName));
    }

    /** The entry of {@code methodHandle.invokeBasic(...)}, or null. */
    @Uninterruptible(reason = REASON)
    public static CodePointer invokeBasicEntry(Object methodHandle) {
        return Word.pointer(methodHandle == null ? 0 : entryOf(MethodHandleInterpreterUtils.peekVMEntry(methodHandle)));
    }

    /**
     * The entry of {@code linkToVirtual(receiver, ..., memberName)} or
     * {@code linkToInterface(receiver, ..., memberName)}, or null. A null receiver takes the slow
     * path, which throws the {@link NullPointerException}.
     */
    @Uninterruptible(reason = REASON)
    public static CodePointer linkToVirtualEntry(Object receiver, Object memberName) {
        InterpreterResolvedJavaMethod target = selectVirtualTarget(receiver, memberName);
        return Word.pointer(target == null ? 0 : target.getDirectCallEntry());
    }

    /**
     * Ristretto code calls this before a {@code linkToVirtual} or {@code linkToInterface} call, and
     * then the direct call entry of the method it returns, if that has one, or else the
     * interpreter's enter stub for the intrinsic. It returns {@code intrinsic}, which never has a
     * direct call entry, when the call must take the slow path. The caller reads the entry after
     * this returns, so a safepoint here could not make it call invalidated code, but this runs
     * without a frame state in the caller and must not reach one.
     */
    @Uninterruptible(reason = "Called from Ristretto code without a frame state.")
    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    public static Object linkToVirtualEntryHolder(Object receiver, Object memberName, Object intrinsic) {
        InterpreterResolvedJavaMethod target = selectVirtualTarget(receiver, memberName);
        return target == null ? intrinsic : target;
    }

    /**
     * Selects the method that {@code linkToVirtual(receiver, ..., memberName)} or
     * {@code linkToInterface(receiver, ..., memberName)} calls, as
     * {@code InterpreterToVM.resolveCallSiteTarget} does: through the interpreter vtable of the
     * receiver's type, at the dispatch index of the member name's method (relative to the itable of
     * its interface in open type world images), or the devirtualized target of a method without a
     * dispatch index. Returns null when the call must take the slow path: a null receiver, an
     * unresolved member name, a receiver that does not implement the method's interface, or
     * anything else the interpreter would report.
     */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static InterpreterResolvedJavaMethod selectVirtualTarget(Object receiver, Object memberName) {
        if (receiver == null || memberName == null) {
            return null;
        }
        Object resolved = SubstrateUtil.cast(memberName, Target_java_lang_invoke_MemberName.class).resolved;
        if (!(resolved instanceof InterpreterResolvedJavaMethod seed)) {
            return null;
        }
        /* Static methods and constructors have no dispatch index (VTBL_UNINITIALIZED). */
        int index = seed.getVTableIndex();
        if (index < 0) {
            if (index == InterpreterResolvedJavaMethod.VTBL_NO_DISPATCH) {
                return seed;
            } else if (index == InterpreterResolvedJavaMethod.VTBL_ONE_IMPL) {
                return seed.peekOneImplementation();
            }
            return null;
        }
        DynamicHub hub = DynamicHub.fromClass(receiver.getClass());
        if (hub.hubIsArray()) {
            /* As in the interpreter: arrays do not have an interpreter vtable. */
            return seed;
        }
        if (!(hub.getInterpreterType() instanceof InterpreterResolvedObjectType receiverType)) {
            return null;
        }
        InterpreterResolvedJavaMethod[] vtable = receiverType.getVtable();
        if (vtable == null) {
            return null;
        }
        int interfaceID = seed.getDeclaringClass().peekHub().getInterfaceID();
        if (!SubstrateOptions.useClosedTypeWorldHubLayout() && interfaceID != DynamicHub.NO_INTERFACE_ID) {
            long iTableStartingOffset = OpenTypeWorldDispatchTableSnippets.findITableStartingOffset(hub, interfaceID);
            if (iTableStartingOffset < 0) {
                return null;
            }
            KnownOffsets offsets = KnownOffsets.singleton();
            index += (int) ((iTableStartingOffset - offsets.getVTableBaseOffset()) / offsets.getVTableEntrySize());
        }
        if (index >= vtable.length) {
            return null;
        }
        InterpreterResolvedJavaMethod target = vtable[index];
        if (target == null || target.getSymbolicName() != seed.getSymbolicName() || target.getSymbolicSignature() != seed.getSymbolicSignature()) {
            return null;
        }
        return target;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static long entryOf(Object memberName) {
        if (memberName == null) {
            return 0;
        }
        Object resolved = SubstrateUtil.cast(memberName, Target_java_lang_invoke_MemberName.class).resolved;
        if (resolved instanceof InterpreterResolvedJavaMethod method) {
            return method.getDirectCallEntry();
        }
        return 0;
    }
}
