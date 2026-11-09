import org.graalvm.nativeimage.hosted.Feature;
public class ReachableProbe implements Feature {
 Class<?> target;
 public void beforeAnalysis(BeforeAnalysisAccess access) {
  try { target = Class.forName("com.oracle.svm.interpreter.InterpreterForeignFunctionsSupportImpl"); access.registerAsUsed(target); }
  catch (Exception e) { throw new RuntimeException(e); }
 }
 public void afterAnalysis(AfterAnalysisAccess access) {
  try {
   Object meta = Class.forName("com.oracle.svm.hosted.FeatureImpl$AnalysisAccessBase").getMethod("getMetaAccess").invoke(access);
   Object type = Class.forName("com.oracle.graal.pointsto.meta.AnalysisMetaAccess").getMethod("lookupJavaType", Class.class).invoke(meta, target);
   Object instantiated = Class.forName("com.oracle.graal.pointsto.meta.AnalysisType").getMethod("isInstantiated").invoke(type);
   System.out.println("PROBE_REACHABLE=" + access.isReachable(target) + " INSTANTIATED=" + instantiated);
   if (!access.isReachable(target) || !Boolean.FALSE.equals(instantiated)) throw new AssertionError("Expected reachable uninstantiated type");
  } catch (Exception e) { throw new RuntimeException(e); }
 }
}
