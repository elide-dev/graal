/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.interpreter.ristretto;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.InterpreterExecutionOffsets;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.hub.DynamicHubCompanion;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.graal.hosted.DeoptimizationFeature;
import com.oracle.svm.graal.hosted.runtimecompilation.RuntimeCompilationFeature;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.meta.HostedMetaAccess;
import com.oracle.svm.hosted.thread.VMThreadLocalCollector;
import com.oracle.svm.interpreter.BuildTimeInterpreterUniverse;
import com.oracle.svm.interpreter.CremaFeature;
import com.oracle.svm.interpreter.InterpreterFeature;
import com.oracle.svm.interpreter.InterpreterFrame;
import com.oracle.svm.interpreter.InterpreterOptions;
import com.oracle.svm.interpreter.MethodHandleLinkage;
import com.oracle.svm.interpreter.RistrettoInterpreterSupport;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType;
import com.oracle.svm.interpreter.ristretto.compile.InterpreterDeoptEntryPoints;
import com.oracle.svm.interpreter.ristretto.compile.RistrettoDeoptimizationSupport;
import com.oracle.svm.interpreter.ristretto.compile.RistrettoDeoptimizedInterpreterFrame;
import com.oracle.svm.interpreter.ristretto.compile.RistrettoGraphBuilderPlugins;
import com.oracle.svm.interpreter.ristretto.compile.RistrettoMethodHandleLinkagePhase;
import com.oracle.svm.interpreter.ristretto.profile.RistrettoCompilationManager;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.ReflectionUtil;
import com.oracle.svm.util.JVMCIReflectionUtil;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.phases.util.Providers;

/**
 * Ristretto provides runtime Just-In-Time (JIT) compilation support for the Crema interpreter in
 * Native Image.
 *
 * Context: Native Image's closed-world model normally forbids loading new classes at runtime. The
 * Crema project adds dynamic class loading and a bytecode interpreter to execute Java bytecode at
 * Native Image runtime. To recover performance, Ristretto integrates with
 * {@link RuntimeCompilationFeature} so that hot interpreted methods can be compiled to machine code
 * at runtime.
 *
 * @see CremaFeature
 * @see InterpreterFeature
 * @see RuntimeCompilationFeature
 * @see RistrettoGraphBuilderPlugins
 * @see RistrettoUtils
 * @see RistrettoDirectives
 */
@AutomaticallyRegisteredFeature
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class RistrettoFeature implements InternalFeature {
    private static final String DYNAMIC_HUB_COMPANION_INTERPRETER_TYPE_FIELD = "interpreterType";
    private static final String INTERPRETER_RESOLVED_OBJECT_TYPE_VTABLE_HOLDER_FIELD = "vtableHolder";
    private static final String VTABLE_HOLDER_VTABLE_FIELD = "vtable";
    private static final String DIRECT_CALL_ENTRY_FIELD = "directCallEntry";
    private static final String PATCHED_SLOT_ADDRESSES_FIELD = "patchedSlotAddresses";
    private static final String PATCHED_SLOT_COUNT_FIELD = "patchedSlotCount";

    private static final Method interpEntryVoid = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryVoid", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryInt = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryInt", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryLong = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryLong", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryFloat = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryFloat", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryDouble = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryDouble", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryObject = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryObject", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryBoolean = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryBoolean", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryByte = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryByte", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryShort = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryShort", RistrettoDeoptimizedInterpreterFrame.class);
    private static final Method interpEntryChar = ReflectionUtil.lookupMethod(InterpreterDeoptEntryPoints.class, "entryChar", RistrettoDeoptimizedInterpreterFrame.class);

    private final RistrettoPreserveBytecodeSupport preserveBytecodeSupport = new RistrettoPreserveBytecodeSupport();

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.useRistretto();
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(RuntimeCompilationFeature.class, CremaFeature.class);
    }

    @Override
    public void onRegistration(OnRegistrationAccess access) {
        ImageSingletons.add(RistrettoFeature.class, this);
        ImageSingletons.add(RistrettoInterpreterSupport.class, new RistrettoInterpreterSupportImpl());
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        RuntimeSupport.getRuntimeSupport().addStartupHook(new RistrettoOptionsStartupHook());
        RuntimeSupport.getRuntimeSupport().addTearDownHook(RistrettoCompilationManager.getProfileSupportTearDownHook());

        if (RistrettoOptions.useDeoptimization()) {
            ImageSingletons.add(RistrettoDeoptimizationSupport.class, new RistrettoDeoptimizationSupport());
        }
        ImageSingletons.add(RistrettoOSRFrameLayout.class, new RistrettoOSRFrameLayout());
    }

    /**
     * Preserves Ristretto directive types required at runtime.
     */
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        FeatureImpl.BeforeAnalysisAccessImpl access = (FeatureImpl.BeforeAnalysisAccessImpl) a;
        preserveBytecodeSupport.beforeAnalysis(access);
        RistrettoUtils.forcePreserveType(access.getBigBang(), RistrettoDirectives.class);
        /*
         * Ristretto code calls this foreign call target (see RistrettoMethodHandleLinkagePhase),
         * which no image code calls, so it is compiled only as a root.
         */
        access.registerAsRoot(ReflectionUtil.lookupMethod(MethodHandleLinkage.class, "linkToVirtualEntryHolder", Object.class, Object.class, Object.class), true,
                        "Foreign call target of Ristretto code, registered in " + RistrettoFeature.class);
        /* OSR code reads the entering interpreter frame from this thread local. */
        RistrettoOSRFrameLayout.singleton().setCurrentFrame(ImageSingletons.lookup(VMThreadLocalCollector.class).forFastThreadLocal(RistrettoOSRSupport.currentFrameThreadLocal()));
        /*
         * The low-level Ristretto fast path reads these fields via offsets recorded in
         * InterpreterExecutionOffsets. Analysis does not necessarily observe those reads as normal
         * Java field loads, so preserve the hosted field metadata explicitly.
         */
        access.registerAsInHeap(InterpreterExecutionOffsets.class, "Offsets used by compiled interpreter dispatch prefixes");
        var metaAccess = access.getMetaAccess();
        registerFastPathField(access, metaAccess.lookupJavaType(DynamicHubCompanion.class), DYNAMIC_HUB_COMPANION_INTERPRETER_TYPE_FIELD);
        registerFastPathField(access, metaAccess.lookupJavaType(InterpreterResolvedObjectType.class), INTERPRETER_RESOLVED_OBJECT_TYPE_VTABLE_HOLDER_FIELD);
        registerFastPathField(access, metaAccess.lookupJavaType(InterpreterResolvedObjectType.VTableHolder.class), VTABLE_HOLDER_VTABLE_FIELD);
        registerFastPathField(access, metaAccess.lookupJavaType(InterpreterResolvedJavaMethod.class), DIRECT_CALL_ENTRY_FIELD);
        registerFastPathField(access, metaAccess.lookupJavaType(InterpreterResolvedJavaMethod.class), PATCHED_SLOT_ADDRESSES_FIELD);
        registerFastPathField(access, metaAccess.lookupJavaType(InterpreterResolvedJavaMethod.class), PATCHED_SLOT_COUNT_FIELD);

        if (RistrettoOptions.useDeoptimization()) {
            DeoptimizationFeature.registerDeoptimizeRuntimeAsRoot(access, RistrettoFeature.class);

            access.registerAsRoot(interpEntryVoid, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryInt, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryLong, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryFloat, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryDouble, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryObject, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);

            access.registerAsRoot(interpEntryBoolean, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryByte, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryShort, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
            access.registerAsRoot(interpEntryChar, true, "Interpreter Entry Point For Ristretto Deopt " + RistrettoFeature.class);
        }
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess a) {
        preserveBytecodeSupport.duringAnalysis(a);
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        preserveBytecodeSupport.afterAnalysis((FeatureImpl.AfterAnalysisAccessImpl) access);
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        var accessImpl = (FeatureImpl.AfterCompilationAccessImpl) access;
        if (!InterpreterOptions.DebuggerWithInterpreter.getValue()) {
            /*
             * Crema has initialized generic field metadata before this callback. The debugger owns
             * complete pools when enabled; otherwise hydrate only types with retained method bodies
             * before checking which Ristretto selections survived final executable-callee pruning.
             */
            BuildTimeInterpreterUniverse.singleton().createConstantPoolsForMethodBodies(accessImpl.getUniverse());
        }
        preserveBytecodeSupport.afterCompilation(accessImpl);
    }

    private static void registerFastPathField(FeatureImpl.BeforeAnalysisAccessImpl access, AnalysisType declaringType, String fieldName) {
        access.registerAsAccessed((AnalysisField) JVMCIReflectionUtil.getUniqueDeclaredField(declaringType, fieldName),
                        "Read by the Ristretto fast path via hosted field offsets.");
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess a) {
        FeatureImpl.BeforeCompilationAccessImpl config = (FeatureImpl.BeforeCompilationAccessImpl) a;
        preserveBytecodeSupport.beforeCompilation(config);
        initializeExecutionOffsets(config);
        initializeOSRFrameLayout(config);
        if (RistrettoOptions.useDeoptimization()) {
            config.registerAsImmutable(ImageSingletons.lookup(RistrettoDeoptimizationSupport.class));
            HostedMetaAccess metaAccess = config.getMetaAccess();

            RistrettoDeoptimizationSupport.initialize(
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryVoid)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryInt)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryLong)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryFloat)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryDouble)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryObject)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryBoolean)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryByte)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryShort)),
                            new MethodPointer(metaAccess.lookupJavaMethod(interpEntryChar)));
        }
    }

    /** Where OSR code finds the locals and locks of the interpreter frame that enters it. */
    private static void initializeOSRFrameLayout(FeatureImpl.BeforeCompilationAccessImpl access) {
        var frameType = access.getMetaAccess().lookupJavaType(InterpreterFrame.class);
        RistrettoOSRFrameLayout.singleton().setFieldOffsets(
                        JVMCIReflectionUtil.getUniqueDeclaredField(frameType, "references").getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(frameType, "primitives").getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(frameType, "locks").getOffset());
    }

    private static void initializeExecutionOffsets(FeatureImpl.BeforeCompilationAccessImpl access) {
        var metaAccess = access.getMetaAccess();
        var dynamicHubCompanionType = metaAccess.lookupJavaType(DynamicHubCompanion.class);
        var interpreterResolvedObjectType = metaAccess.lookupJavaType(InterpreterResolvedObjectType.class);
        var vtableHolderType = metaAccess.lookupJavaType(InterpreterResolvedObjectType.VTableHolder.class);
        var interpreterResolvedJavaMethodType = metaAccess.lookupJavaType(InterpreterResolvedJavaMethod.class);

        InterpreterExecutionOffsets offsets = new InterpreterExecutionOffsets(
                        JVMCIReflectionUtil.getUniqueDeclaredField(dynamicHubCompanionType, DYNAMIC_HUB_COMPANION_INTERPRETER_TYPE_FIELD).getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(interpreterResolvedObjectType, INTERPRETER_RESOLVED_OBJECT_TYPE_VTABLE_HOLDER_FIELD).getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(vtableHolderType, VTABLE_HOLDER_VTABLE_FIELD).getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(interpreterResolvedJavaMethodType, DIRECT_CALL_ENTRY_FIELD).getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(interpreterResolvedJavaMethodType, PATCHED_SLOT_ADDRESSES_FIELD).getOffset(),
                        JVMCIReflectionUtil.getUniqueDeclaredField(interpreterResolvedJavaMethodType, PATCHED_SLOT_COUNT_FIELD).getOffset(),
                        InterpreterResolvedJavaMethod.maxPatchedSlots());

        ImageSingletons.add(InterpreterExecutionOffsets.class, offsets);
        access.registerAsImmutable(offsets);
    }

    // TODO GR-71480 - invocation plugins for Ristretto
    /**
     * Registers Ristretto graph builder plugins that lower Crema interpreter operations and
     * runtime-compilation hooks.
     */
    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        RistrettoMethodHandleLinkagePhase.registerForeignCalls(foreignCalls);
    }

    @Override
    public void registerGraphBuilderPlugins(Providers providers, GraphBuilderConfiguration.Plugins plugins, ParsingReason reason) {
        registerRistrettoGraphBuilderPlugins(plugins);
    }

    /**
     * Installs the hosted-safe subset of Ristretto graph builder plugins into the given plugin set.
     *
     * @param plugins graph builder plugin container to mutate
     */
    public static void registerRistrettoGraphBuilderPlugins(GraphBuilderConfiguration.Plugins plugins) {
        RistrettoGraphBuilderPlugins.setHostedGraphBuilderPlugins(plugins);
    }

    public static final class RistrettoEnabled implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return SubstrateOptions.useRistretto();
        }
    }
}
