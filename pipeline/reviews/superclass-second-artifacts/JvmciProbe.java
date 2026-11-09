import java.lang.reflect.Modifier;
import jdk.vm.ci.runtime.JVMCI;
public class JvmciProbe {
 public static void main(String[] a) {
  var meta=JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
  Class<?>[] classes={probe.Aot00.class,probe.Aot01.class,probe.Aot10.class,probe.Aot11.class};
  for(int i=0;i<classes.length;i++) {
   boolean actual=Modifier.isFinal(meta.lookupJavaType(classes[i]).getModifiers());
   System.out.println(classes[i].getName()+" reflection="+Modifier.isFinal(classes[i].getModifiers())+" JVMCI="+actual);
   if(actual!=(i>=2))throw new AssertionError();
  }
 }
}
