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
package jdk.graal.compiler.truffle.test;

import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.staticobject.DefaultStaticObjectFactory;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import com.oracle.truffle.api.test.polyglot.ProxyLanguage;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.nodes.extended.RawStoreNode;
import jdk.graal.compiler.nodes.java.AbstractNewObjectNode;
import jdk.graal.compiler.nodes.java.LoadFieldNode;
import jdk.graal.compiler.nodes.java.StoreFieldNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;

/**
 * A static object that compiled code allocates, writes and reads without letting it escape is
 * scalar-replaced, with either storage strategy. In a native image, field-based storage uses pods,
 * which escape analysis replaces only with {@code -R:+VirtualizePods} (elide-dev/graal#65).
 * {@link StaticObjectEscapeTest} runs the compiled code, including where such objects escape.
 */
@RunWith(Parameterized.class)
public class StaticObjectAllocationTest extends PartialEvaluationTest {

    @Parameterized.Parameters(name = "{0}")
    public static String[] data() {
        return new String[]{"array-based", "field-based"};
    }

    @Parameterized.Parameter public String storage;

    private ProxyLanguage language;

    @Before
    public void setup() {
        setupContext(Context.newBuilder().allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", storage));
        getContext().initialize(ProxyLanguage.ID);
        language = ProxyLanguage.get(null);
    }

    @Test
    public void allocateWriteAndRead() {
        StaticProperty i = new DefaultStaticProperty("i");
        StaticProperty l = new DefaultStaticProperty("l");
        StaticProperty o = new DefaultStaticProperty("o");
        StaticShape.Builder builder = StaticShape.newBuilder(language);
        builder.property(i, int.class, false);
        builder.property(l, long.class, false);
        builder.property(o, Object.class, false);
        StaticShape<DefaultStaticObjectFactory> base = builder.build();
        StaticProperty b = new DefaultStaticProperty("b");
        StaticShape<DefaultStaticObjectFactory> shape = StaticShape.newBuilder(language).property(b, byte.class, false).build(base);

        RootNode root = new RootNode(language) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object object = shape.getFactory().create();
                i.setInt(object, (int) frame.getArguments()[0]);
                l.setLong(object, i.getInt(object) * 2L);
                b.setByte(object, (byte) 3);
                o.setObject(object, frame.getArguments()[1]);
                return i.getInt(object) + l.getLong(object) + b.getByte(object) + (o.getObject(object) == null ? 0 : 1);
            }
        };
        Object[] args = {20, "o"};
        Assert.assertEquals(64L, root.getCallTarget().call(args));

        StructuredGraph graph = partialEval(root, args);
        assertNone(graph, AbstractNewObjectNode.class);
        assertNone(graph, CommitAllocationNode.class);
        assertNone(graph, VirtualObjectNode.class);
        assertNone(graph, RawLoadNode.class);
        assertNone(graph, RawStoreNode.class);
        assertNone(graph, LoadFieldNode.class);
        assertNone(graph, StoreFieldNode.class);
    }

    private static void assertNone(StructuredGraph graph, Class<? extends Node> nodeClass) {
        for (Node node : graph.getNodes()) {
            if (nodeClass.isInstance(node)) {
                Assert.fail("Static object not scalar-replaced: found " + node + " in " + graph);
            }
        }
    }
}
