/*
 * Glue between the fork's Native Image LLVM backend (svml) and Oracle GraalVM's enterprise image
 * builder, for the EE distribution. build-ee.py compiles it against the EE home; see
 * EnterpriseLLVMBackendFeature.
 */
module com.oracle.svm.svm_enterprise.llvm {
    requires org.graalvm.nativeimage;
    requires org.graalvm.nativeimage.builder;
    requires org.graalvm.nativeimage.shared;
    requires com.oracle.svm.svm_enterprise;
    requires jdk.graal.compiler;

    provides com.oracle.svm.core.feature.AutomaticallyRegisteredFeatureServiceRegistration
                    with dev.elide.nativeimage.llvm.enterprise.EnterpriseLLVMBackendFeatureServiceRegistration;
}
