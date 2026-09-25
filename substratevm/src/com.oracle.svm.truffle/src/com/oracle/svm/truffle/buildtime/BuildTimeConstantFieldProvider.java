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
package com.oracle.svm.truffle.buildtime;

import com.oracle.svm.shared.util.VMError;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.core.common.spi.ConstantFieldProvider;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * The constant field provider of build-time partial evaluation: that of the Truffle partial
 * evaluator, except that the exception profile of every call target is generic.
 * <p>
 * Build-time partial evaluation folds the state of the targets that AOT tier code calls as it is
 * when the image is built. The exception profile of a target that was never executed is still
 * uninitialized, and a caller's code that folded it transfers to the interpreter and invalidates
 * itself on the first exception the target throws: that would permanently unlink the AOT tier code
 * of the caller. The profile only lets a caller narrow the type of the exceptions it rethrows, so
 * treating it as generic is always correct. (The shadows' own profiles start generic anyway, see
 * {@code OptimizedCallTarget.createAOTShadow}.)
 */
final class BuildTimeConstantFieldProvider implements ConstantFieldProvider {
    private final ConstantFieldProvider delegate;
    private final ResolvedJavaField exceptionProfile;
    private final JavaConstant generic;

    BuildTimeConstantFieldProvider(ConstantFieldProvider delegate, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
        this.delegate = delegate;
        try {
            this.exceptionProfile = metaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("exceptionProfile"));
            this.generic = constantReflection.readFieldValue(metaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("PROFILE_GENERIC")), null);
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
        VMError.guarantee(generic != null && generic.getJavaKind().getStackKind() == JavaKind.Int, "unexpected value of PROFILE_GENERIC: %s", generic);
    }

    @Override
    public <T> T readConstantField(ResolvedJavaField field, ConstantFieldTool<T> tool) {
        if (field.equals(exceptionProfile) && !tool.getReceiver().isNull()) {
            return tool.foldConstant(generic);
        }
        return delegate.readConstantField(field, tool);
    }

    @Override
    public boolean maybeFinal(ResolvedJavaField field) {
        return delegate.maybeFinal(field);
    }

    @Override
    public boolean isTrustedFinal(CanonicalizerTool tool, ResolvedJavaField field) {
        return delegate.isTrustedFinal(tool, field);
    }
}
