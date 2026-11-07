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

package com.oracle.svm.test.nmt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.word.impl.Word;
import org.junit.Test;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.VMInspectionOptions;
import com.oracle.svm.core.nmt.NativeMemoryTracking;
import com.oracle.svm.core.nmt.NmtCategory;
import com.oracle.svm.graal.SubstrateGraalUtils;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.graal.meta.SubstrateMethod;
import com.oracle.svm.guest.staging.c.function.InvokeJavaFunctionPointer;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.shared.util.ModuleSupport;
import com.oracle.svm.test.NativeImageBuildArgs;

import jdk.vm.ci.code.InstalledCode;

/**
 * Runtime compilation in a compilation isolate with native memory tracking enabled. The compilation
 * isolate allocates the metadata of the installed code (the {@code CodeInfo} and its arrays) and
 * hands it over to the client isolate, which must be able to track and later free it. Regression
 * test for "Bad NMT header: invalid magic" in
 * {@code IsolatedRuntimeMethodInfoAccess.startTrackingInCurrentIsolate}.
 */
@NativeImageBuildArgs({
                "--features=com.oracle.svm.test.nmt.IsolatedRuntimeCompilationNmtTest$TestFeature",
                "--enable-monitoring=nmt",
                "-H:+SupportCompileInIsolates",
                "-H:MaxRuntimeCompileMethods=1000",
})
public class IsolatedRuntimeCompilationNmtTest {

    /** Build-time holder for the method prepared for runtime compilation. */
    public static final class Holder {
        SubstrateMethod compute;
    }

    public static final class TestFeature implements Feature {
        public TestFeature() {
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false,
                            "org.graalvm.nativeimage.builder",
                            "com.oracle.svm.graal", "com.oracle.svm.graal.hosted.runtimecompilation", "com.oracle.svm.hosted");
            ModuleSupport.accessPackagesToClass(ModuleSupport.Access.EXPORT, TestFeature.class, false, "org.graalvm.nativeimage.guest.staging", "com.oracle.svm.guest.staging.c.function");
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

    interface ComputeFn extends CFunctionPointer {
        @InvokeJavaFunctionPointer
        int invoke(int x);
    }

    @Test
    public void installCodeCompiledInIsolate() {
        assertTrue("native memory tracking must be enabled", VMInspectionOptions.hasNativeMemoryTrackingSupport());
        assertTrue("runtime compilation must use a compilation isolate", SubstrateOptions.shouldCompileInIsolates());

        long codeMemoryBefore = NativeMemoryTracking.singleton().getMallocMemory(NmtCategory.Code);
        InstalledCode code = SubstrateGraalUtils.compileAndInstall(ImageSingletons.lookup(Holder.class).compute);
        try {
            assertTrue(code.isValid());
            /* The client isolate now tracks the metadata that the compilation isolate allocated. */
            assertTrue("installed code metadata must be tracked in the client isolate", NativeMemoryTracking.singleton().getMallocMemory(NmtCategory.Code) > codeMemoryBefore);

            ComputeFn fn = Word.pointer(code.getEntryPoint());
            assertEquals(JitSubject.compute(5), fn.invoke(5));
        } finally {
            code.invalidate();
        }
    }
}
