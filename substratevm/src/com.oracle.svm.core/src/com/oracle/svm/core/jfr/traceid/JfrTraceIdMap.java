/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.jfr.traceid;

import java.util.Arrays;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.shared.BuildPhaseProvider.ReadyForCompilation;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.PartiallyLayerAware;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.Duplicable;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.api.replacements.Fold;

/**
 * Map for storing trace ids. Initialized before compilation with static class count from analysis.
 *
 * Classes that are loaded at run time (see {@code RuntimeClassLoading}) get type ids above the
 * image-time maximum, so their trace ids live in a chunked extension that grows as type ids are
 * handed out. Chunks are never replaced once published and the outer array is copied on growth,
 * which keeps the uninterruptible readers free of locks: they only read a volatile reference and
 * then index into immutable-shaped arrays.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, layeredInstallationKind = Duplicable.class, other = PartiallyLayerAware.class)
public class JfrTraceIdMap {
    /** Trace id of a class that is not present in the map (TYPE_NONE in HotSpot's JFR). */
    static final long NO_ID = 0;

    private static final int RUNTIME_CHUNK_SHIFT = 10;
    private static final int RUNTIME_CHUNK_SIZE = 1 << RUNTIME_CHUNK_SHIFT;
    private static final int RUNTIME_CHUNK_MASK = RUNTIME_CHUNK_SIZE - 1;

    @UnknownObjectField(availability = ReadyForCompilation.class) private long[] traceIDs;
    /** The highest trace id assigned at image build time; run-time ids continue above it. */
    @UnknownPrimitiveField(availability = ReadyForCompilation.class) private long maxHostedTraceId;

    /** Trace ids of run-time loaded types, indexed by {@code index - traceIDs.length}. */
    private volatile long[][] runtimeChunks;

    @Platforms(Platform.HOSTED_ONLY.class)
    public JfrTraceIdMap() {
    }

    @Fold
    static JfrTraceIdMap singleton() {
        return ImageSingletons.lookup(JfrTraceIdMap.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void initialize(int size) {
        traceIDs = new long[size];
        Arrays.fill(traceIDs, -1);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    long getId(int index) {
        return traceIDs[index];
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    void setId(int index, long id) {
        assert traceIDs[index] == -1;
        traceIDs[index] = id;
        maxHostedTraceId = Math.max(maxHostedTraceId, JfrTraceId.fromRawTraceId(id));
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    void tag(int index, long value) {
        long id = traceIDs[index];
        assert id != -1;
        traceIDs[index] = id | value;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    long getId(Class<?> clazz) {
        int index = getIndex(clazz);
        long[] hostedIds = traceIDs;
        if (index < hostedIds.length) {
            long id = hostedIds[index];
            assert id != -1;
            return id;
        }
        long[] chunk = getRuntimeChunk(index - hostedIds.length);
        if (chunk == null) {
            return NO_ID;
        }
        return chunk[(index - hostedIds.length) & RUNTIME_CHUNK_MASK];
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void setId(Class<?> clazz, long id) {
        int index = getIndex(clazz);
        long[] hostedIds = traceIDs;
        if (index < hostedIds.length) {
            hostedIds[index] = id;
            return;
        }
        long[] chunk = getRuntimeChunk(index - hostedIds.length);
        if (chunk != null) {
            chunk[(index - hostedIds.length) & RUNTIME_CHUNK_MASK] = id;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private long[] getRuntimeChunk(int runtimeIndex) {
        long[][] chunks = runtimeChunks;
        int chunkIndex = runtimeIndex >>> RUNTIME_CHUNK_SHIFT;
        if (chunks == null || chunkIndex >= chunks.length) {
            return null;
        }
        return chunks[chunkIndex];
    }

    /**
     * Makes sure that the map has a slot for the given type id, which was just handed out for a
     * type that is loaded at run time, and that the slot carries a unique trace id. Must be called
     * before any {@link Class} with that type id can be referenced by a JFR event.
     */
    void ensureRuntimeTypeId(int typeID) {
        int runtimeIndex = typeID + 1 - traceIDs.length;
        if (runtimeIndex < 0 || getRuntimeChunk(runtimeIndex) != null) {
            return;
        }
        synchronized (this) {
            long[][] chunks = runtimeChunks;
            int chunkIndex = runtimeIndex >>> RUNTIME_CHUNK_SHIFT;
            int oldLength = chunks == null ? 0 : chunks.length;
            if (chunkIndex < oldLength) {
                return;
            }
            long[][] newChunks = chunks == null ? new long[chunkIndex + 1][] : Arrays.copyOf(chunks, chunkIndex + 1);
            for (int i = oldLength; i <= chunkIndex; i++) {
                long[] chunk = new long[RUNTIME_CHUNK_SIZE];
                long firstId = maxHostedTraceId + 1 + ((long) i << RUNTIME_CHUNK_SHIFT);
                for (int j = 0; j < RUNTIME_CHUNK_SIZE; j++) {
                    chunk[j] = JfrTraceId.toRawTraceId(firstId + j);
                }
                newChunks[i] = chunk;
            }
            /* The volatile store publishes the fully initialized chunks to uninterruptible readers. */
            runtimeChunks = newChunks;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int getIndex(Class<?> clazz) {
        DynamicHub hub = DynamicHub.fromClass(clazz);
        return hub.getTypeID() + 1; // Off-set by 1 for error-catcher
    }
}
