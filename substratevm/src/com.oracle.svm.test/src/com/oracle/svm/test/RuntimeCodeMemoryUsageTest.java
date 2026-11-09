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
package com.oracle.svm.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.junit.Test;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.graal.SubstrateGraalUtils;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.graal.meta.SubstrateMethod;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.shared.util.ModuleSupport;

import jdk.vm.ci.code.InstalledCode;

/**
 * Checks the size counters of {@link RuntimeCodeInfoMemory}, which back the non-heap memory usage
 * of the {@code MemoryMXBean}, while runtime-compiled code is installed, invalidated and freed, with
 * and without compilation isolates. The counters must include the code and metadata of installed
 * code and return to their previous values when it is freed. Regression test for
 * elide-dev/graal#227, where the counters became negative without compilation isolates.
 */
@NativeImageBuildArgs({
                "--features=com.oracle.svm.test.RuntimeCodeMemoryUsageTest$TestFeature",
                "-H:+SupportCompileInIsolates",
                "-H:MaxRuntimeCompileMethods=1000",
})
public class RuntimeCodeMemoryUsageTest {

    private static final int INSTALLATIONS = 8;
    private static final int MAX_GCS = 20;

    /** Build-time holder for the method prepared for runtime compilation. */
    public static final class Holder {
        SubstrateMethod compute;
    }

    public static final class TestFeature implements Feature {
        public TestFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false,
                            "org.graalvm.nativeimage.builder",
                            "com.oracle.svm.graal", "com.oracle.svm.graal.hosted.runtimecompilation", "com.oracle.svm.hosted");
        }

        @Override
        public List<Class<? extends Feature>> getRequiredFeatures() {
            return List.of(RuntimeCompilationFeature.class);
        }

        @Override
        public void beforeAnalysis(BeforeAnalysisAccess a) {
            BeforeAnalysisAccessImpl config = (BeforeAnalysisAccessImpl) a;
            RuntimeClassInitialization.initializeAtBuildTime(Holder.class);
            RuntimeClassInitialization.initializeAtBuildTime(JitSubject.class);
            Holder holder = new Holder();
            ImageSingletons.add(Holder.class, holder);
            RuntimeCompilationFeature rcf = RuntimeCompilationFeature.singleton();
            rcf.initializeRuntimeCompilationForTesting(config);
            try {
                holder.compute = rcf.prepareMethodForRuntimeCompilation(JitSubject.class.getMethod("compute", int.class), config);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The method compiled at image run time. */
    public static final class JitSubject {
        public static int compute(int x) {
            return x * 3 + 1;
        }
    }

    @Test
    public void codeCompiledInApplicationIsolate() {
        checkWithCompileInIsolates(false);
    }

    @Test
    public void codeCompiledInCompilationIsolate() {
        checkWithCompileInIsolates(true);
    }

    private static void checkWithCompileInIsolates(boolean compileInIsolates) {
        boolean previous = SubstrateOptions.ConcealedOptions.CompileInIsolates.getValue();
        SubstrateOptions.ConcealedOptions.CompileInIsolates.update(compileInIsolates);
        try {
            assertEquals(compileInIsolates, SubstrateOptions.shouldCompileInIsolates());
            checkInstallAndFree();
        } finally {
            SubstrateOptions.ConcealedOptions.CompileInIsolates.update(previous);
        }
    }

    private static void checkInstallAndFree() {
        RuntimeCodeInfoMemory memory = RuntimeCodeInfoMemory.singleton();
        freeUnreachableCode(0);
        int countBefore = memory.getCount();
        RuntimeCodeInfoMemory.SizeCounters before = checkedCounters();

        List<InstalledCode> installed = new ArrayList<>();
        try {
            checkInstalled(installed, countBefore, before);
        } finally {
            for (InstalledCode code : installed) {
                code.invalidate();
            }
            installed.clear();
        }
        freeUnreachableCode(countBefore);
        assertEquals("invalidated code must be freed", countBefore, memory.getCount());

        RuntimeCodeInfoMemory.SizeCounters after = checkedCounters();
        assertEquals(before.codeSize().rawValue(), after.codeSize().rawValue());
        assertEquals(before.codeAndDataMemorySize().rawValue(), after.codeAndDataMemorySize().rawValue());
        assertEquals(before.nativeMetadataSize().rawValue(), after.nativeMetadataSize().rawValue());
        assertEquals(before.totalSize().rawValue(), after.totalSize().rawValue());
    }

    private static void checkInstalled(List<InstalledCode> installed, int countBefore, RuntimeCodeInfoMemory.SizeCounters before) {
        RuntimeCodeInfoMemory memory = RuntimeCodeInfoMemory.singleton();
        for (int i = 0; i < INSTALLATIONS; i++) {
            InstalledCode code = SubstrateGraalUtils.compileAndInstall(ImageSingletons.lookup(Holder.class).compute);
            assertTrue(code.isValid());
            installed.add(code);
        }
        assertEquals(countBefore + INSTALLATIONS, memory.getCount());

        RuntimeCodeInfoMemory.SizeCounters live = checkedCounters();
        long liveCode = live.codeSize().rawValue() - before.codeSize().rawValue();
        long liveCodeAndData = live.codeAndDataMemorySize().rawValue() - before.codeAndDataMemorySize().rawValue();
        long liveMetadata = live.nativeMetadataSize().rawValue() - before.nativeMetadataSize().rawValue();
        assertTrue("installed code must be counted: " + liveCode, liveCode > 0);
        assertTrue("installed code and data memory must be counted: " + liveCodeAndData, liveCodeAndData >= liveCode);
        assertTrue("installed code metadata must be counted: " + liveMetadata, liveMetadata > 0);
        assertEquals(live.codeAndDataMemorySize().rawValue() + live.nativeMetadataSize().rawValue(), live.totalSize().rawValue());
        assertEquals(live.totalSize().rawValue(), nonHeapUsed());
    }

    private static void freeUnreachableCode(int expectedCount) {
        for (int i = 0; i < MAX_GCS && RuntimeCodeInfoMemory.singleton().getCount() > expectedCount; i++) {
            System.gc();
        }
    }

    private static RuntimeCodeInfoMemory.SizeCounters checkedCounters() {
        RuntimeCodeInfoMemory.SizeCounters counters = RuntimeCodeInfoMemory.singleton().getSizeCounters();
        assertTrue("code size must not be negative: " + counters.codeSize().rawValue(), counters.codeSize().rawValue() >= 0);
        assertTrue("code and data memory size must not be negative: " + counters.codeAndDataMemorySize().rawValue(), counters.codeAndDataMemorySize().rawValue() >= 0);
        assertTrue("native metadata size must not be negative: " + counters.nativeMetadataSize().rawValue(), counters.nativeMetadataSize().rawValue() >= 0);
        assertTrue("total size must not be negative: " + counters.totalSize().rawValue(), counters.totalSize().rawValue() >= 0);
        return counters;
    }

    private static long nonHeapUsed() {
        /* Throws an IllegalArgumentException if the counters are negative. */
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
    }
}
