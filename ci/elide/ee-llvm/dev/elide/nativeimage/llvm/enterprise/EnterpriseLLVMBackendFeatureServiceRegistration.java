package dev.elide.nativeimage.llvm.enterprise;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeatureServiceRegistration;

/** Registers {@link EnterpriseLLVMBackendFeature}, as the builder's annotation processor would. */
public final class EnterpriseLLVMBackendFeatureServiceRegistration implements AutomaticallyRegisteredFeatureServiceRegistration {
    @Override
    public String getClassName() {
        return EnterpriseLLVMBackendFeature.class.getName();
    }
}
