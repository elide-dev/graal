import java.io.*;
import java.net.URI;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.tools.coverage.*;
import org.graalvm.polyglot.io.ByteSequence;
public class CopyEdgeProbe {
 public static void main(String[] args) throws Exception {
  Source[] sources={
   Source.newBuilder("x", "", "empty-content").uri(URI.create("")).build(),
   Source.newBuilder("x", "", "no-content").content(Source.CONTENT_NONE).uri(URI.create("")).build(),
   Source.newBuilder("x", ByteSequence.create(new byte[]{0,1,2}), "binary").uri(URI.create("")).build(),
   Source.newBuilder("x", "a", "same").uri(URI.create("")).build(),
   Source.newBuilder("x", "b", "same").uri(URI.create("")).build(),
   Source.newBuilder("x", "a", "custom").uri(URI.create("memory:///custom")).build()
  };
  var sc=SourceCoverage.class.getDeclaredConstructor(Source.class,RootCoverage[].class);sc.setAccessible(true);
  var clazz=Class.forName("com.oracle.truffle.tools.coverage.impl.LCOVPrinter");
  var ctor=clazz.getDeclaredConstructor(PrintStream.class,SourceCoverage[].class,boolean.class);ctor.setAccessible(true);
  var print=clazz.getDeclaredMethod("print");print.setAccessible(true);
  String previous=null;
  for(Source source:sources) {
   var before=source.getURI();
   var out=new ByteArrayOutputStream();
   var coverage=sc.newInstance(source,new RootCoverage[0]);
   print.invoke(ctor.newInstance(new PrintStream(out),new SourceCoverage[]{coverage},false));
   String sf=out.toString().lines().filter(l->l.startsWith("SF:")).findFirst().orElseThrow().substring(3);
   String expected=before.toString().isEmpty()?Source.newBuilder(source).uri(null).build().getURI().toString():before.toString();
   if(sf.isEmpty()||!sf.equals(expected)||!source.getURI().equals(before))throw new AssertionError(out.toString());
   if(source.getName().equals("same")) { if(sf.equals(previous))throw new AssertionError("collision");previous=sf; }
   System.out.println(source.getName()+" characters="+source.hasCharacters()+" bytes="+source.hasBytes()+" originalURI="+before+" SF="+sf);
  }
 }
}
