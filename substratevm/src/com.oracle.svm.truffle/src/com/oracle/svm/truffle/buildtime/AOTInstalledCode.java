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

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.core.deopt.SubstrateSpeculationLog;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.shared.util.VMError;
import com.oracle.truffle.compiler.OptimizedAssumptionDependency;
import com.oracle.truffle.compiler.TruffleCompilable;
import com.oracle.truffle.runtime.AOTCode;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * The AOT tier code of one live call target: a method compiled into the image at build time
 * ({@link BuildTimeCompiledMethod}), entered from {@code SubstrateOptimizedCallTargetInstalledCode.doInvoke}
 * while it is linked ({@link OptimizedCallTarget#getAOTCode()}).
 * <p>
 * It is also the {@link SubstrateInstalledCode} of the frames of that method
 * ({@link AOTCodeTable}), so that an invalidating deoptimization in AOT code invalidates it, and
 * the {@link OptimizedAssumptionDependency} of the Truffle assumptions its compilation relied on,
 * registered at build time. Invalidation ({@link InvalidateAOTCodeOperation}) unlinks the code at
 * a safepoint and deoptimizes all its activations. Image code is never freed, so the code stays
 * {@linkplain #isAlive() alive}.
 */
public final class AOTInstalledCode implements AOTCode, SubstrateInstalledCode, OptimizedAssumptionDependency {
    final OptimizedCallTarget live;
    final AOTEntry entry;
    private volatile boolean unlinked;
    /**
     * Whether AOT code of {@link #live} was entered. Only ever set, under the lock of this code
     * ({@link #firstEnter()}), after the first entry was processed.
     */
    private boolean entered;

    /**
     * Whether this image contains the AOT tier, i.e., was built with
     * {@code -Dpolyglot.image-build-time.AOTCompile=true}. Folded when the image is built, so that
     * images without it do not contain the AOT tier dispatch.
     */
    @Fold
    public static boolean isAOTTierInImage() {
        return ImageSingletons.contains(AOTCodeTable.class);
    }

    AOTInstalledCode(OptimizedCallTarget live, AOTEntry entry) {
        this.live = live;
        this.entry = entry;
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public AOTEntry getEntry() {
        return entry;
    }

    /**
     * Records that the code was unlinked from its target. The target already cleared its
     * reference ({@link OptimizedCallTarget#unlinkAOT}); this only records the state, so no
     * safepoint is needed.
     */
    @Override
    public void unlink(CharSequence reason) {
        unlinked = true;
    }

    boolean isUnlinked() {
        return unlinked;
    }

    /**
     * Called first thing by the AOT tier code of {@link #live} (see
     * {@link BuildTimeCompiledMethod}). On the first entry, it initializes the live target, which
     * the interpreter would otherwise have done on its first call (the AOT tier runs instead), and
     * traces the entry if {@code engine.TraceAOT} is set.
     */
    @NeverInline("Called from AOT tier code, which is compiled without inlining.")
    static void enter(AOTInstalledCode code) {
        if (!code.entered) {
            code.firstEnter();
        }
    }

    /**
     * Only one of the threads that enter the code for the first time at once processes the entry,
     * so that it is traced once; the others wait until the live target is initialized.
     */
    private synchronized void firstEnter() {
        if (entered) {
            return;
        }
        live.ensureInitialized();
        entered = true;
        if (live.engine.traceAOT) {
            live.log("[aot] enter " + rootName(live));
        }
    }

    static String rootName(OptimizedCallTarget target) {
        String name = target.getRootNode().getName();
        return name == null ? String.valueOf(target.getRootNode()) : name;
    }

    @Override
    public String getName() {
        return "AOT " + live.getName();
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public long getEntryPoint() {
        return entry.entryPoint.rawValue();
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public long getAddress() {
        return entry.entryPoint.rawValue();
    }

    /**
     * Image code is never freed, and its activations can outlive an unlink, so an assumption never
     * drops this dependency ({@code OptimizedAssumption.removeDeadDependencies}).
     */
    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isAlive() {
        return true;
    }

    @Override
    public boolean isValid() {
        return !unlinked;
    }

    /**
     * Unlinks the code, so that the next call of the target runs in the interpreter, and
     * deoptimizes all its activations. Called by {@code Deoptimizer.invalidateMethodOfFrame} for an
     * invalidating deoptimization in the code.
     */
    @Override
    public void invalidate() {
        invalidate("invalidated");
    }

    /** A Truffle assumption the code relies on was invalidated: see {@link #invalidate()}. */
    @Override
    public void onAssumptionInvalidated(Object source, CharSequence reason) {
        invalidate(reason);
    }

    /**
     * Not gated on {@link #isValid()}: after the code was unlinked without deoptimization (by the
     * AOT tier threshold), activations of it can still be on a stack.
     */
    private void invalidate(CharSequence reason) {
        if (SubstrateUtil.HOSTED) {
            /* An assumption invalidated while the image is built: no VM operations, no activations. */
            TruffleBuildTimeCompilationFeature.dropAtBuildTime(this, reason);
            return;
        }
        InvalidateAOTCodeOperation operation = new InvalidateAOTCodeOperation(this, reason);
        operation.enqueue();
        if (operation.unlinked) {
            live.traceUnlinkAOT(reason);
        }
    }

    /** The live target: the target whose calls run this code. */
    @Override
    public TruffleCompilable getCompilable() {
        return live;
    }

    @Override
    public ResolvedJavaMethod getMethod() {
        return null;
    }

    @Override
    public void setAddress(long address, long entryPoint, ResolvedJavaMethod method) {
        throw VMError.shouldNotReachHere("AOT tier code is image code: it is never installed.");
    }

    @Override
    public void clearAddress() {
        throw VMError.shouldNotReachHere("AOT tier code is image code: it is never removed.");
    }

    @Override
    public void makeNonEntrant() {
        throw VMError.shouldNotReachHere("AOT tier code is image code: it is unlinked, never made non-entrant.");
    }

    @Override
    public void reprofile() {
    }

    @Override
    public void recordDeoptimization(DeoptimizationReason reason) {
    }

    /** AOT tier code is compiled without a speculation log. */
    @Override
    public SubstrateSpeculationLog getSpeculationLog() {
        return null;
    }

    @Override
    public void setCompilationId(CompilationIdentifier id) {
    }
}
