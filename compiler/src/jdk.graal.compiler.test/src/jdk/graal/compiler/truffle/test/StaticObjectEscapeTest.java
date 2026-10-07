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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.graalvm.polyglot.Context;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.staticobject.DefaultStaticObjectFactory;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;
import com.oracle.truffle.api.staticobject.StaticProperty;
import com.oracle.truffle.api.staticobject.StaticShape;
import com.oracle.truffle.api.test.polyglot.ProxyLanguage;
import com.oracle.truffle.runtime.OptimizedCallTarget;

/**
 * Runs compiled code that allocates static objects with field-based storage, which are pods in a
 * native image (elide-dev/graal#65). Escape analysis can scalar-replace them, so these tests cover
 * the paths where they are materialized: when they escape, at merges, when compiled code
 * deoptimizes, and across a GC. {@link StaticObjectAllocationTest} checks the graphs.
 */
public class StaticObjectEscapeTest extends TestWithSynchronousCompiling {

    private ProxyLanguage language;

    @Override
    protected Context.Builder newContextBuilder() {
        return super.newContextBuilder().option("engine.StaticObjectStorageStrategy", "field-based");
    }

    @Before
    public void setup() {
        setupContext().initialize(ProxyLanguage.ID);
        language = ProxyLanguage.get(null);
    }

    /** A shape with fields of every kind, and one that extends it. */
    final class Shapes {
        final StaticProperty i = new DefaultStaticProperty("i");
        final StaticProperty l = new DefaultStaticProperty("l");
        final StaticProperty d = new DefaultStaticProperty("d");
        final StaticProperty b = new DefaultStaticProperty("b");
        final StaticProperty o = new DefaultStaticProperty("o");
        final StaticProperty s = new DefaultStaticProperty("s");
        final StaticProperty o2 = new DefaultStaticProperty("o2");
        final StaticShape<DefaultStaticObjectFactory> base;
        final StaticShape<DefaultStaticObjectFactory> derived;

        Shapes() {
            StaticShape.Builder baseBuilder = StaticShape.newBuilder(language);
            baseBuilder.property(i, int.class, false);
            baseBuilder.property(l, long.class, false);
            baseBuilder.property(d, double.class, false);
            baseBuilder.property(b, boolean.class, false);
            baseBuilder.property(o, Object.class, false);
            base = baseBuilder.build();
            StaticShape.Builder derivedBuilder = StaticShape.newBuilder(language);
            derivedBuilder.property(s, short.class, false);
            derivedBuilder.property(o2, Object.class, false);
            derived = derivedBuilder.build(base);
        }

        Object create(int iv, long lv, double dv, boolean bv, Object ov, short sv, Object o2v) {
            Object object = derived.getFactory().create();
            i.setInt(object, iv);
            l.setLong(object, lv);
            d.setDouble(object, dv);
            b.setBoolean(object, bv);
            o.setObject(object, ov);
            s.setShort(object, sv);
            o2.setObject(object, o2v);
            return object;
        }

        Object[] values(Object object) {
            return new Object[]{i.getInt(object), l.getLong(object), d.getDouble(object), b.getBoolean(object), o.getObject(object), s.getShort(object), o2.getObject(object)};
        }
    }

    abstract class TestRoot extends RootNode {
        @CompilationFinal final Shapes shapes;

        TestRoot(Shapes shapes) {
            super(language);
            this.shapes = shapes;
        }

        Object create(Object[] args) {
            return shapes.create((int) args[0], (long) args[1], (double) args[2], (boolean) args[3], args[4], (short) args[5], args[6]);
        }
    }

    private static Object[] args(int seed) {
        return new Object[]{seed, seed * 1000L, seed + 0.5, seed % 2 == 0, "o" + seed, (short) seed, new StringBuilder("o2-").append(seed)};
    }

    /** The values of an object created from {@code args}, as {@link Shapes#values} returns them. */
    private static Object[] expected(Object[] args) {
        return args.clone();
    }

    private static OptimizedCallTarget compile(RootNode root, Object[] warmupArgs) {
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        for (int i = 0; i < 3; i++) {
            target.call(warmupArgs);
        }
        target.compile(true);
        assertCompiled(target);
        return target;
    }

    /** The object does not escape: compiled code computes with its fields only. */
    @Test
    public void nonEscaping() {
        Shapes shapes = new Shapes();
        RootNode root = new TestRoot(shapes) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object object = create(frame.getArguments());
                return shapes.values(object);
            }
        };
        OptimizedCallTarget target = compile(root, args(1));
        for (int seed = 2; seed < 6; seed++) {
            Object[] args = args(seed);
            assertArrayEquals(expected(args), (Object[]) target.call(args));
        }
        assertCompiled(target);
    }

    /** The object escapes as the result: it is materialized with all its fields. */
    @Test
    public void escapes() {
        Shapes shapes = new Shapes();
        RootNode root = new TestRoot(shapes) {
            @Override
            public Object execute(VirtualFrame frame) {
                return create(frame.getArguments());
            }
        };
        OptimizedCallTarget target = compile(root, args(1));
        for (int seed = 2; seed < 6; seed++) {
            Object[] args = args(seed);
            Object object = target.call(args);
            assertSame(shapes.derived.getFactory().create().getClass(), object.getClass());
            assertArrayEquals(expected(args), shapes.values(object));
        }
        assertCompiled(target);
    }

    static volatile Object sink;

    @TruffleBoundary
    static void escape(Object object) {
        sink = object;
    }

    /** The object escapes on one branch only: it is materialized there. */
    @Test
    public void escapesOnOneBranch() {
        Shapes shapes = new Shapes();
        RootNode root = new TestRoot(shapes) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object object = create(frame.getArguments());
                if ((int) frame.getArguments()[0] % 3 == 0) {
                    escape(object);
                }
                return shapes.i.getInt(object) + shapes.s.getShort(object);
            }
        };
        OptimizedCallTarget target = compile(root, args(3));
        target.call(args(1));
        for (int seed = 2; seed < 10; seed++) {
            Object[] args = args(seed);
            sink = null;
            assertEquals(2 * seed, target.call(args));
            if (seed % 3 == 0) {
                assertArrayEquals(expected(args), shapes.values(sink));
            } else {
                assertEquals(null, sink);
            }
        }
    }

    /** Compiled code deoptimizes while the object is scalar-replaced: it is rematerialized. */
    @Test
    public void deoptimizes() {
        Shapes shapes = new Shapes();
        RootNode root = new TestRoot(shapes) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object object = create(frame.getArguments());
                if ((int) frame.getArguments()[0] < 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    shapes.i.setInt(object, -shapes.i.getInt(object));
                }
                return shapes.values(object);
            }
        };
        OptimizedCallTarget target = compile(root, args(1));
        Object[] args = args(7);
        assertArrayEquals(expected(args), (Object[]) target.call(args));
        assertCompiled(target);

        Object[] negative = args(-5);
        Object[] result = (Object[]) target.call(negative);
        assertFalse(target.isValid());
        Object[] expected = expected(negative);
        expected[0] = 5;
        assertArrayEquals(expected, result);
    }

    @TruffleBoundary
    static Object collect(Object object) {
        System.gc();
        return object;
    }

    /** References in a scalar-replaced and in a materialized object survive a GC. */
    @Test
    public void survivesGC() {
        Shapes shapes = new Shapes();
        RootNode root = new TestRoot(shapes) {
            @Override
            public Object execute(VirtualFrame frame) {
                Object object = create(frame.getArguments());
                collect(null);
                Object[] virtual = shapes.values(object);
                Object materialized = collect(create(frame.getArguments()));
                return new Object[]{virtual, shapes.values(materialized)};
            }
        };
        OptimizedCallTarget target = compile(root, args(1));
        for (int seed = 2; seed < 5; seed++) {
            Object[] args = args(seed);
            Object[] result = (Object[]) target.call(args);
            assertArrayEquals(expected(args), (Object[]) result[0]);
            assertArrayEquals(expected(args), (Object[]) result[1]);
        }
        assertTrue(target.isValid());
    }
}
