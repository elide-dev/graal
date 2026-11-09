import java.io.ByteArrayOutputStream;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

public class LCOVReproducer {
    public static void main(String[] args) {
        String language = "instrumentation-test-language";
        Source first = Source.newBuilder(language, "ROOT(STATEMENT)", "same-name").buildLiteral();
        Source second = Source.newBuilder(language, "ROOT(STATEMENT, STATEMENT)", "same-name").buildLiteral();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder().out(output).option("coverage", "true")
                .option("coverage.Output", "lcov").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval(first);
            context.eval(second);
        }
        List<String> files = output.toString().lines().filter(line -> line.startsWith("SF:")).toList();
        System.out.println(files);
        if (files.size() != 2 || !files.contains("SF:" + first.getURI()) ||
                !files.contains("SF:" + second.getURI()) || files.get(0).equals(files.get(1))) {
            throw new AssertionError("LCOV lost pathless source identity");
        }
    }
}
