/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.test.aot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.oracle.truffle.runtime.BuildTimeCompilationSupport;

public class BuildTimeSelectionTest {

    @After
    public void clear() throws Exception {
        for (String p : List.of("PreinitializeCodeSharing", "PreinitializeSources", "AOTCompile", "AOTCompileFilter", "PreinitializeContexts")) {
            System.clearProperty("polyglot.image-build-time." + p);
        }
        var resetMethod = Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("resetPreInitializedEngine");
        resetMethod.setAccessible(true);
        resetMethod.invoke(null);
    }

    private static BuildTimeCompilationSupport.Selection preinit(String sources, String filter) throws Exception {
        System.setProperty("polyglot.image-build-time.PreinitializeContexts", AOTTestLanguage.ID);
        System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
        System.setProperty("polyglot.image-build-time.PreinitializeSources", sources);
        System.setProperty("polyglot.image-build-time.AOTCompile", "true");
        System.setProperty("polyglot.image-build-time.AOTCompileFilter", filter);
        var m = Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("preInitializeEngine");
        m.setAccessible(true);
        m.invoke(null);
        return BuildTimeCompilationSupport.selectPreinitialized();
    }

    private static String files(String... codes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            Path f = Files.createTempFile("aot", ".txt");
            Files.writeString(f, code);
            f.toFile().deleteOnExit();
            sb.append(sb.isEmpty() ? "" : ",").append(AOTTestLanguage.ID).append(':').append(f);
        }
        return sb.toString();
    }

    @Test
    public void selectsPreparableAndReportsRefusal() throws Exception {
        var selection = preinit(files("add:1", "refuse"), "");
        assertEquals(List.of("add1"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("refuse", "root refused AOT (cloneForAOT or prepareForAOT)")));
        selection.selected().forEach(p -> assertTrue(p.shadow().isAOTShadow()));
    }

    @Test
    public void filterRejects() throws Exception {
        var selection = preinit(files("add:1", "add:2"), "add2");
        assertEquals(List.of("add2"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("add1", "filtered")));
    }
}
