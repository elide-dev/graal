import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
public class JvmciProbe {
 public static void main(String[] args) {
  var meta=HotSpotJVMCIRuntime.runtime().getHostJVMCIBackend().getMetaAccess();
  for (Class<?> c : new Class<?>[]{probe.AotNonfinal.class,probe.AotFinal.class,probe.AotOrdinary.class,probe.AotOrdinaryFinal.class}) {
   int flags=meta.lookupJavaType(c).getModifiers(); boolean expected=c==probe.AotFinal.class||c==probe.AotOrdinaryFinal.class;
   System.out.printf("%s reflection=0x%x JVMCI=0x%x expectedFinal=%s%n",c.getName(),c.getModifiers(),flags,expected);
   if(java.lang.reflect.Modifier.isFinal(flags)!=expected)throw new AssertionError(c);
  }
 }
}
