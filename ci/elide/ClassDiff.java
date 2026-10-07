/*
 * Prints the classes that differ between two directories of class files (e.g. the same module
 * extracted from two JDK images), ignoring debug information: line numbers, local variable tables
 * and source debug extensions. Source changes to comments or formatting move line numbers but leave
 * the rest of a class as it is.
 *
 *   java ClassDiff.java <dir-a> <dir-b>
 */

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public class ClassDiff {
    public static void main(String[] args) throws IOException {
        Path a = Path.of(args[0]);
        Path b = Path.of(args[1]);
        Set<String> names = new TreeSet<>();
        names.addAll(classes(a));
        names.addAll(classes(b));
        for (String name : names) {
            Path fa = a.resolve(name);
            Path fb = b.resolve(name);
            if (!Files.exists(fa) || !Files.exists(fb) || !Arrays.equals(normalize(fa), normalize(fb))) {
                System.out.println(name);
            }
        }
    }

    static Set<String> classes(Path root) throws IOException {
        Set<String> result = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(f -> f.toString().endsWith(".class")).forEach(f -> result.add(root.relativize(f).toString().replace('\\', '/')));
        }
        return result;
    }

    /** The class without debug information, written back in a canonical form. */
    static byte[] normalize(Path file) throws IOException {
        ClassFile cf = ClassFile.of(ClassFile.DebugElementsOption.DROP_DEBUG, ClassFile.LineNumbersOption.DROP_LINE_NUMBERS,
                        ClassFile.ConstantPoolSharingOption.NEW_POOL);
        ClassModel model = cf.parse(Files.readAllBytes(file));
        return cf.transformClass(model, ClassTransform.ACCEPT_ALL);
    }
}
