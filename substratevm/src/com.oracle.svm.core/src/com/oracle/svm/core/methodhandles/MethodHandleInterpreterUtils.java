/*
 * Copyright (c) 2020, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.methodhandles;

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.invoke.Target_java_lang_invoke_MemberName;
import com.oracle.svm.shared.util.VMError;

public final class MethodHandleInterpreterUtils {
    private MethodHandleInterpreterUtils() {
    }

    public static Target_java_lang_invoke_MemberName extractVMEntry(MethodHandle handle) {
        return extractVMEntry(SubstrateUtil.cast(handle, Target_java_lang_invoke_MethodHandle.class));
    }

    public static Target_java_lang_invoke_MemberName extractVMEntry(Target_java_lang_invoke_MethodHandle handle) {
        Target_java_lang_invoke_LambdaForm lform = handle.internalForm();
        if (lform.vmentry == null) {
            // if the form comes from the image, its entry might have been reset
            VMError.guarantee(Heap.getHeap().isInImageHeap(lform));
            lform.prepare();
            VMError.guarantee(lform.vmentry != null);
        }
        return lform.vmentry;
    }

    /**
     * Returns the member of a direct method handle, or null for any other handle. A direct method
     * handle ({@code DirectMethodHandle} and its nested subclasses for special, interface and
     * constructor calls) invokes its member through a {@code linkTo*} intrinsic, whatever
     * {@code LambdaForm} it carries. Only handles for invocable members are returned: field
     * accessors are also direct method handles, but go through different lambda forms.
     */
    public static Target_java_lang_invoke_MemberName getDirectMemberName(MethodHandle handle) {
        if (!Target_java_lang_invoke_DirectMethodHandle.class.isInstance(handle)) {
            return null;
        }
        Target_java_lang_invoke_MemberName member = SubstrateUtil.cast(handle, Target_java_lang_invoke_DirectMethodHandle.class).member;
        if (member == null || !member.isInvocable()) {
            return null;
        }
        return member;
    }

    /** Name prefix of the JDK's direct var handle classes, such as {@code VarHandleInts$FieldInstanceReadWrite}. */
    private static final String DIRECT_VAR_HANDLE_CLASS_PREFIX = "java.lang.invoke.VarHandle";

    /**
     * Returns the member name of the method that {@code handle}'s lambda form runs, or null when
     * the lambda form of a handle in the image heap has not been prepared yet (see
     * {@link #extractVMEntry}). Unlike that method, this never prepares the lambda form.
     */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static Target_java_lang_invoke_MemberName peekVMEntry(Object handle) {
        Target_java_lang_invoke_LambdaForm form = SubstrateUtil.cast(handle, Target_java_lang_invoke_MethodHandle.class).form;
        return form == null ? null : form.vmentry;
    }

    /**
     * Returns the member that {@code VarHandleGuards.guard_*} invokes through
     * {@code MethodHandle.linkToStatic} for {@code handle} and the access descriptor
     * {@code accessDescriptor} (the guard's appendix), or null if the guard would do anything else:
     * throw a {@code WrongMethodTypeException}, adapt the handle's type with {@code asType}, or
     * resolve the member first.
     * <p>
     * The checks are those of the guard: the handle is direct ({@code VarHandle.checkAccessModeThenIsDirect}
     * is not overridden: {@code IndirectVarHandle} and {@code LazyInitializingVarHandle} override it),
     * an exact handle has the call site's type, and the erased type of the access mode is the call
     * site's erased type. A guard of a call site that returns void also accepts the access mode's
     * type with a void return, and discards the member's result ({@code resultDiscarded}). A guard
     * that returns a reference casts the result to the call site's return type; such a call site
     * is only accepted when the cast cannot fail, that is when the handle's variable type is a
     * subtype of the call site's return type.
     */
    public static Target_java_lang_invoke_MemberName getVarHandleGuardMember(VarHandle handle, Object accessDescriptor, boolean resultDiscarded, boolean returnsReference) {
        if (!handle.getClass().getName().startsWith(DIRECT_VAR_HANDLE_CLASS_PREFIX) || !Target_java_lang_invoke_VarHandle_AccessDescriptor.class.isInstance(accessDescriptor)) {
            return null;
        }
        Target_java_lang_invoke_VarHandle_AccessDescriptor ad = SubstrateUtil.cast(accessDescriptor, Target_java_lang_invoke_VarHandle_AccessDescriptor.class);
        VarHandle.AccessMode accessMode = VarHandle.AccessMode.values()[ad.mode];
        if (handle.hasInvokeExactBehavior() && handle.accessModeType(accessMode) != ad.symbolicMethodTypeExact) {
            return null;
        }
        Target_java_lang_invoke_VarForm form = SubstrateUtil.cast(handle, Target_java_lang_invoke_VarHandle.class).vform;
        if (form == null || form.memberName_table == null) {
            return null;
        }
        MethodType erased = ad.symbolicMethodTypeErased;
        if (form.methodType_table[ad.type] != erased) {
            MethodType[] voidTypes = form.methodType_V_table;
            if (!resultDiscarded || voidTypes == null || voidTypes[ad.type] != erased) {
                return null;
            }
        }
        if (returnsReference && !ad.returnType.isAssignableFrom(handle.varType())) {
            return null;
        }
        Target_java_lang_invoke_MemberName member = form.memberName_table[ad.mode];
        if (member == null || !member.isInvocable()) {
            return null;
        }
        return member;
    }

    /**
     * Returns a new member name that is not resolved, for runtime-compiled code that needs one in
     * place of a missing lambda form entry (see {@code RistrettoMethodHandleLinkagePhase}).
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static Object newUnresolvedMemberName() {
        return ReflectionUtil.newInstance(ReflectionUtil.lookupConstructor(ReflectionUtil.lookupClass("java.lang.invoke.MemberName")));
    }
}
