import java.nio.file.*;
public class LoadProbe {
 static class Loader extends ClassLoader { Class<?> loadBytes(byte[] b) { return defineClass(null, b, 0, b.length); } }
 public static void main(String[] args) throws Exception { Class<?> c = new Loader().loadBytes(Files.readAllBytes(Path.of(args[0]))); Object result = c.getMethod("answer").invoke(null); if (!Integer.valueOf(42).equals(result)) throw new AssertionError(result); System.out.println("DYNAMIC_LOAD_OK=" + result); }
}
