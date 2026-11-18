/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.test;

import static java.lang.classfile.ClassFile.ACC_ABSTRACT;
import static java.lang.classfile.ClassFile.ACC_INTERFACE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * Runtime-loaded classes with wide dispatch tables. An image holds a fixed number of interpreter
 * dispatch stubs, and with interface hashing an itable offset must fit a short. A class that
 * exceeds either limit must fail to load with a {@link LinkageError} that the program can handle,
 * not take the VM down (elide-dev/runtime#2163). Default methods that a class inherits must take one
 * vtable slot each, not one per declaring interface and per subclass (elide-dev/graal#184).
 */
@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
})
public class RuntimeClassLoadingDispatchTableTest {
    private static final String PACKAGE = "com.oracle.svm.test.generated.dispatch";
    private static final int METHODS_PER_INTERFACE = 150;

    /** Defines generated classes at run time, so that Crema builds their dispatch tables. */
    private static final class GeneratedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classFiles;

        GeneratedClassLoader(Map<String, byte[]> classFiles) {
            super(RuntimeClassLoadingDispatchTableTest.class.getClassLoader());
            this.classFiles = classFiles;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classFiles.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * A class implementing 14 interfaces of 150 methods each, which is what the downstream report
     * used: 2,250 dispatch entries, within the limits.
     */
    @Test
    public void testWideInterfaceDispatchTableLoads() throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader(generateWideClass("Wide14", 14));
        Class<?> clazz = Class.forName(PACKAGE + ".Wide14", true, loader);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Assert.assertEquals(14, clazz.getInterfaces().length);
        Assert.assertEquals(149, clazz.getMethod("method149").invoke(instance));
        for (Class<?> iface : clazz.getInterfaces()) {
            Assert.assertEquals(7, iface.getMethod("method7").invoke(instance));
        }
    }

    /**
     * Five interfaces, each extending the previous one and redeclaring the same 150 default
     * methods, implemented by a chain of six abstract classes that do not override them, with two
     * concrete leaves. This is the shape of Micronaut's element types, which implement several
     * interfaces that all redeclare the defaults of {@code AnnotationMetadata}. Each inherited
     * default needs one vtable slot. When every declaring interface and every subclass appended
     * another slot for it, the leaf's dispatch table grew to 4,200+ entries and failed to link.
     */
    @Test
    public void testInheritedDefaultMethodsShareOneSlot() throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader(generateDefaultsHierarchy());
        Class<?> caller = Class.forName(PACKAGE + ".DefaultsCaller", true, loader);
        Class<?> base = Class.forName(PACKAGE + ".Defaults0", true, loader);
        Class<?> midClass = Class.forName(PACKAGE + ".DefaultsMid", true, loader);
        Class<?> leafClass = Class.forName(PACKAGE + ".DefaultsLeaf", true, loader);
        Object mid = midClass.getDeclaredConstructor().newInstance();
        Object leaf = leafClass.getDeclaredConstructor().newInstance();
        for (int m : CALLED_DEFAULTS) {
            /* The abstract classes implement Defaults2; the leaf adds the more specific Defaults4. */
            Assert.assertEquals(defaultValue(2, m), caller.getMethod("virtualMid" + m, midClass).invoke(null, mid));
            Assert.assertEquals(defaultValue(4, m), caller.getMethod("virtualLeaf" + m, leafClass).invoke(null, leaf));
            Assert.assertEquals(defaultValue(2, m), caller.getMethod("interface" + m, base).invoke(null, mid));
            Assert.assertEquals(defaultValue(4, m), caller.getMethod("interface" + m, base).invoke(null, leaf));
            Assert.assertEquals(defaultValue(4, m), base.getMethod(methodName(m)).invoke(leaf));
        }
    }

    /**
     * A concrete class that inherits, through an abstract superclass, two conflicting defaults and
     * an abstract interface method it does not implement. Reusing the superclass's slots must keep
     * the errors that invoking them raises.
     */
    @Test
    public void testInheritedImplicitMethodErrors() throws Exception {
        GeneratedClassLoader loader = new GeneratedClassLoader(generateImplicitErrorsHierarchy());
        Class<?> caller = Class.forName(PACKAGE + ".ErrorsCaller", true, loader);
        Class<?> concrete = Class.forName(PACKAGE + ".ErrorsConcrete", true, loader);
        Object instance = concrete.getDeclaredConstructor().newInstance();
        for (String call : new String[]{"virtualConflict", "interfaceConflict"}) {
            InvocationTargetException e = Assert.assertThrows(InvocationTargetException.class, () -> caller.getMethod(call, concrete).invoke(null, instance));
            Assert.assertEquals(call, IncompatibleClassChangeError.class, e.getCause().getClass());
        }
        for (String call : new String[]{"virtualMissing", "interfaceMissing"}) {
            InvocationTargetException e = Assert.assertThrows(InvocationTargetException.class, () -> caller.getMethod(call, concrete).invoke(null, instance));
            Assert.assertEquals(call, AbstractMethodError.class, e.getCause().getClass());
        }
    }

    /** More virtual methods than the image has interpreter dispatch stubs for. */
    @Test
    public void testTooManyVirtualMethodsFailsToLink() {
        GeneratedClassLoader loader = new GeneratedClassLoader(generateTallClass("Tall", 4200));
        LinkageError error = Assert.assertThrows(LinkageError.class, () -> Class.forName(PACKAGE + ".Tall", true, loader));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains("Cannot link class " + PACKAGE + ".Tall"));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains("dispatch table"));
        /* The loader must still work, so a program can recover. */
        Assert.assertThrows(LinkageError.class, () -> Class.forName(PACKAGE + ".Tall", true, loader));
    }

    /**
     * 28 interfaces of 150 methods: 4,350 dispatch entries, beyond the interpreter stubs, and with
     * interface hashing also beyond the itable offsets a hub can encode.
     */
    @Test
    public void testTooWideInterfaceDispatchTableFailsToLink() {
        GeneratedClassLoader loader = new GeneratedClassLoader(generateWideClass("Wide28", 28));
        LinkageError error = Assert.assertThrows(LinkageError.class, () -> Class.forName(PACKAGE + ".Wide28", true, loader));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains("Cannot link class " + PACKAGE + ".Wide28"));
        Assert.assertTrue(error.getMessage(), error.getMessage().contains("dispatch table"));
    }

    private static MethodTypeDesc intMethod() {
        return MethodTypeDesc.of(CD_int);
    }

    private static String methodName(int index) {
        return "method" + index;
    }

    /** A class with {@code interfaceCount} interfaces, each declaring the same 150 methods. */
    private static Map<String, byte[]> generateWideClass(String simpleName, int interfaceCount) {
        Map<String, byte[]> classFiles = new HashMap<>();
        ClassDesc[] interfaces = new ClassDesc[interfaceCount];
        for (int i = 0; i < interfaceCount; i++) {
            String interfaceName = PACKAGE + "." + simpleName + "Dispatch" + i;
            interfaces[i] = ClassDesc.of(interfaceName);
            ClassDesc interfaceDesc = interfaces[i];
            classFiles.put(interfaceName, ClassFile.of().build(interfaceDesc, cb -> {
                cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
                cb.withSuperclass(CD_Object);
                for (int m = 0; m < METHODS_PER_INTERFACE; m++) {
                    cb.withMethod(methodName(m), intMethod(), ACC_PUBLIC | ACC_ABSTRACT, mb -> {
                    });
                }
            }));
        }
        String className = PACKAGE + "." + simpleName;
        classFiles.put(className, ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            cb.withInterfaceSymbols(interfaces);
            cb.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            for (int m = 0; m < METHODS_PER_INTERFACE; m++) {
                int value = m;
                cb.withMethodBody(methodName(m), intMethod(), ACC_PUBLIC, code -> code.loadConstant(value).ireturn());
            }
        }));
        return classFiles;
    }

    /** A class with {@code methodCount} virtual methods and no interfaces. */
    private static Map<String, byte[]> generateTallClass(String simpleName, int methodCount) {
        String className = PACKAGE + "." + simpleName;
        byte[] classFile = ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            cb.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            for (int m = 0; m < methodCount; m++) {
                int value = m;
                cb.withMethodBody(methodName(m), intMethod(), ACC_PUBLIC, code -> code.loadConstant(value).ireturn());
            }
        });
        return Map.of(className, classFile);
    }

    private static final int DEFAULT_INTERFACES = 5;
    private static final int ABSTRACT_CLASSES = 6;
    private static final int[] CALLED_DEFAULTS = {0, 75, METHODS_PER_INTERFACE - 1};

    private static int defaultValue(int interfaceIndex, int method) {
        return interfaceIndex * 1000 + method;
    }

    /**
     * Interfaces {@code Defaults0..4}, each extending the previous one and redeclaring the same 150
     * default methods; abstract classes {@code DefaultsAbstract0..5}, the first implementing
     * {@code Defaults2}; {@code DefaultsMid} and {@code DefaultsLeaf} (which also implements
     * {@code Defaults4}) extending the last; and {@code DefaultsCaller}, which invokes some of the
     * defaults virtually and through {@code Defaults0}.
     */
    private static Map<String, byte[]> generateDefaultsHierarchy() {
        Map<String, byte[]> classFiles = new HashMap<>();
        ClassDesc[] interfaces = new ClassDesc[DEFAULT_INTERFACES];
        for (int i = 0; i < DEFAULT_INTERFACES; i++) {
            interfaces[i] = ClassDesc.of(PACKAGE + ".Defaults" + i);
            ClassDesc superInterface = i == 0 ? null : interfaces[i - 1];
            int interfaceIndex = i;
            classFiles.put(PACKAGE + ".Defaults" + i, ClassFile.of().build(interfaces[i], cb -> {
                cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
                cb.withSuperclass(CD_Object);
                if (superInterface != null) {
                    cb.withInterfaceSymbols(superInterface);
                }
                for (int m = 0; m < METHODS_PER_INTERFACE; m++) {
                    int value = defaultValue(interfaceIndex, m);
                    cb.withMethodBody(methodName(m), intMethod(), ACC_PUBLIC, code -> code.loadConstant(value).ireturn());
                }
            }));
        }
        ClassDesc superClass = CD_Object;
        for (int i = 0; i < ABSTRACT_CLASSES; i++) {
            ClassDesc abstractClass = ClassDesc.of(PACKAGE + ".DefaultsAbstract" + i);
            ClassDesc[] direct = i == 0 ? new ClassDesc[]{interfaces[2]} : new ClassDesc[0];
            classFiles.put(PACKAGE + ".DefaultsAbstract" + i, generateSubclass(abstractClass, superClass, ACC_ABSTRACT, direct));
            superClass = abstractClass;
        }
        ClassDesc mid = ClassDesc.of(PACKAGE + ".DefaultsMid");
        ClassDesc leaf = ClassDesc.of(PACKAGE + ".DefaultsLeaf");
        classFiles.put(PACKAGE + ".DefaultsMid", generateSubclass(mid, superClass, 0));
        classFiles.put(PACKAGE + ".DefaultsLeaf", generateSubclass(leaf, superClass, 0, interfaces[DEFAULT_INTERFACES - 1]));
        ClassDesc caller = ClassDesc.of(PACKAGE + ".DefaultsCaller");
        classFiles.put(PACKAGE + ".DefaultsCaller", ClassFile.of().build(caller, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            for (int m : CALLED_DEFAULTS) {
                String name = methodName(m);
                cb.withMethodBody("virtualMid" + m, MethodTypeDesc.of(CD_int, mid), ACC_PUBLIC | ACC_STATIC,
                                code -> code.aload(0).invokevirtual(mid, name, intMethod()).ireturn());
                cb.withMethodBody("virtualLeaf" + m, MethodTypeDesc.of(CD_int, leaf), ACC_PUBLIC | ACC_STATIC,
                                code -> code.aload(0).invokevirtual(leaf, name, intMethod()).ireturn());
                cb.withMethodBody("interface" + m, MethodTypeDesc.of(CD_int, interfaces[0]), ACC_PUBLIC | ACC_STATIC,
                                code -> code.aload(0).invokeinterface(interfaces[0], name, intMethod()).ireturn());
            }
        }));
        return classFiles;
    }

    /**
     * Interfaces {@code ErrorsLeft} and {@code ErrorsRight}, both with a default {@code conflict()},
     * and {@code ErrorsMissing} with an abstract {@code missing()}; abstract classes
     * {@code ErrorsAbstract0} (implementing all three) and {@code ErrorsAbstract1};
     * {@code ErrorsConcrete} extending the latter; and {@code ErrorsCaller}.
     */
    private static Map<String, byte[]> generateImplicitErrorsHierarchy() {
        Map<String, byte[]> classFiles = new HashMap<>();
        ClassDesc left = ClassDesc.of(PACKAGE + ".ErrorsLeft");
        ClassDesc right = ClassDesc.of(PACKAGE + ".ErrorsRight");
        ClassDesc missing = ClassDesc.of(PACKAGE + ".ErrorsMissing");
        for (ClassDesc conflicting : new ClassDesc[]{left, right}) {
            classFiles.put(PACKAGE + "." + conflicting.displayName(), ClassFile.of().build(conflicting, cb -> {
                cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
                cb.withSuperclass(CD_Object);
                cb.withMethodBody("conflict", intMethod(), ACC_PUBLIC, code -> code.loadConstant(1).ireturn());
            }));
        }
        classFiles.put(PACKAGE + ".ErrorsMissing", ClassFile.of().build(missing, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
            cb.withSuperclass(CD_Object);
            cb.withMethod("missing", intMethod(), ACC_PUBLIC | ACC_ABSTRACT, mb -> {
            });
        }));
        ClassDesc abstract0 = ClassDesc.of(PACKAGE + ".ErrorsAbstract0");
        ClassDesc abstract1 = ClassDesc.of(PACKAGE + ".ErrorsAbstract1");
        ClassDesc concrete = ClassDesc.of(PACKAGE + ".ErrorsConcrete");
        classFiles.put(PACKAGE + ".ErrorsAbstract0", generateSubclass(abstract0, CD_Object, ACC_ABSTRACT, left, right, missing));
        classFiles.put(PACKAGE + ".ErrorsAbstract1", generateSubclass(abstract1, abstract0, ACC_ABSTRACT));
        classFiles.put(PACKAGE + ".ErrorsConcrete", generateSubclass(concrete, abstract1, 0));
        ClassDesc caller = ClassDesc.of(PACKAGE + ".ErrorsCaller");
        MethodTypeDesc callType = MethodTypeDesc.of(CD_int, concrete);
        classFiles.put(PACKAGE + ".ErrorsCaller", ClassFile.of().build(caller, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            cb.withMethodBody("virtualConflict", callType, ACC_PUBLIC | ACC_STATIC, code -> code.aload(0).invokevirtual(concrete, "conflict", intMethod()).ireturn());
            cb.withMethodBody("interfaceConflict", callType, ACC_PUBLIC | ACC_STATIC, code -> code.aload(0).invokeinterface(left, "conflict", intMethod()).ireturn());
            cb.withMethodBody("virtualMissing", callType, ACC_PUBLIC | ACC_STATIC, code -> code.aload(0).invokevirtual(concrete, "missing", intMethod()).ireturn());
            cb.withMethodBody("interfaceMissing", callType, ACC_PUBLIC | ACC_STATIC, code -> code.aload(0).invokeinterface(missing, "missing", intMethod()).ireturn());
        }));
        return classFiles;
    }

    /** A public class with only a default constructor and the given direct interfaces. */
    private static byte[] generateSubclass(ClassDesc thisClass, ClassDesc superClass, int extraFlags, ClassDesc... superInterfaces) {
        return ClassFile.of().build(thisClass, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER | extraFlags);
            cb.withSuperclass(superClass);
            cb.withInterfaceSymbols(superInterfaces);
            cb.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, code -> code.aload(0).invokespecial(superClass, INIT_NAME, MTD_void).return_());
        });
    }
}
