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

import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CLASS_INIT_NAME;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

/**
 * A class loaded at run time calls the core reflection entry points {@link Method#invoke},
 * {@link Field#getInt} and {@link Constructor#newInstance}. These methods are {@code @ForceInline}
 * in the JDK, so every image caller inlines them, and the compile queue would not compile them on
 * their own. The interpreter can only call an image method through its compiled code, so the image
 * must keep compiled code for such methods (elide-dev/graal#161). The test registers neither these
 * methods nor anything else as roots or for reflection.
 */
@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
})
public class RuntimeClassLoadingReflectionEntryPointsTest {
    private static final String CALLS_NAME = "com.oracle.svm.test.generated.reflection.ReflectionCalls";
    private static final ClassDesc CD_Method = ClassDesc.of(Method.class.getName());
    private static final ClassDesc CD_Field = ClassDesc.of(Field.class.getName());
    private static final ClassDesc CD_Constructor = ClassDesc.of(Constructor.class.getName());
    private static final ClassDesc CD_Object_array = CD_Object.arrayType();

    /** Defines the generated class at run time. */
    private static final class GeneratedClassLoader extends ClassLoader {
        private final byte[] classFile = generateReflectionCalls();

        GeneratedClassLoader() {
            super(RuntimeClassLoadingReflectionEntryPointsTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!CALLS_NAME.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    public void testRuntimeLoadedCodeCallsReflectionEntryPoints() throws Exception {
        Class<?> calls = Class.forName(CALLS_NAME, true, new GeneratedClassLoader());
        Method suffix = calls.getMethod("suffix", String.class);
        Field value = calls.getField("VALUE");
        Constructor<?> constructor = calls.getConstructor();

        /* The same calls from image code. */
        Assert.assertEquals("image!", suffix.invoke(null, "image"));
        Assert.assertEquals(42, value.getInt(null));
        Assert.assertSame(calls, constructor.newInstance().getClass());

        /* The calls from the run-time loaded class. */
        Method invoke = calls.getMethod("invoke", Method.class, Object.class, Object[].class);
        Method getInt = calls.getMethod("getInt", Field.class, Object.class);
        Method newInstance = calls.getMethod("newInstance", Constructor.class, Object[].class);
        Assert.assertEquals("guest!", invoke.invoke(null, suffix, null, new Object[]{"guest"}));
        Assert.assertEquals(42, getInt.invoke(null, value, null));
        Assert.assertSame(calls, newInstance.invoke(null, constructor, new Object[0]).getClass());
    }

    /**
     * Generates the following class.
     *
     * <pre>
     * public class ReflectionCalls {
     *     public static int VALUE = 42;
     *
     *     public static String suffix(String s) { return s.concat("!"); }
     *     public static Object invoke(Method m, Object receiver, Object[] args) { return m.invoke(receiver, args); }
     *     public static int getInt(Field f, Object receiver) { return f.getInt(receiver); }
     *     public static Object newInstance(Constructor c, Object[] args) { return c.newInstance(args); }
     * }
     * </pre>
     */
    private static byte[] generateReflectionCalls() {
        ClassDesc self = ClassDesc.of(CALLS_NAME);
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withField("VALUE", CD_int, ACC_PUBLIC | ACC_STATIC);
            cb.withMethodBody(CLASS_INIT_NAME, MTD_void, ACC_STATIC, code -> code.bipush(42).putstatic(self, "VALUE", CD_int).return_());
            cb.withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, code -> code.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void).return_());
            cb.withMethodBody("suffix", MethodTypeDesc.of(CD_String, CD_String), ACC_PUBLIC | ACC_STATIC,
                            code -> code.aload(0).ldc("!").invokevirtual(CD_String, "concat", MethodTypeDesc.of(CD_String, CD_String)).areturn());
            cb.withMethodBody("invoke", MethodTypeDesc.of(CD_Object, CD_Method, CD_Object, CD_Object_array), ACC_PUBLIC | ACC_STATIC,
                            code -> code.aload(0).aload(1).aload(2).invokevirtual(CD_Method, "invoke", MethodTypeDesc.of(CD_Object, CD_Object, CD_Object_array)).areturn());
            cb.withMethodBody("getInt", MethodTypeDesc.of(CD_int, CD_Field, CD_Object), ACC_PUBLIC | ACC_STATIC,
                            code -> code.aload(0).aload(1).invokevirtual(CD_Field, "getInt", MethodTypeDesc.of(CD_int, CD_Object)).ireturn());
            cb.withMethodBody("newInstance", MethodTypeDesc.of(CD_Object, CD_Constructor, CD_Object_array), ACC_PUBLIC | ACC_STATIC,
                            code -> code.aload(0).aload(1).invokevirtual(CD_Constructor, "newInstance", MethodTypeDesc.of(CD_Object, CD_Object_array)).areturn());
        });
    }
}
