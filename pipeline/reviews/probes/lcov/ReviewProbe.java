import java.io.*;
import java.net.URI;
import java.nio.file.*;
import org.graalvm.polyglot.*;
public class ReviewProbe {
  static Source literal(String code, URI uri) {
    var b=Source.newBuilder("instrumentation-test-language",code,"same-name");
    if(uri!=null) b.uri(uri);
    return b.buildLiteral();
  }
  public static void main(String[] args) throws Exception {
    Path dest=Path.of(args[0]);
    Path file=dest.resolve("real-source.test");
    Files.writeString(file,"ROOT(STATEMENT)");
    for(String mode:new String[]{"default","shared","empty","fileuri","path"}) {
      URI uri=switch(mode) {case "shared"->URI.create("memory:///shared"); case "empty"->URI.create(""); case "fileuri"->file.toUri(); default->null;};
      Source first=mode.equals("path") ? Source.newBuilder("instrumentation-test-language",file.toFile()).uri(URI.create("memory:///override")).build():literal("ROOT(STATEMENT)",uri);
      ByteArrayOutputStream out=new ByteArrayOutputStream();
      try(Context ctx=Context.newBuilder().out(out).option("coverage","true").option("coverage.Output","lcov").option("engine.WarnInterpreterOnly","false").build()) {
        ctx.eval(first);
        if(mode.equals("default")||mode.equals("shared")) ctx.eval(literal("ROOT(STATEMENT, STATEMENT)",uri));
      }
      Files.write(dest.resolve(mode+".info"),out.toByteArray());
      System.out.println(mode+" path="+first.getPath()+" uri="+first.getURI()+" SF="+out.toString().lines().filter(l->l.startsWith("SF:")).toList());
    }
  }
}
