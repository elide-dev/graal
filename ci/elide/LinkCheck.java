/*
 * Checks that Oracle's enterprise code still links against the fork's code in an EE distribution.
 *
 *   java LinkCheck.java <ee-home> <fork-jar>... -- <enterprise-jar>...
 *
 * The fork jars are the jars build-ee.py put into the home: the overlaid jars and the upgraded
 * compiler modules in lib/jvmci. The enterprise jars are the jars only Oracle's build has. The
 * enterprise modules inside lib/modules are found by name.
 *
 * For every class of the enterprise code, it checks each class, field and method that the class
 * refers to and that resolves in, or through, a fork class: the class exists, the member exists with
 * the same descriptor and is not private, the superclass is not final, no final method is
 * overridden, and a concrete class implements every abstract method it inherits. For each upgraded
 * module, the fork's module must export and open at least what Oracle's module does.
 *
 * It prints each problem and exits with 1 if there is one.
 */

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class LinkCheck {
    /** Where a class's bytes come from: a jar entry or a path in the JDK's runtime image. */
    interface Source {
        byte[] read() throws IOException;
    }

    final Map<String, Source> forkClasses = new HashMap<>();
    final Map<String, Source> otherClasses = new HashMap<>();
    final Map<String, ClassModel> parsed = new HashMap<>();
    final Map<String, String> consumers = new HashMap<>(); // class -> where it comes from
    final Set<String> problems = new TreeSet<>();
    /** Classes whose hierarchy has a supertype outside the home (e.g. Truffle's runtime, from Maven). */
    final Set<String> unresolvedHierarchies = new TreeSet<>();
    int references;

    public static void main(String[] args) throws Exception {
        int split = List.of(args).indexOf("--");
        if (args.length < 2 || split < 1) {
            System.err.println("usage: java LinkCheck.java <ee-home> <fork-jar>... -- <enterprise-jar>...");
            System.exit(2);
        }
        Path home = Path.of(args[0]);
        List<Path> forkJars = Stream.of(args).skip(1).limit(split - 1).map(Path::of).toList();
        List<Path> enterpriseJars = Stream.of(args).skip(split + 1).map(Path::of).toList();
        new LinkCheck().run(home, forkJars, enterpriseJars);
    }

    void run(Path home, List<Path> forkJars, List<Path> enterpriseJars) throws Exception {
        for (Path jar : forkJars) {
            index(jar, forkClasses, null);
        }
        for (Path jar : enterpriseJars) {
            index(jar, otherClasses, jar.toString());
        }
        FileSystem jrt = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", home.toString()));
        // The upgraded modules replace Oracle's copies entirely, so those copies provide nothing.
        Set<String> upgraded = new TreeSet<>();
        for (Path jar : forkJars) {
            if (jar.getParent().getFileName().toString().equals("jvmci")) {
                upgraded.add(ModuleFinder.of(jar).findAll().iterator().next().descriptor().name());
            }
        }
        Set<String> enterpriseModules = new TreeSet<>();
        try (Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
            for (Path module : modules.toList()) {
                String name = module.getFileName().toString();
                if (upgraded.contains(name)) {
                    continue;
                }
                boolean enterprise = name.contains("enterprise");
                if (enterprise) {
                    enterpriseModules.add(name);
                }
                try (Stream<Path> files = Files.walk(module)) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                        String className = module.relativize(file).toString();
                        className = className.substring(0, className.length() - ".class".length());
                        if (className.equals("module-info")) {
                            continue;
                        }
                        otherClasses.putIfAbsent(className, () -> Files.readAllBytes(file));
                        if (enterprise) {
                            consumers.put(className, "module " + name);
                        }
                    }
                }
            }
        }
        for (String consumer : new TreeSet<>(consumers.keySet())) {
            checkClass(consumer);
        }
        checkModules(jrt, forkJars);
        System.out.printf("link check: %d enterprise classes (%d jars, modules %s), %d references into %d fork classes; upgraded modules %s%n",
                        consumers.size(), enterpriseJars.size(), enterpriseModules, references, forkClasses.size(), upgraded);
        if (!unresolvedHierarchies.isEmpty()) {
            System.out.printf("not checked, some supertypes are not in the home: %s%n", unresolvedHierarchies);
        }
        problems.forEach(p -> System.out.println("problem: " + p));
        if (!problems.isEmpty()) {
            System.exit(1);
        }
    }

    /** Indexes a jar's classes, honoring multi-release entries up to the running Java version. */
    void index(Path jar, Map<String, Source> into, String consumerOrigin) throws IOException {
        Map<String, Integer> versions = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entries = file.entries(); entries.hasMoreElements();) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                int version = 0;
                String className = name;
                if (name.startsWith("META-INF/versions/")) {
                    String[] parts = name.split("/", 4);
                    version = Integer.parseInt(parts[2]);
                    if (version > Runtime.version().feature()) {
                        continue;
                    }
                    className = parts[3];
                }
                className = className.substring(0, className.length() - ".class".length());
                if (versions.getOrDefault(className, -1) >= version) {
                    continue;
                }
                versions.put(className, version);
                String entry = name;
                into.put(className, () -> {
                    try (JarFile f = new JarFile(jar.toFile()); InputStream in = f.getInputStream(f.getEntry(entry))) {
                        return in.readAllBytes();
                    }
                });
                if (consumerOrigin != null) {
                    consumers.put(className, consumerOrigin);
                }
            }
        }
    }

    /** The class, the fork's if the fork has one. Empty if no one has it. */
    Optional<ClassModel> load(String name) {
        if (parsed.containsKey(name)) {
            return Optional.ofNullable(parsed.get(name));
        }
        Source source = forkClasses.containsKey(name) ? forkClasses.get(name) : otherClasses.get(name);
        ClassModel model = null;
        if (source != null) {
            try {
                model = ClassFile.of().parse(source.read());
            } catch (IOException e) {
                throw new RuntimeException(name, e);
            }
        }
        parsed.put(name, model);
        return Optional.ofNullable(model);
    }

    /** The class and all its supertypes, the superclasses first. */
    List<ClassModel> hierarchy(ClassModel model) {
        incomplete = false;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ClassModel> result = new ArrayList<>();
        List<ClassModel> interfaces = new ArrayList<>();
        for (ClassModel c = model; c != null;) {
            if (seen.add(name(c))) {
                result.add(c);
            }
            for (ClassEntry i : c.interfaces()) {
                loadSupertype(i.asInternalName()).ifPresent(interfaces::add);
            }
            c = c.superclass().flatMap(s -> loadSupertype(s.asInternalName())).orElse(null);
        }
        while (!interfaces.isEmpty()) {
            ClassModel i = interfaces.removeFirst();
            if (seen.add(name(i))) {
                result.add(i);
                for (ClassEntry s : i.interfaces()) {
                    loadSupertype(s.asInternalName()).ifPresent(interfaces::add);
                }
            }
        }
        // An interface's members include Object's public methods.
        if (!seen.contains("java/lang/Object")) {
            load("java/lang/Object").ifPresent(result::add);
        }
        return result;
    }

    /** Whether the last {@link #hierarchy} call met a supertype that is not in the home. */
    boolean incomplete;

    Optional<ClassModel> loadSupertype(String name) {
        Optional<ClassModel> model = load(name);
        if (model.isEmpty()) {
            incomplete = true;
        }
        return model;
    }

    static String name(ClassModel model) {
        return model.thisClass().asInternalName();
    }

    boolean touchesFork(List<ClassModel> hierarchy) {
        return hierarchy.stream().anyMatch(c -> forkClasses.containsKey(name(c)));
    }

    void checkClass(String consumer) {
        ClassModel model = load(consumer).orElseThrow();
        String where = consumer + " (" + consumers.get(consumer) + ")";
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof MemberRefEntry ref) {
                checkMember(where, consumer, ref);
            } else if (entry instanceof ClassEntry c) {
                String target = c.asInternalName().replaceFirst("^\\[+L?", "").replaceFirst(";$", "");
                if (forkClasses.containsKey(target) || isForkPackage(target)) {
                    references++;
                    if (load(target).isEmpty()) {
                        problems.add(where + " refers to class " + target + ", which the fork does not have");
                    }
                }
            }
        }
        checkInheritance(where, model);
    }

    /** A class in a package the fork provides, which the fork may have removed. */
    boolean isForkPackage(String className) {
        int slash = className.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String pkg = className.substring(0, slash + 1);
        return forkPackages().contains(pkg);
    }

    Set<String> forkPackages;

    Set<String> forkPackages() {
        if (forkPackages == null) {
            forkPackages = new HashSet<>();
            for (String c : forkClasses.keySet()) {
                forkPackages.add(c.substring(0, c.lastIndexOf('/') + 1));
            }
        }
        return forkPackages;
    }

    void checkMember(String where, String consumer, MemberRefEntry ref) {
        String owner = ref.owner().asInternalName();
        if (owner.startsWith("[")) {
            return;
        }
        Optional<ClassModel> ownerModel = load(owner);
        if (ownerModel.isEmpty()) {
            if (isForkPackage(owner)) {
                problems.add(where + " refers to " + owner + "." + ref.name() + ", but the fork has no class " + owner);
            }
            return;
        }
        List<ClassModel> hierarchy = hierarchy(ownerModel.get());
        boolean complete = !incomplete;
        if (!touchesFork(hierarchy)) {
            return;
        }
        references++;
        String name = ref.name().stringValue();
        String type = ref.type().stringValue();
        boolean field = ref.tag() == PoolEntry.TAG_FIELDREF;
        for (ClassModel c : hierarchy) {
            if (field) {
                for (FieldModel f : c.fields()) {
                    if (f.fieldName().equalsString(name) && f.fieldType().equalsString(type)) {
                        checkAccess(where, consumer, c, name, f.flags().has(AccessFlag.PRIVATE));
                        return;
                    }
                }
            } else {
                for (MethodModel m : c.methods()) {
                    if (m.methodName().equalsString(name) && m.methodType().equalsString(type)) {
                        checkAccess(where, consumer, c, name + type, m.flags().has(AccessFlag.PRIVATE));
                        return;
                    }
                }
            }
        }
        if (!complete) {
            unresolvedHierarchies.add(owner);
            return;
        }
        problems.add(where + " refers to " + (field ? "field " : "method ") + owner + "." + name + " " + type +
                        ", which the fork's classes do not have");
    }

    void checkAccess(String where, String consumer, ClassModel declarer, String member, boolean isPrivate) {
        if (isPrivate && !name(declarer).equals(consumer) && forkClasses.containsKey(name(declarer))) {
            problems.add(where + " uses " + name(declarer) + "." + member + ", which is private in the fork");
        }
    }

    void checkInheritance(String where, ClassModel model) {
        List<ClassModel> hierarchy = hierarchy(model);
        boolean complete = !incomplete;
        if (!touchesFork(hierarchy)) {
            return;
        }
        model.superclass().ifPresent(s -> {
            Optional<ClassModel> superModel = load(s.asInternalName());
            if (superModel.isEmpty()) {
                problems.add(where + " extends " + s.asInternalName() + ", which does not exist");
            } else if (superModel.get().flags().has(AccessFlag.FINAL)) {
                problems.add(where + " extends " + s.asInternalName() + ", which is final in the fork");
            }
        });
        for (ClassEntry i : model.interfaces()) {
            if (load(i.asInternalName()).isEmpty()) {
                problems.add(where + " implements " + i.asInternalName() + ", which does not exist");
            }
        }
        // No method may override a final method of a superclass.
        for (MethodModel m : model.methods()) {
            if (m.flags().has(AccessFlag.STATIC) || m.flags().has(AccessFlag.PRIVATE) || m.methodName().stringValue().startsWith("<")) {
                continue;
            }
            for (ClassModel c : hierarchy.subList(1, hierarchy.size())) {
                if (c.flags().has(AccessFlag.INTERFACE)) {
                    continue;
                }
                for (MethodModel s : c.methods()) {
                    if (s.methodName().equalsString(m.methodName().stringValue()) && s.methodType().equalsString(m.methodType().stringValue()) &&
                                    s.flags().has(AccessFlag.FINAL) && !s.flags().has(AccessFlag.PRIVATE)) {
                        problems.add(where + " overrides " + name(c) + "." + m.methodName() + m.methodType() + ", which is final in the fork");
                    }
                }
            }
        }
        // A concrete class must implement every abstract method it inherits.
        if (model.flags().has(AccessFlag.ABSTRACT) || model.flags().has(AccessFlag.INTERFACE)) {
            return;
        }
        if (!complete) {
            unresolvedHierarchies.add(name(model));
            return;
        }
        Set<String> concrete = new HashSet<>();
        Map<String, String> abstractMethods = new HashMap<>();
        for (ClassModel c : hierarchy) {
            boolean isInterface = c.flags().has(AccessFlag.INTERFACE);
            for (MethodModel m : c.methods()) {
                if (m.flags().has(AccessFlag.STATIC) || m.flags().has(AccessFlag.PRIVATE)) {
                    continue;
                }
                String key = m.methodName().stringValue() + m.methodType().stringValue();
                if (m.flags().has(AccessFlag.ABSTRACT)) {
                    abstractMethods.putIfAbsent(key, name(c));
                } else if (!isInterface || m.code().isPresent()) {
                    concrete.add(key);
                }
            }
        }
        abstractMethods.forEach((key, declarer) -> {
            if (!concrete.contains(key)) {
                problems.add(where + " does not implement " + declarer + "." + key);
            }
        });
    }

    /** The fork's upgraded modules must export and open at least what Oracle's modules do. */
    void checkModules(FileSystem jrt, List<Path> forkJars) {
        for (Path jar : forkJars) {
            if (!jar.getParent().getFileName().toString().equals("jvmci")) {
                continue;
            }
            ModuleDescriptor fork = ModuleFinder.of(jar).findAll().iterator().next().descriptor();
            Path moduleInfo = jrt.getPath("/modules", fork.name(), "module-info.class");
            if (!Files.exists(moduleInfo)) {
                problems.add(jar + ": Oracle's build has no module " + fork.name() + " to upgrade");
                continue;
            }
            ModuleDescriptor theirs;
            try (InputStream in = Files.newInputStream(moduleInfo)) {
                theirs = ModuleDescriptor.read(in);
            } catch (IOException e) {
                throw new RuntimeException(moduleInfo.toString(), e);
            }
            for (ModuleDescriptor.Exports e : theirs.exports()) {
                boolean kept = fork.exports().stream().anyMatch(f -> f.source().equals(e.source()) &&
                                (!f.isQualified() || (e.isQualified() && f.targets().containsAll(e.targets()))));
                if (!kept) {
                    problems.add(fork.name() + " no longer exports " + e);
                }
            }
            for (ModuleDescriptor.Opens o : theirs.opens()) {
                boolean kept = fork.opens().stream().anyMatch(f -> f.source().equals(o.source()) &&
                                (!f.isQualified() || (o.isQualified() && f.targets().containsAll(o.targets()))));
                if (!kept) {
                    problems.add(fork.name() + " no longer opens " + o);
                }
            }
            for (ModuleDescriptor.Provides p : theirs.provides()) {
                boolean kept = fork.provides().stream().anyMatch(f -> f.service().equals(p.service()));
                if (!kept) {
                    problems.add(fork.name() + " no longer provides " + p.service());
                }
            }
        }
    }
}
