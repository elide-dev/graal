/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.hub;

import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.classinitialization.ClassInitializationInfo;
import com.oracle.svm.core.graal.meta.DynamicHubOffsets;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.core.hub.RuntimeClassLoading.ClassDefinitionInfo;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.guest.staging.layered.LayeredFieldValue;
import com.oracle.svm.jvmci.shared.meta.SharedType;
import com.oracle.svm.guest.staging.layered.LayeredFieldValueTransformer;
import com.oracle.svm.shared.BuildPhaseProvider;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Stable;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Storage for non-critical or mutable data of {@link DynamicHub}.
 *
 * Some of these fields are immutable and moving them to a separate read-only companion class might
 * improve sharing between isolates and processes, but could increase image size.
 */
public final class DynamicHubCompanion {
    /**
     * {@code Class.annotationData} of this class is known to be empty; stands in for a shared
     * empty {@code Class.AnnotationData} in {@link DynamicHubExtension#annotationData}.
     */
    static final int EXTENSION_FLAG_EMPTY_ANNOTATION_DATA = 1;
    /**
     * Unsafe allocation is always allowed; stands in for
     * {@code RuntimeDynamicAccessMetadata.alwaysAvailable(false)} in
     * {@link DynamicHubExtension#canUnsafeAllocate}.
     */
    static final int EXTENSION_FLAG_UNSAFE_ALLOCATION = 1 << 1;

    /** Field used for module information access at run-time. */
    final Module module;

    /**
     * The hub for the superclass, or null if an interface, a primitive type, or
     * {@link java.lang.Object}.
     *
     * @see Class#getSuperclass()
     */
    final DynamicHub superHub;

    /** Source file name if known; null otherwise. */
    final String sourceFileName;

    /** The {@link Modifier modifiers} of this class. */
    final int modifiers;

    /**
     * The class that serves as the host for the nest. All nestmates have the same host. Initially
     * set to {@code null} for runtime-loaded classes.
     */
    @Stable Class<?> nestHost;

    /** The simple binary name of this class, as returned by {@code Class.getSimpleBinaryName0}. */
    final String simpleBinaryName;

    /**
     * The class that declares this class, as returned by {@code Class.getDeclaringClass0} or an
     * exception that happened at image-build time.
     */
    Object declaringClass;

    final String signature;

    /** Similar to {@code DynamicHub.flags}, but set later during the image build. */
    @UnknownPrimitiveField(availability = BuildPhaseProvider.AfterHostedUniverse.class) //
    @LayeredFieldValue(transformer = JNIAccessibleFlagTransformer.class) //
    @Stable byte additionalFlags;

    //
    /**
     * The hub for an array of this type, or null if the array type has been determined as
     * uninstantiated by the static analysis. In layered builds, it is possible for this value to be
     * initially set to null and then updated in a subsequent layer.
     */
    @LayeredFieldValue(transformer = ArrayHubTransformer.class) @Stable DynamicHub arrayHub;

    /**
     * The interfaces that this class implements. Either null (no interfaces), a {@link DynamicHub}
     * (one interface), or a {@link DynamicHub}[] array (more than one interface).
     */
    @Stable Object interfacesEncoding;

    /**
     * Reference to a list of enum values for subclasses of {@link Enum}; null otherwise.
     */
    @Stable Object enumConstantsReference;

    /**
     * Back link to the SubstrateType used by the substrate meta access. Only used for the subset of
     * types for which a SubstrateType exists.
     */
    @UnknownObjectField(availability = BuildPhaseProvider.AfterAnalysis.class, fullyQualifiedTypes = "com.oracle.svm.graal.meta.SubstrateType", canBeNull = true) //
    @Stable SharedType metaType;

    /**
     * Metadata for running class initializers at run time. Refers to a singleton marker object for
     * classes/interfaces already initialized during image generation, i.e., this field is never
     * null at run time.
     */
    @Stable ClassInitializationInfo classInitializationInfo;

    /**
     * Encoded compact image reflection metadata for non-layered image classes. When using layered
     * images this field is always zero and should not be queried. Instead, use
     * {@link LayeredReflectionMetadataSingleton}.
     */
    @UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class) //
    @Stable int encodedReflectionMetadata;

    /**
     * Class metadata of image classes, packed with {@link PackedMetadataEncoding}; see
     * {@link ImageDynamicHubMetadata}. Zero if the class has no such metadata.
     */
    @UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class) //
    @Stable int encodedHubMetadata;

    /**
     * Rarely used data of the class, see {@link DynamicHubExtension}. Set at image build time only
     * for classes with class data or a JFR event configuration; all other classes allocate it on
     * first use with {@link #ensureExtension()}.
     */
    DynamicHubExtension extension;

    /**
     * Flags that stand in for {@link DynamicHubExtension} values that are shared singletons, so
     * that setting them does not require an extension object. See
     * {@link #EXTENSION_FLAG_EMPTY_ANNOTATION_DATA} and {@link #EXTENSION_FLAG_UNSAFE_ALLOCATION}.
     */
    @UnknownPrimitiveField(availability = BuildPhaseProvider.AfterCompilation.class) //
    @Stable byte extensionFlags;

    @Platforms(Platform.HOSTED_ONLY.class) //
    @Stable DynamicHub dynamicHub;

    /**
     * Classloader used for loading this class. Most classes have the correct class loader set
     * already at image build time. {@link PredefinedClassesSupport Predefined classes} get their
     * classloader only at run time, before "loading" the field value is
     * {@link DynamicHub#NO_CLASS_LOADER}.
     */
    Object classLoader;
    ResolvedJavaType interpreterType;

    @Platforms(Platform.HOSTED_ONLY.class)
    static DynamicHubCompanion createHosted(Module module, DynamicHub superHub, String sourceFileName, int modifiers,
                    Object classLoader, Class<?> nestHost, String simpleBinaryName, Object declaringClass, String signature, Object classData, DynamicHub dynamicHub) {
        return new DynamicHubCompanion(module, superHub, sourceFileName, modifiers, classLoader, nestHost, simpleBinaryName, declaringClass, signature, classData, dynamicHub);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    private DynamicHubCompanion(Module module, DynamicHub superHub, String sourceFileName, int modifiers,
                    Object classLoader, Class<?> nestHost, String simpleBinaryName, Object declaringClass, String signature, Object classData, DynamicHub dynamicHub) {
        this(module, superHub, sourceFileName, modifiers, classLoader, nestHost, simpleBinaryName, declaringClass, signature, classData, (ProtectionDomain) null);
        this.dynamicHub = dynamicHub;
    }

    static DynamicHubCompanion createAtRuntime(Module module, DynamicHub superHub, String sourceFileName, int modifiers,
                    ClassLoader classLoader, String simpleBinaryName, Object declaringClass, String signature, ClassDefinitionInfo info) {
        assert RuntimeClassLoading.isSupported();
        return new DynamicHubCompanion(module, superHub, sourceFileName, modifiers, classLoader, info.dynamicNest, simpleBinaryName, declaringClass, signature, info.classData, info.protectionDomain);
    }

    private DynamicHubCompanion(Module module, DynamicHub superHub, String sourceFileName, int modifiers,
                    Object classLoader, Class<?> nestHost, String simpleBinaryName, Object declaringClass, String signature, Object classData, ProtectionDomain protectionDomain) {
        this.module = module;
        this.superHub = superHub;
        this.sourceFileName = sourceFileName;
        this.modifiers = modifiers;
        this.nestHost = nestHost;
        this.simpleBinaryName = simpleBinaryName;
        this.declaringClass = declaringClass;
        this.signature = signature;

        this.classLoader = classLoader;
        if (classData != null) {
            ensureExtension().classData = classData;
        }
        if (protectionDomain != null) {
            ensureExtension().protectionDomain = protectionDomain;
        }
    }

    public void setHubMetadata(RuntimeDynamicHubMetadata hubMetadata) {
        ensureExtension().hubMetadata = hubMetadata;
    }

    public void setReflectionMetadata(RuntimeReflectionMetadata reflectionMetadata) {
        ensureExtension().reflectionMetadata = reflectionMetadata;
    }

    /** Class metadata of a runtime-loaded class, or null for image classes. */
    RuntimeDynamicHubMetadata getRuntimeHubMetadata() {
        DynamicHubExtension result = extension;
        return result == null ? null : result.hubMetadata;
    }

    /** Reflection metadata of a runtime-loaded class, or null for image classes. */
    ReflectionMetadata getRuntimeReflectionMetadata() {
        DynamicHubExtension result = extension;
        return result == null ? null : result.reflectionMetadata;
    }

    /** The extension object, or null if none has been allocated yet. */
    DynamicHubExtension extension() {
        return extension;
    }

    /** Returns the extension object, allocating and publishing it if necessary. */
    DynamicHubExtension ensureExtension() {
        DynamicHubExtension result = extension;
        if (result == null) {
            result = allocateExtension();
        }
        return result;
    }

    private DynamicHubExtension allocateExtension() {
        DynamicHubExtension created = new DynamicHubExtension();
        if (SubstrateUtil.HOSTED) {
            synchronized (this) {
                if (extension == null) {
                    extension = created;
                }
                return extension;
            }
        }
        /*
         * Use the witness value of the exchange instead of re-reading the field: the compiler may
         * still hold the read that found the field null, e.g. right after the companion was
         * allocated at run time, and would not see the value another thread installed.
         *
         * This is the only run-time write of the field, and the analysis cannot see it, so
         * DynamicHubOffsetsFeature registers the field as unsafe accessed. Without that, reads of
         * the field through a constant hub are folded to null (see there).
         */
        long offset = DynamicHubOffsets.singleton().getCompanionExtensionOffset();
        Object witness = Unsafe.getUnsafe().compareAndExchangeReference(this, offset, null, created);
        return witness == null ? created : (DynamicHubExtension) witness;
    }

    boolean isExtensionFlagSet(int flag) {
        return (extensionFlags & flag) != 0;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    void setExtensionFlag(int flag) {
        extensionFlags = (byte) (extensionFlags | flag);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public DynamicHub getDynamicHub() {
        return dynamicHub;
    }

    /**
     * In layered builds it is possible for a {@link DynamicHubCompanion#arrayHub} to become
     * reachable in a later layer than the layer in which the companion is installed in. When this
     * happens we must update the companion's field to point to the newly installed value.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    static class ArrayHubTransformer extends LayeredFieldValueTransformer<DynamicHubCompanion> {
        boolean appLayer = ImageLayerBuildingSupport.buildingApplicationLayer();

        @Override
        public boolean isValueAvailable(DynamicHubCompanion receiver) {
            /*
             * As soon as we have a non-null value we set the field. Otherwise, once the ready for
             * compilation phase has been reached no new dynamic hubs will be added and the value
             * can set.
             */
            return receiver.arrayHub != null || BuildPhaseProvider.isReadyForCompilation();
        }

        @Override
        public Result transform(DynamicHubCompanion receiver) {
            /*
             * When there is a concrete array hub value, then no update is needed in later layers.
             * However, when both the value is null and we are not in the application layer, then we
             * may update the value in a later layer.
             */
            return new Result(receiver.arrayHub, receiver.arrayHub == null && !appLayer);
        }

        @Override
        public boolean isUpdateAvailable(DynamicHubCompanion receiver) {
            /* A concrete new value has been found. */
            return receiver.arrayHub != null;
        }

        @Override
        public Result update(DynamicHubCompanion receiver) {
            assert receiver.arrayHub != null : "update should only be called when a valid arrayHub is available";
            return new Result(receiver.arrayHub, false);
        }
    }

    /**
     * A class installed in an earlier layer can be registered for JNI access in a later layer. In
     * that case, update the already installed companion with the newly set JNI-accessible bit.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    static class JNIAccessibleFlagTransformer extends LayeredFieldValueTransformer<DynamicHubCompanion> {
        boolean appLayer = ImageLayerBuildingSupport.buildingApplicationLayer();

        @Override
        public boolean isValueAvailable(@SuppressWarnings("unused") DynamicHubCompanion receiver) {
            return BuildPhaseProvider.isReadyForCompilation();
        }

        @Override
        public Result transform(DynamicHubCompanion receiver) {
            boolean jniAccessible = DynamicHub.isJNIAccessibleFlagSet(receiver.additionalFlags);
            return new Result(receiver.additionalFlags, !jniAccessible && !appLayer);
        }

        @Override
        public boolean isUpdateAvailable(DynamicHubCompanion receiver) {
            /*
             * Wait until setSharedData has finalized the instantiated bit before caching the
             * one-shot update result for the complete additionalFlags field.
             */
            return BuildPhaseProvider.isReadyForCompilation() && DynamicHub.isJNIAccessibleFlagSet(receiver.additionalFlags);
        }

        @Override
        public Result update(DynamicHubCompanion receiver) {
            assert DynamicHub.isJNIAccessibleFlagSet(receiver.additionalFlags) : "update should only be called when JNI accessibility was enabled";
            return new Result(receiver.additionalFlags, false);
        }
    }
}
