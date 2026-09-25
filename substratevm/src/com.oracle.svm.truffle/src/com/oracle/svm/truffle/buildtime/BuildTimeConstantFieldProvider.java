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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.oracle.svm.shared.util.VMError;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.core.common.spi.ConstantFieldProvider;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.options.OptionValues;
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
 * only ever sees patched. So a mutable instance field (not {@code final}) that patching can change
 * ({@link #patchState}) is handled apart:
 * <ul>
 * <li>A field of one of the {@linkplain #PATCHED_POLYGLOT_CLASSES polyglot classes} that patching
 * always updates, such as the environment of a language context, is not folded.</li>
 * <li>A field of a language class other than an AST node, and a single-context constant
 * ({@code WeakAssumedValue}, which patching updates to the same context), is folded and
 * {@linkplain #startRecording() recorded}. The code is linked at run time only if every recorded
 * field still has its folded value once the context is patched ({@code AOTCodeTable}). A stable
 * array such a field holds is folded as a plain constant, so that its elements are read when the
 * code runs.</li>
 * </ul>
 */
final class BuildTimeConstantFieldProvider implements ConstantFieldProvider {
    private final ConstantFieldProvider delegate;
    private final ResolvedJavaField exceptionProfile;
    private final ResolvedJavaField returnProfile;
    private final JavaConstant generic;
    private final boolean bound;
    private final MetaAccessProvider metaAccess;
    private final ResolvedJavaType nodeType;
    private final ResolvedJavaField weakAssumedValueProfile;

    /**
     * A folded field that patching can change: {@code field} of {@code receiver} was {@code value}.
     * Without a {@code field}, the context object of the language context {@code receiver} was
     * {@code value} ({@link #foldLanguageContextObject}).
     */
    record Fold(JavaConstant receiver, ResolvedJavaField field, JavaConstant value) {
    }

    /**
     * Folds the context object of the constant language context {@code languageContext}, and records
     * it, if the current thread records ({@link #startRecording()}). Returns {@code null} otherwise.
     * The object is read as {@code PolyglotLanguageContext.getContextImpl()} does, through the
     * environment ({@code Env.context}), with the constant reflection of the partial evaluation, so
     * that it is a constant of the analysis.
     */
    static JavaConstant foldLanguageContextObject(JavaConstant languageContext, MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
        List<Fold> folds = RECORDING.get();
        if (folds == null || languageContext.isNull()) {
            return null;
        }
        ResolvedJavaField envField;
        ResolvedJavaField contextField;
        ResolvedJavaField unsetField;
        try {
            envField = metaAccess.lookupJavaField(Class.forName("com.oracle.truffle.polyglot.PolyglotLanguageContext").getDeclaredField("env"));
            contextField = metaAccess.lookupJavaField(TruffleLanguage.Env.class.getDeclaredField("context"));
            unsetField = metaAccess.lookupJavaField(TruffleLanguage.Env.class.getDeclaredField("UNSET_CONTEXT"));
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
        JavaConstant env = constantReflection.readFieldValue(envField, languageContext);
        if (env == null || env.isNull()) {
            return null;
        }
        JavaConstant value = constantReflection.readFieldValue(contextField, env);
        if (value == null || value.isNull() || Boolean.TRUE.equals(constantReflection.constantEquals(value, constantReflection.readFieldValue(unsetField, null)))) {
            return null;
        }
        folds.add(new Fold(languageContext, null, value));
        return value;
    }

    /** The folds of the partial evaluation that the current thread runs, if it records them. */
    private static final ThreadLocal<List<Fold>> RECORDING = new ThreadLocal<>();

    /**
     * Records the patchable fields ({@link #patchState}) that the partial evaluation the current
     * thread runs next folds, until {@link #stopRecording()}. Without a recording, such fields are
     * not folded.
     */
    static void startRecording() {
        RECORDING.set(new ArrayList<>());
    }

    /** Stops recording ({@link #startRecording()}) and returns the recorded folds. */
    static List<Fold> stopRecording() {
        List<Fold> folds = RECORDING.get();
        RECORDING.remove();
        return folds == null ? List.of() : folds;
    }

    /** How patching a pre-initialized context can affect a field. */
    private enum PatchState {
        /** Patching never changes it. */
        UNCHANGED,
        /** Patching updates it: never folded. */
        UPDATED,
        /** Patching can change it: folded and recorded. */
        VERIFIED
    }

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
        try {
            this.weakAssumedValueProfile = metaAccess.lookupJavaField(Class.forName("com.oracle.truffle.polyglot.WeakAssumedValue").getDeclaredField("profile"));
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    @Override
    public <T> T readConstantField(ResolvedJavaField field, ConstantFieldTool<T> tool) {
        if (field.equals(exceptionProfile) && !tool.getReceiver().isNull()) {
            return tool.foldConstant(generic);
        }
        if (field.equals(returnProfile) && !tool.getReceiver().isNull()) {
            return tool.foldConstant(JavaConstant.NULL_POINTER);
        }
        if (bound && !field.isStatic() && !field.isFinal()) {
            switch (patchState(field, tool.getReceiver())) {
                case UPDATED:
                    return null;
                case VERIFIED:
                    return foldAndRecord(field, tool);
                default:
                    break;
            }
        }
        return delegate.readConstantField(field, tool);
    }

    private <T> T foldAndRecord(ResolvedJavaField field, ConstantFieldTool<T> tool) {
        List<Fold> folds = RECORDING.get();
        if (folds == null) {
            return null;
        }
        JavaConstant value = tool.readValue();
        if (value == null || (field.equals(weakAssumedValueProfile) && value.isNull())) {
            /* An unset single-context constant is set at run time: never speculate on it. */
            return null;
        }
        T result = delegate.readConstantField(field, new PlainConstantTool<>(tool));
        if (result != null) {
            folds.add(new Fold(tool.getReceiver(), field, value));
        }
        return result;
    }

    /** Folds stable arrays as plain constants, so that their elements are not folded. */
    private record PlainConstantTool<T>(ConstantFieldTool<T> tool) implements ConstantFieldTool<T> {
        @Override
        public OptionValues getOptions() {
            return tool.getOptions();
        }

        @Override
        public JavaConstant readValue() {
            return tool.readValue();
        }

        @Override
        public JavaConstant getReceiver() {
            return tool.getReceiver();
        }

        @Override
        public Object getReason() {
            return tool.getReason();
        }

        @Override
        public T foldConstant(JavaConstant ret) {
            return tool.foldConstant(ret);
        }

        @Override
        public T foldStableArray(JavaConstant ret, int stableDimensions, boolean isDefaultStable) {
            return tool.foldConstant(ret);
        }
    }

    /**
     * How patching a pre-initialized context can affect the mutable instance {@code field} of
     * {@code receiver}. Fields of AST nodes and of the Truffle framework are unchanged, except for
     * the {@link #PATCHED_POLYGLOT_CLASSES}, which patching updates, and the single-context
     * constants, which it can change. Fields of language classes can change.
     */
    private PatchState patchState(ResolvedJavaField field, JavaConstant receiver) {
        if (receiver == null || receiver.isNull()) {
            return PatchState.UNCHANGED;
        }
        ResolvedJavaType type = metaAccess.lookupJavaType(receiver);
        if (type == null || nodeType.isAssignableFrom(type)) {
            return PatchState.UNCHANGED;
        }
        if (field.equals(weakAssumedValueProfile)) {
            return PatchState.VERIFIED;
        }
        String holder = field.getDeclaringClass().toJavaName();
        if (holder.startsWith("com.oracle.truffle.polyglot.")) {
            return PATCHED_POLYGLOT_CLASSES.contains(holder) ? PatchState.UPDATED : PatchState.UNCHANGED;
        }
        boolean framework = holder.startsWith("com.oracle.truffle.api.") || holder.startsWith("com.oracle.truffle.runtime.") || holder.startsWith("com.oracle.truffle.object.");
        return framework ? PatchState.UNCHANGED : PatchState.VERIFIED;
    }

    /**
     * The classes of the polyglot implementation whose mutable state patching a context updates:
     * the context, its language contexts, configuration and threads, its limits, the engine (whose
     * options and host language are patched). The structure that context lookups fold, such as
     * sharing layers and language instances, is never patched.
     */
    private static final Set<String> PATCHED_POLYGLOT_CLASSES = Set.of(
                    "com.oracle.truffle.polyglot.PolyglotContextImpl",
                    "com.oracle.truffle.polyglot.PolyglotLanguageContext",
                    "com.oracle.truffle.polyglot.PolyglotContextConfig",
                    "com.oracle.truffle.polyglot.PolyglotThreadInfo",
                    "com.oracle.truffle.polyglot.PolyglotLimits",
                    "com.oracle.truffle.polyglot.PolyglotEngineImpl");

    @Override
    public boolean maybeFinal(ResolvedJavaField field) {
        return delegate.maybeFinal(field);
    }

    @Override
    public boolean isTrustedFinal(CanonicalizerTool tool, ResolvedJavaField field) {
        return delegate.isTrustedFinal(tool, field);
    }
}
