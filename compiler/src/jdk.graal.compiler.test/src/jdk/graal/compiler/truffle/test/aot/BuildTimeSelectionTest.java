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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Test;

import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.BuildTimeCompilationSupport;
import com.oracle.truffle.runtime.OptimizedCallTarget;

public class BuildTimeSelectionTest {

    @After
    public void clear() throws Exception {
        for (String p : List.of("PreinitializeCodeSharing", "PreinitializeSources", "AOTCompile", "AOTCompileFilter", "AOTCompileTotalBudget", "PreinitializeContexts")) {
            System.clearProperty("polyglot.image-build-time." + p);
        }
        resetPreInitializedEngine();
    }

    private static void resetPreInitializedEngine() throws Exception {
        var resetMethod = Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("resetPreInitializedEngine");
        resetMethod.setAccessible(true);
        resetMethod.invoke(null);
    }

    private static BuildTimeCompilationSupport.Selection preinit(String languageId, String sources, String filter) throws Exception {
        return preinit(languageId, sources, filter, true);
    }

    private static BuildTimeCompilationSupport.Selection preinit(String languageId, String sources, String filter, boolean codeSharing) throws Exception {
        System.setProperty("polyglot.image-build-time.PreinitializeContexts", languageId);
        if (codeSharing) {
            System.setProperty("polyglot.image-build-time.PreinitializeCodeSharing", "true");
        }
        System.setProperty("polyglot.image-build-time.PreinitializeSources", sources);
        System.setProperty("polyglot.image-build-time.AOTCompile", "true");
        System.setProperty("polyglot.image-build-time.AOTCompileFilter", filter);
        var m = Class.forName("org.graalvm.polyglot.Engine$ImplHolder").getDeclaredMethod("preInitializeEngine");
        m.setAccessible(true);
        m.invoke(null);
        return BuildTimeCompilationSupport.selectPreinitialized();
    }

    private static BuildTimeCompilationSupport.Selection preinit(String sources, String filter) throws Exception {
        return preinit(AOTTestLanguage.ID, sources, filter);
    }

    private static String filesFor(String languageId, String... codes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            Path f = Files.createTempFile("aot", ".txt");
            Files.writeString(f, code);
            f.toFile().deleteOnExit();
            sb.append(sb.isEmpty() ? "" : ",").append(languageId).append(':').append(f);
        }
        return sb.toString();
    }

    private static String files(String... codes) throws Exception {
        return filesFor(AOTTestLanguage.ID, codes);
    }

    @Test
    public void selectsPreparableAndReportsRefusal() throws Exception {
        var selection = preinit(files("add:1", "refuse"), "");
        assertEquals(List.of("add1"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("refuse", "root refused AOT (cloneForAOT or prepareForAOT)")));
        selection.selected().forEach(p -> assertTrue(p.shadow().isAOTShadow()));
    }

    /**
     * Roots without a guest language are not guest code, so the selection skips them silently: the
     * engine creates a {@code RootNode.createConstantNode} root in the shared layer of every
     * pre-initialized context (PolyglotEngineImpl.ensureRuntimeInitialized), which must be neither
     * selected nor rejected.
     */
    @Test
    public void rootsWithoutLanguageAreNotSelectedOrRejected() throws Exception {
        var selection = preinit(files("add:1", "refuse"), "");
        assertEquals(List.of("add1"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertEquals(List.of(new BuildTimeCompilationSupport.Rejection("refuse", "root refused AOT (cloneForAOT or prepareForAOT)")), selection.rejected());
    }

    /**
     * Without code sharing, the roots of the pre-initialized context are selected, and its language
     * stays single-context, so the context that adopts it at run time keeps single-context
     * compilation.
     */
    @Test
    public void boundPreinitializationSelectsContextRoots() throws Exception {
        AOTTestLanguage.multipleContexts = false;
        var selection = preinit(AOTTestLanguage.ID, files("add:1", "refuse"), "", false);
        assertTrue(BuildTimeCompilationSupport.isBoundPreinitialization());
        assertEquals(List.of("add1"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertEquals(List.of(new BuildTimeCompilationSupport.Rejection("refuse", "root refused AOT (cloneForAOT or prepareForAOT)")), selection.rejected());
        assertFalse("the language of a pre-initialized context without code sharing must stay single-context", AOTTestLanguage.multipleContexts);
    }

    /**
     * With {@code AOTCompileTotalBudget}, the roots are kept by priority while their AOT shadows fit
     * in the budget together: with equal (no) known call sites, the smaller ones first. The others
     * are rejected as over the total budget.
     */
    @Test
    public void totalBudgetKeepsSmallerRootsThatFit() throws Exception {
        String sources = files("add:1", "branch", "strcat", "inlined", "loop", "instr");
        Map<String, Integer> costs = new LinkedHashMap<>();
        for (var pair : preinit(sources, "").selected()) {
            costs.put(pair.live().getRootNode().getName(), NodeUtil.countNodes(pair.shadow().getRootNode()));
        }
        /* Without known call sites: by size, then by name, while the roots fit. */
        List<String> byPriority = costs.keySet().stream().sorted(Comparator.comparingInt((String name) -> costs.get(name)).thenComparing(name -> name)).toList();
        int budget = costs.get(byPriority.get(0)) + costs.get(byPriority.get(1)) + costs.get(byPriority.get(2));
        clear();
        System.setProperty("polyglot.image-build-time.AOTCompileTotalBudget", String.valueOf(budget));
        var selection = preinit(sources, "");
        List<String> selected = selection.selected().stream().map(p -> p.live().getRootNode().getName()).sorted().toList();
        assertEquals(costs.toString(), byPriority.subList(0, 3).stream().sorted().toList(), selected);
        for (String name : byPriority.subList(3, byPriority.size())) {
            assertTrue(selection.rejected().toString(), selection.rejected().contains(new BuildTimeCompilationSupport.Rejection(name, BuildTimeCompilationSupport.OVER_TOTAL_BUDGET)));
        }
    }

    @Test
    public void filterRejects() throws Exception {
        var selection = preinit(files("add:1", "add:2"), "add2");
        assertEquals(List.of("add2"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());
        assertTrue(selection.rejected().contains(new BuildTimeCompilationSupport.Rejection("add1", "filtered")));
    }

    /**
     * A root whose AOT shadow cannot be created, whatever its language did wrong, is rejected with
     * the reason, never fails the selection (and so the image build), and leaves the live root as
     * it was. The well-behaved roots are still selected, including one whose copy shares a node
     * that is never adopted with the live root (finding m5).
     */
    @Test
    public void misbehavingRootsAreRejectedAndLiveRootsUnchanged() throws Exception {
        var selection = preinit(files("misbehave:clonethrow", "misbehave:prepthrow", "misbehave:cloneself", "misbehave:cloneshare", "misbehave:sharedesc", "misbehave:owndesc", "misbehave:shareunadoptable", "add:1"), "");
        assertEquals(List.of("add1", "owndesc", "shareunadoptable"), selection.selected().stream().map(p -> p.live().getRootNode().getName()).sorted().toList());
        Map<String, String> reasons = selection.rejected().stream().collect(Collectors.toMap(BuildTimeCompilationSupport.Rejection::rootName, BuildTimeCompilationSupport.Rejection::reason, (a, b) -> a, TreeMap::new));
        assertEquals(List.of("cloneself", "cloneshare", "clonethrow", "prepthrow", "sharedesc"), List.copyOf(reasons.keySet()));
        String failed = BuildTimeCompilationSupport.SHADOW_FAILED + ": ";
        assertEquals(failed + "java.lang.UnsupportedOperationException: clonethrow", reasons.get("clonethrow"));
        assertEquals(failed + "java.lang.IllegalStateException: prepthrow", reasons.get("prepthrow"));
        for (String mode : List.of("cloneself", "cloneshare")) {
            assertTrue(reasons.get(mode), reasons.get(mode).startsWith(failed + "java.lang.IllegalStateException: cloneForAOT returned a tree that shares node"));
        }
        assertTrue(reasons.get("sharedesc"), reasons.get("sharedesc").startsWith(failed + "java.lang.IllegalStateException: prepareForAOT changed the FrameDescriptor"));

        for (String mode : List.of("clonethrow", "prepthrow", "cloneself", "cloneshare", "sharedesc", "owndesc", "shareunadoptable")) {
            AOTTestLanguage.MisbehavingRoot live = AOTTestLanguage.misbehaving.get(mode);
            assertEquals(mode, FrameSlotKind.Illegal, live.getFrameDescriptor().getSlotKind(0));
            assertSame(mode, live, live.child.getParent());
            assertSame(mode, live, live.getCallTarget().getRootNode());
            assertFalse(mode, ((OptimizedCallTarget) live.getCallTarget()).isAOTShadow());
        }
        OptimizedCallTarget ownDescShadow = selection.selected().stream().filter(p -> p.live().getRootNode().getName().equals("owndesc")).findFirst().orElseThrow().shadow();
        assertEquals(FrameSlotKind.Int, ownDescShadow.getRootNode().getFrameDescriptor().getSlotKind(0));
    }

    /**
     * (a) A non-SHARED (EXCLUSIVE) language's roots must never be selected, even though its
     * "add:N" source parses to a root that is perfectly willing to be AOT-prepared: the
     * enumeration itself must exclude it, not merely fail to compile it.
     */
    @Test
    public void exclusiveLanguageSelectsNothing() throws Exception {
        var selection = preinit(AOTExclusiveTestLanguage.ID, filesFor(AOTExclusiveTestLanguage.ID, "add:1"), "");
        assertEquals(List.of(), selection.selected());
        assertEquals(List.of(), selection.rejected());
    }

    /**
     * (b) Pins the SHARED-layer-only enumeration by contrasting the same "add:1" source between
     * the SHARED test language (selected) and its EXCLUSIVE counterpart (not even enumerated).
     * The compiler test module cannot reach {@code PolyglotSharingLayer} directly, so this
     * indirect comparison is the enumeration pin: only the SHARED variant's root is ever visible
     * to {@code selectPreinitialized()}.
     */
    @Test
    public void onlySharedLayerRootsAreEnumerated() throws Exception {
        var sharedSelection = preinit(AOTTestLanguage.ID, filesFor(AOTTestLanguage.ID, "add:1"), "");
        assertEquals(List.of("add1"), sharedSelection.selected().stream().map(p -> p.live().getRootNode().getName()).toList());

        resetPreInitializedEngine();
        var exclusiveSelection = preinit(AOTExclusiveTestLanguage.ID, filesFor(AOTExclusiveTestLanguage.ID, "add:1"), "");
        assertEquals(List.of(), exclusiveSelection.selected());
        assertEquals(List.of(), exclusiveSelection.rejected());
    }
}
