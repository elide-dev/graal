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

import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * EXCLUSIVE-policy counterpart of {@link AOTTestLanguage}, used to verify that
 * {@code BuildTimeCompilationSupport.selectPreinitialized()} only considers roots of SHARED
 * layers: this language's roots must never be selected, even though its "add:N" sources parse to
 * roots that are perfectly willing to be AOT-prepared.
 */
@TruffleLanguage.Registration(id = AOTExclusiveTestLanguage.ID, name = AOTExclusiveTestLanguage.ID, contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class AOTExclusiveTestLanguage extends TruffleLanguage<Object> {
    public static final String ID = "AOTTierExclusiveTestLanguage";

    @Override
    protected Object createContext(Env env) {
        return env;
    }

    @Override
    protected boolean patchContext(Object context, Env newEnv) {
        return true;
    }

    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return true;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String trimmed = request.getSource().getCharacters().toString().trim();
        if (!trimmed.startsWith("add:")) {
            throw new IllegalArgumentException(trimmed);
        }
        return new AddRoot(this, Integer.parseInt(trimmed.substring(4))).getCallTarget();
    }

    public static final class AddRoot extends RootNode {
        final int constant;

        AddRoot(TruffleLanguage<?> language, int constant) {
            super(language);
            this.constant = constant;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return (Integer) frame.getArguments()[0] + constant;
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.GENERIC;
        }

        @Override
        public String getName() {
            return "excl-add" + constant;
        }
    }
}
