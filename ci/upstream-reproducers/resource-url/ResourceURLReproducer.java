import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class ResourceURLReproducer {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        URL first = new URL("resource://127.0.0.1/1!/x");
        URL second = new URL("resource://127.1/1!/x");
        Set<URL> urls = new HashSet<>();
        urls.add(first);
        urls.add(second);
        System.out.println("equal=" + first.equals(second) + ", sameFile=" + first.sameFile(second) + ", setSize=" + urls.size());
        if (first.equals(second) || first.sameFile(second) || urls.size() != 2) {
            throw new AssertionError("Resource URL identity used a network address");
        }
    }
}
