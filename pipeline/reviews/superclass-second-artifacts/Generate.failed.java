import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public class Generate {
 static final int PUB=ClassFile.ACC_PUBLIC,SUP=ClassFile.ACC_SUPER,FIN=ClassFile.ACC_FINAL;
 static class Loader extends ClassLoader { Loader(ClassLoader p){super(p);} Class<?> define(byte[] b){return defineClass(null,b,0,b.length);} }
 static byte[] parent(String name, boolean actual, int inner) {
  ClassDesc p=ClassDesc.of(name);
  return ClassFile.of().build(p,b->{b.withFlags(PUB|SUP|(actual?FIN:0)); if(inner>=0)b.with(InnerClassesAttribute.of(InnerClassInfo.of(p,Optional.of(ClassDesc.of("probe.Outer")),Optional.of("Parent"),PUB|(inner==1?FIN:0))));});
 }
 static byte[] child(String name, String parent) {return ClassFile.of().build(ClassDesc.of(name), b->b.withFlags(PUB|SUP).withSuperclass(ClassDesc.of(parent)));}
 static void check(String id, Loader l, Class<?> p, boolean actual) {
  boolean rejected=false;
  try{l.define(child("probe.Child"+id,p.getName()));}catch(IncompatibleClassChangeError x){rejected=true;}
  System.out.println(id+" actual="+actual+" reflected="+Modifier.isFinal(p.getModifiers())+" rejected="+rejected);
  if(rejected!=actual)throw new AssertionError(id);
 }
 public static void main(String[] args)throws Exception {
  if(args.length>0&&args[0].equals("generate")) {
   Path root=Path.of(args[1]); Files.createDirectories(root.resolve("probe"));
   for(int a=0;a<2;a++)for(int i=0;i<2;i++)Files.write(root.resolve("probe/Aot"+a+i+".class"),parent("probe.Aot"+a+i,a!=0,i));
   return;
  }
  for(int a=0;a<2;a++)for(int i=-1;i<2;i++)for(int delegated=0;delegated<2;delegated++) {
   String id="R"+a+"I"+(i+1)+"D"+delegated;
   Loader l=new Loader(Generate.class.getClassLoader()); Class<?> p=l.define(parent("probe.Parent"+id,a!=0,i));
   check(id, delegated==0?l:new Loader(l),p,a!=0);
  }
  check("Object",new Loader(Probe.class.getClassLoader()),Object.class,false);
  check("String",new Loader(Probe.class.getClassLoader()),String.class,true);
  System.out.println("PASS 18 superclass cases");
 }
}
