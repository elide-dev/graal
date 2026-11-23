package dev.elide.nativeimage.llvm.enterprise;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.code.SubstrateBackendFactory;
import com.oracle.svm.enterprise.core.EnterpriseSubstrateBackendFactory;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.phases.util.Providers;

/**
 * Lets Oracle GraalVM's enterprise image builder compile with the fork's LLVM backend.
 *
 * The enterprise compiler configuration creates its backend through the
 * {@link EnterpriseSubstrateBackendFactory} singleton. The enterprise AMD64 and AArch64 features
 * register it for Graal's own backends only: with -H:CompilerBackend=llvm they leave it to Oracle's
 * enterprise LLVM module, which Oracle's builds no longer ship. This registers one that creates the
 * backend of the CE LLVM backend's {@link SubstrateBackendFactory} (LLVMFeature's).
 */
@AutomaticallyRegisteredFeature
public final class EnterpriseLLVMBackendFeature implements InternalFeature {
    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return SubstrateOptions.useLLVMBackend();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (!ImageSingletons.contains(EnterpriseSubstrateBackendFactory.class)) {
            ImageSingletons.add(EnterpriseSubstrateBackendFactory.class, new LLVMBackendFactory());
        }
    }

    @SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
    private static final class LLVMBackendFactory extends EnterpriseSubstrateBackendFactory {
        @Override
        public SubstrateBackend newBackend(Providers providers) {
            return SubstrateBackendFactory.get().newBackend(providers);
        }
    }
}
