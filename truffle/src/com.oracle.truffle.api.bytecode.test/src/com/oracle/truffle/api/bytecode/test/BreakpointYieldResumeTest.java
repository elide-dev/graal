/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.bytecode.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags.RootBodyTag;
import com.oracle.truffle.api.instrumentation.StandardTags.RootTag;
import com.oracle.truffle.api.instrumentation.StandardTags.StatementTag;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

/**
 * Line breakpoints on a statement that yields hit once per execution of the statement, not again
 * when the continuation resumes inside it.
 */
public class BreakpointYieldResumeTest {

    private static final String CODE = "a\nyield\nb";
    private static final int YIELD_LINE = 2;

    private Context context;

    @Before
    public void setup() {
        context = Context.create(YieldStatementLanguage.ID);
        context.initialize(YieldStatementLanguage.ID);
        context.enter();
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.leave();
            context.close();
            context = null;
        }
    }

    /*
     * Line 1 and line 3 are plain statements. The statement on line 2 yields, so a call returns a
     * continuation and continuing it resumes inside that statement.
     */
    private static YieldStatementRootNode parse(Source source) {
        return YieldStatementRootNodeGen.create(YieldStatementLanguage.REF.get(null), BytecodeConfig.DEFAULT, b -> {
            b.beginSource(source);
            b.beginSourceSection(0, CODE.length());
            b.beginRoot();

            b.beginSourceSection(0, 1);
            b.beginTag(StatementTag.class);
            b.emitNop();
            b.endTag(StatementTag.class);
            b.endSourceSection();

            b.beginSourceSection(2, 5);
            b.beginTag(StatementTag.class);
            b.beginYield();
            b.emitLoadConstant(2);
            b.endYield();
            b.endTag(StatementTag.class);
            b.endSourceSection();

            b.beginReturn();
            b.beginSourceSection(8, 1);
            b.beginTag(StatementTag.class);
            b.emitLoadConstant(3);
            b.endTag(StatementTag.class);
            b.endSourceSection();
            b.endReturn();

            b.endRoot();
            b.endSourceSection();
            b.endSource();
        }).getNode(0);
    }

    private List<String> runWithBreakpoint(SuspendAnchor anchor, List<String> hitsAfterYield) {
        Source source = Source.newBuilder(YieldStatementLanguage.ID, CODE, "yield.test").build();
        YieldStatementRootNode node = parse(source);
        List<String> hits = new ArrayList<>();
        try (DebuggerSession session = Debugger.find(context.getEngine()).startSession(event -> {
            hits.add(event.getSourceSection().getStartLine() + " " + event.getSuspendAnchor());
        })) {
            session.install(Breakpoint.newBuilder(source).lineIs(YIELD_LINE).suspendAnchor(anchor).build());

            Object result = node.getCallTarget().call();
            assertTrue(String.valueOf(result), result instanceof ContinuationResult);
            hitsAfterYield.addAll(hits);
            assertEquals(3, ((ContinuationResult) result).continueWith(null));
        }
        return hits;
    }

    @Test
    public void testBeforeAnchorHitsOnceAcrossResume() {
        List<String> hitsAfterYield = new ArrayList<>();
        List<String> hits = runWithBreakpoint(SuspendAnchor.BEFORE, hitsAfterYield);
        assertEquals(List.of(YIELD_LINE + " BEFORE"), hitsAfterYield);
        assertEquals(List.of(YIELD_LINE + " BEFORE"), hits);
    }

    @Test
    public void testAfterAnchorHitsOnceWhenStatementCompletes() {
        List<String> hitsAfterYield = new ArrayList<>();
        List<String> hits = runWithBreakpoint(SuspendAnchor.AFTER, hitsAfterYield);
        assertEquals(List.of(), hitsAfterYield);
        assertEquals(List.of(YIELD_LINE + " AFTER"), hits);
    }

    @TruffleLanguage.Registration(id = YieldStatementLanguage.ID)
    @ProvidedTags({RootTag.class, RootBodyTag.class, StatementTag.class, DebuggerTags.AlwaysHalt.class})
    public static class YieldStatementLanguage extends TruffleLanguage<Object> {

        static final String ID = "bytecode_BreakpointYieldResumeTestLanguage";

        static final LanguageReference<YieldStatementLanguage> REF = LanguageReference.create(YieldStatementLanguage.class);

        @Override
        protected Object createContext(Env env) {
            return new Object();
        }
    }

    @GenerateBytecode(languageClass = YieldStatementLanguage.class, enableTagInstrumentation = true, enableYield = true)
    public abstract static class YieldStatementRootNode extends RootNode implements BytecodeRootNode {

        protected YieldStatementRootNode(YieldStatementLanguage language, FrameDescriptor frameDescriptor) {
            super(language, frameDescriptor);
        }

        @Operation
        static final class Nop {
            @Specialization
            static void doNop() {
            }
        }
    }
}
