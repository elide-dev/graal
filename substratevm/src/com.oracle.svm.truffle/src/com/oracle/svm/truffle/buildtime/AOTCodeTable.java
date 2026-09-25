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

import org.graalvm.nativeimage.c.function.CodePointer;

import com.oracle.svm.core.deopt.ImageCodeInstalledCodeLookup;
import com.oracle.svm.core.deopt.SubstrateInstalledCode;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.SingleLayer;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.InitialLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;

import jdk.internal.misc.Unsafe;

/**
 * Resolves frames of AOT tier methods to their {@link AOTInstalledCode}, for
 * {@code Deoptimizer.invalidateMethodOfFrame}. The codes are sorted by entry address when the image
 * code is laid out; the order is kept at run time because all entry points are relocated relative
 * to the same code base.
 * <p>
 * Also the image singleton that marks an image as containing the AOT tier
 * ({@link AOTInstalledCode#isAOTTierInImage()}).
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = SingleLayer.class, layeredInstallationKind = InitialLayerOnly.class)
final class AOTCodeTable implements ImageCodeInstalledCodeLookup {
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private AOTInstalledCode[] codes;

    /*
     * The fields that the code folded and that patching the pre-initialized context can change: the
     * folds of codes[i] are foldStarts[i] (inclusive) to foldStarts[i + 1] (exclusive). Each is a
     * field of foldReceivers[j] at foldOffsets[j], of the kind foldKinds[j] (a JavaKind type
     * character), with the value foldReferences[j] or the raw primitive value foldBits[j] when it
     * was folded, and the name foldNames[j].
     */
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private int[] foldStarts;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private Object[] foldReceivers;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private long[] foldOffsets;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private char[] foldKinds;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private Object[] foldReferences;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private long[] foldBits;
    @UnknownObjectField(availability = BuildPhaseProvider.AfterCompilation.class) //
    private String[] foldNames;

    /** The kind of a fold of the context object of a language context (foldReceivers[j]). */
    static final char LANGUAGE_CONTEXT_OBJECT = 'X';

    /** Whether {@link #linkAfterPatch()} ran. Guarded by this table. */
    private boolean linkedAfterPatch;

    void setCodes(AOTInstalledCode[] sortedCodes) {
        this.codes = sortedCodes;
    }

    void setFoldedState(int[] starts, Object[] receivers, long[] offsets, char[] kinds, Object[] references, long[] bits, String[] names) {
        this.foldStarts = starts;
        this.foldReceivers = receivers;
        this.foldOffsets = offsets;
        this.foldKinds = kinds;
        this.foldReferences = references;
        this.foldBits = bits;
        this.foldNames = names;
    }

    /**
     * Links the codes that folded fields that patching the pre-initialized context can change, if
     * patching left all of them unchanged, and unlinks the others: called once the context is
     * patched, before it is used. Only the first call does anything: there is one pre-initialized
     * context per image run.
     */
    synchronized void linkAfterPatch() {
        if (linkedAfterPatch) {
            return;
        }
        linkedAfterPatch = true;
        for (int i = 0; i < codes.length; i++) {
            int start = foldStarts[i];
            int end = foldStarts[i + 1];
            if (start == end) {
                continue;
            }
            String changed = null;
            for (int j = start; j < end && changed == null; j++) {
                if (!isUnchanged(j)) {
                    changed = foldNames[j];
                }
            }
            if (changed == null) {
                codes[i].linkAfterPatch(end - start);
            } else {
                codes[i].unlinkAfterPatch("state folded at build time changed when the context was patched: " + changed);
            }
        }
    }

    private boolean isUnchanged(int j) {
        Object receiver = foldReceivers[j];
        long offset = foldOffsets[j];
        long bits = foldBits[j];
        Unsafe unsafe = Unsafe.getUnsafe();
        return switch (foldKinds[j]) {
            case 'Z' -> unsafe.getBooleanVolatile(receiver, offset) == (bits != 0);
            case 'B' -> unsafe.getByteVolatile(receiver, offset) == (byte) bits;
            case 'S' -> unsafe.getShortVolatile(receiver, offset) == (short) bits;
            case 'C' -> unsafe.getCharVolatile(receiver, offset) == (char) bits;
            case 'I', 'F' -> unsafe.getIntVolatile(receiver, offset) == (int) bits;
            case 'J', 'D' -> unsafe.getLongVolatile(receiver, offset) == bits;
            case LANGUAGE_CONTEXT_OBJECT -> BuildTimeCompilationSupport.getLanguageContextObject(receiver) == foldReferences[j];
            default -> unsafe.getReferenceVolatile(receiver, offset) == foldReferences[j];
        };
    }

    @Override
    public SubstrateInstalledCode lookup(CodePointer ip) {
        AOTInstalledCode[] table = codes;
        long pc = ip.rawValue();
        int lo = 0;
        int hi = table.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            AOTInstalledCode code = table[mid];
            long start = code.entry.entryPoint.rawValue();
            if (Long.compareUnsigned(pc, start) < 0) {
                hi = mid - 1;
            } else if (Long.compareUnsigned(pc, start + code.entry.codeSize) >= 0) {
                lo = mid + 1;
            } else {
                return code;
            }
        }
        return null;
    }
}
