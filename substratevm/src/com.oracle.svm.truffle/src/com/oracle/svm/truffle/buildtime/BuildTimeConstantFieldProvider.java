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

import java.util.Set;

import com.oracle.svm.shared.util.VMError;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.core.common.spi.ConstantFieldProvider;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * The constant field provider of build-time partial evaluation: that of the Truffle partial
 * evaluator, except that the exception profile of every call target is generic and the return
 * profile of every call target is unset.
 * <p>
 * Build-time partial evaluation folds the state of the targets that AOT tier code calls as it is
 * when the image is built. The exception profile of a target that was never executed is still
 * uninitialized, and a caller's code that folded it transfers to the interpreter and invalidates
 * itself on the first exception the target throws: that would permanently unlink the AOT tier code
 * of the caller. The profile only lets a caller narrow the type of the exceptions it rethrows, so
 * treating it as generic is always correct. (The shadows' own profiles start generic anyway, see
 * {@code OptimizedCallTarget.createAOTShadow}.)
 * <p>
 * Likewise for the return profile of a target that was executed at build time: code that folded
 * its type depends on its assumption, and would be unlinked the first time the target returns
 * another type. An unset profile makes compiled code neither cast the result nor profile it, which
 * is always correct.
 * <p>
 * Without code sharing ({@link BuildTimeCompilationSupport#isBoundPreinitialization()}), the
 * compiled call targets belong to the pre-initialized context, whose language contexts are patched
 * at run time before any of its code runs. Patching can change context state, which the image's JIT
 * only ever sees patched. So a mutable instance field (not {@code final}) is not folded if patching
 * can change it ({@link #isPatchable}): a field of a language class other than an AST node, or of
 * one of the {@linkplain #PATCHED_POLYGLOT_CLASSES polyglot classes} that patching updates,
 * including the single-context constants ({@code WeakAssumedValue}). The compiled code then reads
 * the context and its state when it runs.
 */
final class BuildTimeConstantFieldProvider implements ConstantFieldProvider {
    private final ConstantFieldProvider delegate;
    private final ResolvedJavaField exceptionProfile;
    private final ResolvedJavaField returnProfile;
    private final JavaConstant generic;
    private final boolean bound;
    private final MetaAccessProvider metaAccess;
    private final ResolvedJavaType nodeType;

    BuildTimeConstantFieldProvider(ConstantFieldProvider delegate, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
        this.delegate = delegate;
        try {
            this.exceptionProfile = metaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("exceptionProfile"));
            this.returnProfile = metaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("returnProfile"));
            this.generic = constantReflection.readFieldValue(metaAccess.lookupJavaField(OptimizedCallTarget.class.getDeclaredField("PROFILE_GENERIC")), null);
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
        VMError.guarantee(generic != null && generic.getJavaKind().getStackKind() == JavaKind.Int, "unexpected value of PROFILE_GENERIC: %s", generic);
        this.bound = BuildTimeCompilationSupport.isBoundPreinitialization();
        this.metaAccess = metaAccess;
        this.nodeType = metaAccess.lookupJavaType(Node.class);
    }

    @Override
    public <T> T readConstantField(ResolvedJavaField field, ConstantFieldTool<T> tool) {
        if (field.equals(exceptionProfile) && !tool.getReceiver().isNull()) {
            return tool.foldConstant(generic);
        }
        if (field.equals(returnProfile) && !tool.getReceiver().isNull()) {
            return tool.foldConstant(JavaConstant.NULL_POINTER);
        }
        if (bound && !field.isStatic() && !field.isFinal() && isPatchable(field, tool.getReceiver())) {
            return null;
        }
        return delegate.readConstantField(field, tool);
    }

    /**
     * Whether patching a pre-initialized context can change {@code field} of {@code receiver}: its
     * holder is not an AST node, and is either a language class or one of the
     * {@link #PATCHED_POLYGLOT_CLASSES}.
     */
    private boolean isPatchable(ResolvedJavaField field, JavaConstant receiver) {
        if (receiver == null || receiver.isNull()) {
            return false;
        }
        ResolvedJavaType type = metaAccess.lookupJavaType(receiver);
        if (type == null || nodeType.isAssignableFrom(type)) {
            return false;
        }
        String holder = field.getDeclaringClass().toJavaName();
        if (holder.startsWith("com.oracle.truffle.polyglot.")) {
            return PATCHED_POLYGLOT_CLASSES.contains(holder);
        }
        return !(holder.startsWith("com.oracle.truffle.api.") || holder.startsWith("com.oracle.truffle.runtime.") || holder.startsWith("com.oracle.truffle.object."));
    }

    /**
     * The classes of the polyglot implementation whose mutable state patching a context updates:
     * the context, its language contexts, configuration and threads, its limits, the engine (whose
     * options and host language are patched), and the single-context constants. The structure that
     * context lookups fold, such as sharing layers and language instances, is never patched.
     */
    private static final Set<String> PATCHED_POLYGLOT_CLASSES = Set.of(
                    "com.oracle.truffle.polyglot.PolyglotContextImpl",
                    "com.oracle.truffle.polyglot.PolyglotLanguageContext",
                    "com.oracle.truffle.polyglot.PolyglotContextConfig",
                    "com.oracle.truffle.polyglot.PolyglotThreadInfo",
                    "com.oracle.truffle.polyglot.PolyglotLimits",
                    "com.oracle.truffle.polyglot.PolyglotEngineImpl",
                    "com.oracle.truffle.polyglot.WeakAssumedValue");

    @Override
    public boolean maybeFinal(ResolvedJavaField field) {
        return delegate.maybeFinal(field);
    }

    @Override
    public boolean isTrustedFinal(CanonicalizerTool tool, ResolvedJavaField field) {
        return delegate.isTrustedFinal(tool, field);
    }
}
