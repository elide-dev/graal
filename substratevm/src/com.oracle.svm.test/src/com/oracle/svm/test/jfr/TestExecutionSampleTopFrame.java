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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.oracle.svm.core.jfr.JfrEvent;
import com.oracle.svm.shared.NeverInline;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;

/**
 * Execution samples must show the interrupted Java code at the top of the stack trace, not the
 * safepoint slowpath and recurring callback frames that take the sample (elide-dev/graal#104).
 */
public class TestExecutionSampleTopFrame extends JfrRecordingTest {
    private static final Duration SAMPLE_PERIOD = Duration.ofMillis(10);
    private static final String SPIN_METHOD = "spin";

    private final AtomicLong spinningThreadId = new AtomicLong();

    @Test
    public void test() throws Throwable {
        String[] events = new String[]{JfrEvent.ExecutionSample.getName()};
        Recording recording = prepareRecording(events, getDefaultConfiguration(), Map.of("flush-interval", "0"), createTempJfrFile());
        recording.enable(JfrEvent.ExecutionSample.getName()).withPeriod(SAMPLE_PERIOD);
        recording.start();

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            spinningThreadId.set(Thread.currentThread().threadId());
            spin(stop);
        });
        thread.start();
        Thread.sleep(1000);
        stop.set(true);
        thread.join();

        stopRecording(recording, this::validateEvents);
    }

    private void validateEvents(List<RecordedEvent> events) {
        int samples = 0;
        for (RecordedEvent event : events) {
            RecordedThread sampledThread = event.getThread("sampledThread");
            if (sampledThread == null || sampledThread.getJavaThreadId() != spinningThreadId.get() || !containsSpin(event)) {
                continue;
            }
            samples++;
            List<RecordedFrame> frames = event.getStackTrace().getFrames();
            if (!hasOnlyJavaFramesAboveSpin(frames)) {
                StringBuilder stack = new StringBuilder();
                for (RecordedFrame frame : frames) {
                    stack.append(System.lineSeparator()).append("  ").append(frame.getMethod().getType().getName()).append('.').append(frame.getMethod().getName());
                }
                fail("Expected " + SPIN_METHOD + " at the top of the sample, but found:" + stack);
            }
        }
        assertTrue("Expected execution samples of the spinning thread.", samples > 0);
    }

    /** Frames inlined into {@code spin} (e.g., {@code AtomicBoolean.get}) may be on top of it. */
    private static boolean hasOnlyJavaFramesAboveSpin(List<RecordedFrame> frames) {
        for (RecordedFrame frame : frames) {
            String type = frame.getMethod().getType().getName();
            if (frame.getMethod().getName().equals(SPIN_METHOD) && type.equals(TestExecutionSampleTopFrame.class.getName())) {
                return true;
            }
            if (type.startsWith("com.oracle.svm.core.")) {
                return false;
            }
        }
        return false;
    }

    private static boolean containsSpin(RecordedEvent event) {
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            if (frame.getMethod().getName().equals(SPIN_METHOD) && frame.getMethod().getType().getName().equals(TestExecutionSampleTopFrame.class.getName())) {
                return true;
            }
        }
        return false;
    }

    @NeverInline("Must be the top frame of the samples.")
    private static long spin(AtomicBoolean stop) {
        long value = 0;
        while (!stop.get()) {
            value = value * 31 + 7;
        }
        return value;
    }
}
