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
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * Runtime-loaded classes with wide dispatch tables. An image holds a fixed number of interpreter
 * dispatch stubs, and with interface hashing an itable offset must fit a short. A class that
 * exceeds either limit must fail to load with a {@link LinkageError} that the program can handle,
 * not take the VM down (elide-dev/runtime#2163).
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
}
