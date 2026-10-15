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

import com.oracle.svm.core.invoke.Target_java_lang_invoke_MemberName;
import com.oracle.svm.core.methodhandles.MethodHandleInterpreterUtils;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

/**
 * Reads the direct call entry of the method that a {@code MethodHandle.invokeBasic},
 * {@code linkToStatic} or {@code linkToSpecial} call in image code links to (elide-dev/graal#112).
 * The signature-polymorphic wrappers of these intrinsics (see
 * {@code PolymorphicSignatureWrapperMethod}) call the entry directly when there is one, with the
 * arguments they were given, instead of boxing them for {@link CremaSupportImpl}. The entry is
 * published by Ristretto for the methods it compiles, and by
 * {@link InterpreterToVM#publishImageMethodDirectCallEntry} for image methods.
 * <p>
 * The readers do not reach a safepoint: invalidated code withdraws its entry at a safepoint, so
 * the caller can call an entry it has read before it reaches one.
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
