/*
 * Copyright (c) 2024, 2026, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.pointsto.ObjectScanner.OtherReason;
import static com.oracle.graal.pointsto.ObjectScanner.ScanReason;
import static com.oracle.svm.interpreter.InterpreterFeature.assertionsEnabled;
import static com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod.VTBL_NO_DISPATCH;
import static com.oracle.svm.util.GuestAnnotationAccess.newAnnotationValue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.word.Pointer;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.code.SubstrateBackendWithAssembler;
import com.oracle.svm.core.hub.RuntimeClassLoading;
import com.oracle.svm.core.hub.crema.CremaJNIFieldIds;
import com.oracle.svm.core.hub.crema.CremaJNIMethodIds;
import com.oracle.svm.core.hub.crema.CremaSupport;
import com.oracle.svm.core.hub.registry.ClassRegistries;
import com.oracle.svm.core.interpreter.InterpreterForeignFunctionsSupport;
import com.oracle.svm.core.jni.CallVariant;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.classloading.RuntimeClassLoadingFeature;
import com.oracle.svm.hosted.code.CEntryPointCallStubSupport;
import com.oracle.svm.hosted.code.CEntryPointData;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedInstanceClass;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.interpreter.hosted.CremaJNIUpcallWrapperMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaField;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaType;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedObjectType;
import com.oracle.svm.jvmci.shared.meta.DeoptStub;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.util.GuestAccess;
import com.oracle.svm.util.JVMCIReflectionUtil;

import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * In this mode the interpreter is used to execute previously (= image build-time) unknown methods,
 * i.e. methods that are loaded or created at run-time.
 */

@Platforms(Platform.HOSTED_ONLY.class)
@AutomaticallyRegisteredFeature
public class CremaFeature implements InternalFeature {
    private static final String VTABLE_HOLDER_FIELD = "vtableHolder";

    private AnalysisMethod enterVTableInterpreterStub;
    private AnalysisMethod enterDirectInterpreterStub;
    private AnalysisMethod enterInterpreterForFFMUpcall;
    private AnalysisMethod enterCremaJNIUpcallVarargsVirtualWrapper;
    private AnalysisMethod enterCremaJNIUpcallArrayVirtualWrapper;
    private AnalysisMethod enterCremaJNIUpcallVaListVirtualWrapper;
    private AnalysisMethod enterCremaJNIUpcallVarargsNonVirtualWrapper;
    private AnalysisMethod enterCremaJNIUpcallArrayNonVirtualWrapper;
    private AnalysisMethod enterCremaJNIUpcallVaListNonVirtualWrapper;

    /** Constructors registered as roots by {@link #keepInlinedSuperConstructorsCompiled}. */
    private final Set<AnalysisMethod> rootedSuperConstructors = new HashSet<>();

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return RuntimeClassLoading.isSupported();
    }

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        return List.of(InterpreterFeature.class, RuntimeClassLoadingFeature.class);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(CremaSupport.class, new CremaSupportImpl());
        VMError.guarantee(!RuntimeClassLoading.isSupported() || ClassRegistries.respectClassLoader());
        ImageSingletons.add(InterpreterForeignFunctionsSupport.class, new InterpreterForeignFunctionsSupportImpl());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        FeatureImpl.BeforeAnalysisAccessImpl accessImpl = (FeatureImpl.BeforeAnalysisAccessImpl) access;
        try {
            var metaAccess = accessImpl.getMetaAccess();
            AnalysisType declaringClass = metaAccess.lookupJavaType(InterpreterStubSection.class);

            enterVTableInterpreterStub = (AnalysisMethod) JVMCIReflectionUtil.getUniqueDeclaredMethod(metaAccess, declaringClass,
                            "enterVTableInterpreterStub", int.class, Pointer.class);
            accessImpl.registerAsRoot(enterVTableInterpreterStub, true, "stub for interpreter");

            enterDirectInterpreterStub = (AnalysisMethod) JVMCIReflectionUtil.getUniqueDeclaredMethod(metaAccess, declaringClass,
                            "enterDirectInterpreterStub", InterpreterResolvedJavaMethod.class, Pointer.class);
            accessImpl.registerAsRoot(enterDirectInterpreterStub, true, "stub for interpreter");

            AnalysisMethod enterInterpreterForFFMUpcallTarget = (AnalysisMethod) JVMCIReflectionUtil.getUniqueDeclaredMethod(metaAccess, declaringClass,
                            "enterInterpreterForFFMUpcall", Pointer.class, Isolate.class, Pointer.class);
            CEntryPointData entryPointData = CEntryPointData.create(enterInterpreterForFFMUpcallTarget);
            enterInterpreterForFFMUpcall = CEntryPointCallStubSupport.singleton().registerStubForMethod(enterInterpreterForFFMUpcallTarget, () -> entryPointData, List.of(
                            newAnnotationValue(DeoptStub.class, "stubType", DeoptStub.StubType.InterpreterFFMUpcallStub)));

            access.registerAsInHeap(CremaJNIFieldIds.CremaJNIStaticFieldId.class);
            access.registerAsInHeap(CremaJNIMethodIds.CremaJNIMethodId.class);

            CEntryPointData unpublished = CEntryPointData.createCustomUnpublished();
            enterCremaJNIUpcallVarargsVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.VARARGS, false, unpublished);
            enterCremaJNIUpcallArrayVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.ARRAY, false, unpublished);
            enterCremaJNIUpcallVaListVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.VA_LIST, false, unpublished);
            enterCremaJNIUpcallVarargsNonVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.VARARGS, true, unpublished);
            enterCremaJNIUpcallArrayNonVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.ARRAY, true, unpublished);
            enterCremaJNIUpcallVaListNonVirtualWrapper = registerCremaJNIUpcallWrapperMethod(accessImpl, CallVariant.VA_LIST, true, unpublished);

            AnalysisType invokers = accessImpl.findTypeByName("java.lang.invoke.Invokers$Holder");
            for (AnalysisMethod invokersMethod : invokers.getDeclaredMethods(false)) {
                accessImpl.registerAsRoot(invokersMethod, false, "Used by method handles with crema");
            }
        } catch (NoSuchMethodError e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    private static AnalysisMethod registerCremaJNIUpcallWrapperMethod(FeatureImpl.BeforeAnalysisAccessImpl access, CallVariant callVariant, boolean nonVirtual, CEntryPointData entryPointData) {
        CremaJNIUpcallWrapperMethod wrapper = new CremaJNIUpcallWrapperMethod(callVariant, nonVirtual, access.getUniverse().getOriginalMetaAccess(), access.getBigBang()
                        .getWordTypes());
        AnalysisMethod analysisWrapper = access.getUniverse().lookup(wrapper);
        access.getBigBang().addRootMethod(analysisWrapper, true, "Registered in " + CremaFeature.class);
        analysisWrapper.registerAsNativeEntryPoint(entryPointData);
        return analysisWrapper;
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        AnalysisUniverse aUniverse = ((FeatureImpl.AfterAnalysisAccessImpl) access).getUniverse();
        BuildTimeInterpreterUniverse btiUniverse = BuildTimeInterpreterUniverse.singleton();

        keepSuperConstructorsCompiled(aUniverse, btiUniverse);

        if (assertionsEnabled()) {
            for (AnalysisType analysisType : aUniverse.getTypes()) {
                if (!analysisType.isReachable()) {
                    continue;
                }
                assert btiUniverse.getType(analysisType) != null : "type is reachable but not part of interpreter universe: " + analysisType;
            }
        }
    }

    /**
     * Whether run-time loaded subclasses of the declaring class can call {@code method} as their
     * super constructor.
     */
    private static boolean isSuperConstructorCandidate(AnalysisMethod method) {
        if (!method.isConstructor() || method.isPrivate()) {
            return false;
        }
        AnalysisType declaringClass = method.getDeclaringClass();
        return !declaringClass.isFinalFlagSet() && !declaringClass.isHidden();
    }

    /**
     * A class loaded at run time can extend any non-final image class, and its constructors must
     * then call a constructor of that class. The interpreter calls an image method through the
     * method's compiled code, because image methods keep no bytecodes; so a constructor that image
     * code calls must keep compiled code of its own, even when every image caller inlines it (the
     * usual fate of a trivial constructor), or a run-time loaded subclass could not be instantiated
     * (elide-dev/runtime#2508).
     * <p>
     * Inlining before analysis leaves such a constructor reachable but never invoked on its own,
     * with no graph the compile queue could compile. Registering it as a root here makes the
     * analysis parse it, and the compile queue then compiles it as an entry point. A special-invoke
     * root links only once the analysis sees an instance of the declaring class or of a subclass,
     * which it may never do (the inlined allocation may have been dead), so the constructor is
     * registered as implementation-invoked outright, as {@link BigBang#forcedAddRootMethod} does
     * for the constructors of abstract classes: run-time class loading implies the open type world,
     * whose analysis saturates the flows of such a method, so its code is sound for any subclass.
     * Constructors that image code never calls are left alone: calling one raises a
     * {@link NoSuchMethodError} that names the metadata which keeps it.
     *
     * @return whether a root was registered, which requires another analysis iteration
     */
    private boolean keepInlinedSuperConstructorsCompiled(FeatureImpl.DuringAnalysisAccessImpl access) {
        boolean added = false;
        for (AnalysisMethod method : access.getUniverse().getMethods()) {
            if (isSuperConstructorCandidate(method) && method.isInlined() && !method.isSimplyImplementationInvoked() && !method.isDirectRootMethod() && rootedSuperConstructors.add(method)) {
                String reason = "Constructor of a subclassable image class that image code inlines; run-time loaded subclasses call it";
                access.getBigBang().forcedAddRootMethod(method, true, reason);
                method.registerAsImplementationInvoked(reason);
                added = true;
            }
        }
        return added;
    }

    /**
     * The compile queue compiles a method only when compiled code still calls it after inlining, so
     * a constructor that the analysis linked can still end up without compiled code of its own.
     * Compile the super constructor candidates the analysis linked on their own, see
     * {@link #keepInlinedSuperConstructorsCompiled}.
     */
    private static void keepSuperConstructorsCompiled(AnalysisUniverse aUniverse, BuildTimeInterpreterUniverse btiUniverse) {
        for (AnalysisMethod method : aUniverse.getMethods()) {
            if (isSuperConstructorCandidate(method) && method.isSimplyImplementationInvoked() && btiUniverse.getMethod(method) != null) {
                SubstrateCompilationDirectives.singleton().registerForcedCompilation(method);
            }
        }
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        FeatureImpl.DuringAnalysisAccessImpl accessImpl = (FeatureImpl.DuringAnalysisAccessImpl) access;
        if (keepInlinedSuperConstructorsCompiled(accessImpl)) {
            accessImpl.requireAnalysisIteration();
        }
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        FeatureImpl.BeforeCompilationAccessImpl accessImpl = (FeatureImpl.BeforeCompilationAccessImpl) access;
        HostedUniverse hUniverse = accessImpl.getUniverse();
        BuildTimeInterpreterUniverse iUniverse = BuildTimeInterpreterUniverse.singleton();
        ResolvedJavaField vtableHolderField = JVMCIReflectionUtil.getUniqueDeclaredField(GuestAccess.get().lookupType(InterpreterResolvedObjectType.class), VTABLE_HOLDER_FIELD);

        for (HostedMethod method : hUniverse.getMethods()) {
            InterpreterResolvedJavaMethod iMethod = iUniverse.getMethod(method);
            if (iMethod == null) {
                continue;
            }
            if (method.hasVTableIndex()) {
                iMethod.setVTableIndex(method.getVTableIndex());
            } else if (!(method.isStatic() || method.isConstructor()) && (method.isPrivate() || method.isFinal() || method.getDeclaringClass().isFinalFlagSet())) {
                /*
                 * This helps when such methods are called with call kind VTABLE_LOOKUP. Most crema
                 * paths ensure those methods will end up being called with call kind DIRECT, but
                 * setting this up anyway makes the crema call path more robust.
                 */
                iMethod.setVTableIndex(VTBL_NO_DISPATCH);
            }
        }

        ScanReason reason = new OtherReason("Manual rescan triggered before compilation from " + CremaFeature.class);
        for (HostedType hType : hUniverse.getTypes()) {
            iUniverse.mirrorSVMVTable(hType, objectType -> accessImpl.getHeapScanner().rescanField(objectType, vtableHolderField, reason));
        }

        InterpreterFeature.prepareSignatures();

        accessImpl.registerAsImmutable(CremaSupport.singleton());
        CremaSupport.singleton().setEnterDirectInterpreterStubEntryPoint(new MethodPointer(hUniverse.lookup(enterDirectInterpreterStub)));
        /*
         * Only foreign function support (com.oracle.svm.core.foreign) uses this singleton. Without
         * it, the analysis never reached the singleton's type, and it is not in the image.
         */
        InterpreterForeignFunctionsSupport ffmSupport = InterpreterForeignFunctionsSupport.singleton();
        if (hUniverse.getBigBang().getMetaAccess().optionalLookupJavaType(ffmSupport.getClass()).filter(AnalysisType::isReachable).isPresent()) {
            accessImpl.registerAsImmutable(ffmSupport);
            ffmSupport.setUpcallStubPointer(new MethodPointer(hUniverse.lookup(enterInterpreterForFFMUpcall)));
        }
        CremaSupport.singleton().setCremaJNIUpcallWrapperEntryPoints(new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallVarargsVirtualWrapper)),
                        new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallArrayVirtualWrapper)),
                        new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallVaListVirtualWrapper)),
                        new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallVarargsNonVirtualWrapper)),
                        new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallArrayNonVirtualWrapper)),
                        new MethodPointer(hUniverse.lookup(enterCremaJNIUpcallVaListNonVirtualWrapper)));
    }

    @Override
    public void afterCompilation(AfterCompilationAccess access) {
        FeatureImpl.AfterCompilationAccessImpl accessImpl = (FeatureImpl.AfterCompilationAccessImpl) access;
        BuildTimeInterpreterUniverse iUniverse = BuildTimeInterpreterUniverse.singleton();

        for (HostedType type : accessImpl.getUniverse().getTypes()) {
            if (type.isPrimitive() || type.isArray()) {
                continue;
            }
            InterpreterResolvedJavaType iType = iUniverse.getType(type.getWrapped());
            if (iType == null) {
                assert !type.getWrapped().isReachable() : "No interpreter type for " + type;
                continue;
            }

            // Setup fields info
            InterpreterResolvedObjectType objectType = (InterpreterResolvedObjectType) iType;
            initializeInterpreterFields(iUniverse, (HostedField[]) type.getStaticFields());
            if (type.isInterface()) {
                continue;
            }
            HostedInstanceClass instanceClass = (HostedInstanceClass) type;
            objectType.setAfterFieldsOffset(instanceClass.getAfterFieldsOffset());
            initializeInterpreterFields(iUniverse, instanceClass.getInstanceFields(false));
        }
    }

    private static void initializeInterpreterFields(BuildTimeInterpreterUniverse iUniverse, HostedField[] fields) {
        for (HostedField hostedField : fields) {
            InterpreterResolvedJavaField iField = iUniverse.getField(hostedField.getWrapped());
            if (iField == null) {
                assert !hostedField.isAccessed() : "No interpreter field for " + hostedField;
                continue;
            }
            if (iField.isArtificiallyReachable()) {
                VMError.guarantee(iField.isUnmaterializedConstant());
                continue;
            }
            iUniverse.initializeJavaFieldFromHosted(hostedField, iField);
        }
    }

    @Override
    public void afterAbstractImageCreation(AfterAbstractImageCreationAccess access) {
        FeatureImpl.AfterAbstractImageCreationAccessImpl accessImpl = ((FeatureImpl.AfterAbstractImageCreationAccessImpl) access);
        InterpreterStubSection stubSection = ImageSingletons.lookup(InterpreterStubSection.class);

        SubstrateBackend b = accessImpl.getRuntimeConfiguration().getBackendForNormalMethod();
        if (b instanceof SubstrateBackendWithAssembler<?> bAsm) {
            stubSection.createInterpreterVTableEnterStubSection(accessImpl.getImage(), bAsm);
        } else {
            throw VMError.shouldNotReachHere("Needs a backend with an assembler, it is not available with backend %s", b.getClass());
        }
    }

    @Override
    public void beforeImageWrite(BeforeImageWriteAccess access) {
        FeatureImpl.BeforeImageWriteAccessImpl accessImpl = (FeatureImpl.BeforeImageWriteAccessImpl) access;
        InterpreterStubSection stubSection = ImageSingletons.lookup(InterpreterStubSection.class);
        stubSection.markEnterStubPatch(accessImpl.getHostedUniverse().lookup(enterVTableInterpreterStub));
    }
}
