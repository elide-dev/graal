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

package com.oracle.svm.test.jfr;

import static java.lang.classfile.ClassFile.ACC_PRIVATE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Array;
import java.util.List;

import org.graalvm.nativeimage.ImageInfo;
import org.junit.Test;

import com.oracle.svm.core.genscavenge.HeapParameters;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.RuntimeClassLoading;
import com.oracle.svm.core.hub.registry.TypeIDs;
import com.oracle.svm.core.jfr.JfrEvent;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.util.UnsignedUtils;
import com.oracle.svm.test.NativeImageBuildArgs;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * Classes that are loaded at run time get type ids above the image-time maximum. JFR events that
 * reference such a class (as monitor class, allocated class, ...) must still be able to look up its
 * trace id and the class must be written to the constant pool of the recording.
 */
@NativeImageBuildArgs({
                "-H:+UnlockExperimentalVMOptions",
                "-H:+RuntimeClassLoading",
})
public class TestRuntimeLoadedClassEvents extends JfrRecordingTest {
    private static final String CLASS_NAME = "com.oracle.svm.test.jfr.runtime.JfrRuntimeLoadedClass";
    private static final String ARRAY_CLASS_NAME = "[L" + CLASS_NAME + ";";
    private static final String ALLOCATE_METHOD_NAME = "allocate";
    private static final int MILLIS = 60;

    private Thread firstThread;
    private Thread secondThread;
    private volatile boolean passedCheckpoint;

    @Test
    public void test() throws Throwable {
        assumeTrue("runtime class loading is not supported", !ImageInfo.inImageCode() || RuntimeClassLoading.isSupported());

        Class<?> clazz = new RuntimeClassLoader().loadClass(CLASS_NAME);
        assertNotSame(getClass().getClassLoader(), clazz.getClassLoader());
        if (ImageInfo.inImageCode()) {
            assertTrue("class must have a run-time type id", DynamicHub.fromClass(clazz).getTypeID() >= TypeIDs.singleton().getFirstRuntimeTypeId());
        }
        Object monitor = clazz.getDeclaredConstructor().newInstance();

        String[] events = new String[]{JfrEvent.JavaMonitorEnter.getName(), JfrEvent.ObjectAllocationInNewTLAB.getName(), JfrEvent.ObjectAllocationOutsideTLAB.getName()};
        Recording recording = startRecording(events);

        int alignedHeapChunkSize = ImageInfo.inImageCode() ? UnsignedUtils.safeToInt(HeapParameters.getAlignedHeapChunkSize()) : 1024 * 1024;
        /* Large arrays of the run-time loaded class are allocated outside of the TLAB. */
        allocateArray(clazz, 2 * alignedHeapChunkSize);
        /* Exhaust the TLAB with instances that the run-time loaded class allocates itself. */
        clazz.getMethod(ALLOCATE_METHOD_NAME, int.class).invoke(null, alignedHeapChunkSize / 16);

        firstThread = new Thread(() -> doWork(monitor));
        secondThread = new Thread(() -> {
            passedCheckpoint = true;
            doWork(monitor);
        });
        /* Start the first thread so that it can then start the second thread. */
        firstThread.start();
        firstThread.join();
        secondThread.join();

        stopRecording(recording, this::validateEvents);
    }

    private void validateEvents(List<RecordedEvent> events) {
        boolean foundMonitorEnter = false;
        boolean foundInstanceAllocation = false;
        boolean foundArrayAllocation = false;
        for (RecordedEvent event : events) {
            String eventName = event.getEventType().getName();
            String eventThread = event.<RecordedThread> getValue("eventThread").getJavaName();
            if (eventName.equals(JfrEvent.JavaMonitorEnter.getName())) {
                RecordedClass monitorClass = event.getValue("monitorClass");
                if (monitorClass.getName().equals(CLASS_NAME) && event.getDuration().toMillis() >= MILLIS && secondThread.getName().equals(eventThread)) {
                    assertEquals("Previous owner is wrong", firstThread.getName(), event.<RecordedThread> getValue("previousOwner").getJavaName());
                    assertRuntimeLoaded(monitorClass);
                    foundMonitorEnter = true;
                }
            } else if (eventThread.equals("main")) {
                RecordedClass objectClass = event.getValue("objectClass");
                if (objectClass.getName().equals(CLASS_NAME)) {
                    assertRuntimeLoaded(objectClass);
                    foundInstanceAllocation = true;
                } else if (objectClass.getName().equals(ARRAY_CLASS_NAME)) {
                    assertRuntimeLoaded(objectClass);
                    foundArrayAllocation = true;
                }
            }
        }
        assertTrue("Expected monitor blocked event for the run-time loaded class not found", foundMonitorEnter);
        assertTrue("Expected allocation event for the run-time loaded class not found", foundInstanceAllocation);
        assertTrue("Expected allocation event for the array of the run-time loaded class not found", foundArrayAllocation);
    }

    private static void assertRuntimeLoaded(RecordedClass clazz) {
        assertEquals(RuntimeClassLoader.class.getName(), clazz.getClassLoader().getType().getName());
    }

    private void doWork(Object monitor) {
        synchronized (monitor) {
            if (Thread.currentThread().equals(secondThread)) {
                return; // second thread doesn't need to do work.
            }
            try {
                // ensure ordering of critical section entry
                secondThread.start();

                // spin until second thread blocks
                while (!secondThread.getState().equals(Thread.State.BLOCKED) || !passedCheckpoint) {
                    Thread.sleep(10);
                }
                Thread.sleep(MILLIS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @NeverInline("Prevent escape analysis.")
    private static Object allocateArray(Class<?> componentType, int length) {
        return Array.newInstance(componentType, length);
    }

    /** Defines a class that is not part of the image. Each loader instance defines its own copy. */
    private static final class RuntimeClassLoader extends ClassLoader {
        RuntimeClassLoader() {
            super(TestRuntimeLoadedClassEvents.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(CLASS_NAME)) {
                throw new ClassNotFoundException(name);
            }
            byte[] classFile = generateClassBytes(name);
            return defineClass(name, classFile, 0, classFile.length);
        }

        /**
         * Generates the class file of the run-time loaded class. Its source equivalent is:
         *
         * <pre>
         * public class JfrRuntimeLoadedClass {
         *     private long f0, f1, f2, f3;
         *
         *     public static void allocate(int count) {
         *         for (int i = 0; i &lt; count; i++) {
         *             new JfrRuntimeLoadedClass();
         *         }
         *     }
         * }
         * </pre>
         */
        private static byte[] generateClassBytes(String className) {
            ClassDesc self = ClassDesc.of(className);
            // @formatter:off
            return ClassFile.of().build(self, classBuilder -> classBuilder
                            .withField("f0", CD_long, ACC_PRIVATE)
                            .withField("f1", CD_long, ACC_PRIVATE)
                            .withField("f2", CD_long, ACC_PRIVATE)
                            .withField("f3", CD_long, ACC_PRIVATE)
                            .withMethodBody(INIT_NAME, MTD_void, ACC_PUBLIC, b -> b
                                            .aload(0)
                                            .invokespecial(CD_Object, INIT_NAME, MTD_void)
                                            .return_())
                            .withMethodBody(ALLOCATE_METHOD_NAME, MethodTypeDesc.of(CD_void, CD_int), ACC_PUBLIC | ACC_STATIC, b -> {
                                Label loop = b.newLabel();
                                Label end = b.newLabel();
                                b.iconst_0().istore(1)
                                                .labelBinding(loop)
                                                .iload(1).iload(0).if_icmpge(end)
                                                .new_(self).dup().invokespecial(self, INIT_NAME, MTD_void).pop()
                                                .iinc(1, 1).goto_(loop)
                                                .labelBinding(end)
                                                .return_();
                            }));
            // @formatter:on
        }
    }
}
