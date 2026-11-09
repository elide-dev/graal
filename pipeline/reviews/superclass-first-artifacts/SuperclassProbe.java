import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static java.lang.classfile.ClassFile.*;
public class SuperclassProbe {
  static class Loader extends ClassLoader { Class<?> define(byte[] b) { return defineClass(null,b,0,b.length); } }
  static byte[] parent(String name, int flags, int inner) {
    ClassDesc p=ClassDesc.of(name);
    return ClassFile.of().build(p,b->{ b.withFlags(flags); if(inner>=0)b.with(InnerClassesAttribute.of(InnerClassInfo.of(p,Optional.of(ClassDesc.of("probe.Outer")),Optional.of("Parent"),inner))); });
  }
  static byte[] child(String name,Class<?> p) { return ClassFile.of().build(ClassDesc.of(name),b->b.withFlags(ACC_PUBLIC|ACC_SUPER).withSuperclass(p.describeConstable().orElseThrow())); }
  static void check(String label,Loader l,Class<?> p,boolean expectedFinal,int index) {
    boolean rejected=false;
    try { l.define(child("probe.Child"+index,p)); } catch(IncompatibleClassChangeError e) { rejected=true; }
    System.out.println(label+" reflectionFinal="+Modifier.isFinal(p.getModifiers())+" expectedFinal="+expectedFinal+" childRejected="+rejected);
    if(rejected!=expectedFinal)throw new AssertionError(label);
  }
  public static void main(String[] args) throws Exception {
    int i=0;
    for(boolean fin:new boolean[]{false,true})for(int inner:new int[]{-1,ACC_PUBLIC,ACC_PUBLIC|ACC_FINAL}) {
      Loader l=new Loader(); Class<?> p=l.define(parent("probe.RuntimeParent"+i,ACC_PUBLIC|ACC_SUPER|(fin?ACC_FINAL:0),inner));
      check("runtime/"+i,l,p,fin,i++);
    }
    for(Class<?> p:new Class<?>[]{probe.AotNonfinal.class,probe.AotFinal.class,probe.AotOrdinary.class,probe.AotOrdinaryFinal.class,Object.class,String.class}) {
      check("aot/"+p.getName(),new Loader(),p,p==probe.AotFinal.class||p==probe.AotOrdinaryFinal.class||p==String.class,i++);
    }
    Loader l=new Loader();
    try {l.define(parent("probe.InvalidParent",ACC_PUBLIC|ACC_SUPER|ACC_FINAL|ACC_ABSTRACT,ACC_PUBLIC)); throw new AssertionError("invalid final abstract parent accepted");}catch(ClassFormatError expected){System.out.println("invalid final+abstract parent rejected: "+expected.getClass().getSimpleName());}
    System.out.println("PASS 13 cases");
  }
}
