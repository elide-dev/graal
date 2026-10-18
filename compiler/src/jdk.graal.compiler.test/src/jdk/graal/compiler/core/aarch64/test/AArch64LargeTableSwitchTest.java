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
package jdk.graal.compiler.core.aarch64.test;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.asm.BranchTargetOutOfBoundsException;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.aarch64.AArch64LIRGenerator;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.core.test.backend.BackendTest;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.LabelRef;
import jdk.graal.compiler.lir.StandardOp;
import jdk.graal.compiler.lir.SwitchStrategy;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.phases.LIRPhase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.lir.phases.PostAllocationOptimizationPhase.PostAllocationOptimizationContext;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.JavaConstant;

public class AArch64LargeTableSwitchTest extends BackendTest {
    private boolean forceConservative;
    private Class<? extends LIRInstruction> expectedSwitch;

    @Before
    public void requireAArch64() {
        Assume.assumeTrue("AArch64-specific test", getTarget().arch instanceof AArch64);
    }

    public static int rangeSnippet(int key) {
        int result = switch (GraalDirectives.opaque(key)) {
            case 0 -> 11;
            case 1 -> 29;
            case 2 -> -17;
            case 3 -> 83;
            case 4 -> 37;
            case 5 -> 131;
            case 6 -> 23;
            case 7 -> 61;
            default -> -9;
        };
        return result;
    }

    public static int hashSnippet(int key) {
        int result = switch (GraalDirectives.opaque(key)) {
            case 6160024 -> 11;
            case 6160034 -> 29;
            case 6160058 -> -17;
            case 6160074 -> 83;
            case 6160080 -> 37;
            case 6160108 -> 131;
            case 6160120 -> 23;
            case 6160130 -> 61;
            case 6160146 -> 97;
            case 6160164 -> 107;
            case 6160190 -> 151;
            case 6160206 -> 193;
            case 6160232 -> 211;
            case 6160254 -> 241;
            case 6160260 -> 263;
            default -> -9;
        };
        return result;
    }

    public static int strategySnippet(int key) {
        return switch (GraalDirectives.opaque(key)) {
            case 7 -> 11;
            case 100003 -> 29;
            case 100000007 -> -17;
            default -> -9;
        };
    }

    public static int remainingSnippet(int key) {
        return switch (GraalDirectives.opaque(key)) {
            case 0 -> 11;
            case 1 -> 29;
            case 2 -> -17;
            case 3 -> 83;
            case 4 -> 37;
            case 5 -> 131;
            case 6 -> 23;
            case 7 -> 61;
            case 100003 -> 97;
            case 100000007 -> 107;
            default -> -9;
        };
    }

    @Test
    public void strategyDestinationsBeyondConditionalRange() {
        checkLargeSwitch("strategySnippet", AArch64ControlFlow.StrategySwitchOp.class, true);
    }

    @Test
    public void remainingDestinationsBeyondConditionalRange() {
        checkLargeSwitch("remainingSnippet", AArch64ControlFlow.RangeTableSwitchOp.class, true);
    }

    @Test
    public void rangeTableBeyondAdrRange() {
        checkLargeSwitch("rangeSnippet", AArch64ControlFlow.RangeTableSwitchOp.class, false);
    }

    @Test
    public void hashTableBeyondAdrRange() {
        checkLargeSwitch("hashSnippet", AArch64ControlFlow.HashTableSwitchOp.class, false);
    }

    @Test
    public void rangeDefaultBeyondConditionalRange() {
        checkLargeSwitch("rangeSnippet", AArch64ControlFlow.RangeTableSwitchOp.class, true);
    }

    @Test
    public void hashDefaultBeyondConditionalRange() {
        checkLargeSwitch("hashSnippet", AArch64ControlFlow.HashTableSwitchOp.class, true);
    }

    private void checkLargeSwitch(String method, Class<? extends LIRInstruction> switchClass, boolean distantTargets) {
        StructuredGraph graph = parseEager(method, StructuredGraph.AllowAssumptions.YES);
        var result = getLIRGenerationResult(graph, getOptimisticOptimizations());
        var lir = result.getLIR();
        var source = lir.getControlFlowGraph().getStartBlock();
        for (var block : lir.getControlFlowGraph().getBlocks()) {
            if (block.getSuccessorCount() > 1) {
                source = block;
                break;
            }
        }
        Assert.assertTrue("switch CFG successors required", source.getSuccessorCount() > 1);
        LabelRef defaultTarget = LabelRef.forSuccessor(lir, source, 0);
        LabelRef[] targets = new LabelRef[8];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = LabelRef.forSuccessor(lir, source, i % source.getSuccessorCount());
        }
        var key = AArch64.r0.asValue(LIRKind.value(AArch64Kind.DWORD));
        JavaConstant[] sparseKeys = {JavaConstant.forInt(100003), JavaConstant.forInt(100000007)};
        var strategy = new SwitchStrategy.SequentialStrategy(new double[]{0.5, 0.5}, sparseKeys);
        LabelRef[] sparseTargets = {targets[1], targets[2]};
        LIRInstruction switchOp;
        if (switchClass == AArch64ControlFlow.HashTableSwitchOp.class) {
            JavaConstant[] keys = new JavaConstant[targets.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = JavaConstant.forInt(i * 100003);
            }
            switchOp = new AArch64ControlFlow.HashTableSwitchOp(keys, defaultTarget, targets, key, AArch64.r1.asValue(LIRKind.value(AArch64Kind.DWORD)));
        } else if (switchClass == AArch64ControlFlow.StrategySwitchOp.class) {
            switchOp = new AArch64ControlFlow.StrategySwitchOp(strategy, sparseTargets, defaultTarget, key, AArch64LIRGenerator::toIntConditionFlag);
        } else {
            boolean remaining = method.equals("remainingSnippet");
            switchOp = new AArch64ControlFlow.RangeTableSwitchOp(0, defaultTarget, targets, remaining ? strategy : null, remaining ? sparseTargets : null, key, true, true);
        }
        CompilationResult compilationResult = new CompilationResult(graph.compilationId());
        CompilationResultBuilder crb = ((LIRGenerationProvider) getBackend()).newCompilationResultBuilder(result, result.getFrameMap(), compilationResult,
                        CompilationResultBuilderFactory.Default, null);
        LIRInstruction op = switchOp;
        Assert.assertThrows(BranchTargetOutOfBoundsException.class, () -> emitSwitch(crb, op, distantTargets, method.equals("remainingSnippet")));
        crb.resetForEmittingCode();
        crb.setConservativeLabelRanges();
        emitSwitch(crb, op, distantTargets, method.equals("remainingSnippet"));
    }

    private static void emitSwitch(CompilationResultBuilder crb, LIRInstruction op, boolean distantTargets, boolean remaining) {
        AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
        // Isolate switch emission from unrelated entry-barrier and return-poll range limits.
        if (!distantTargets || remaining) {
            crb.getLIR().addSlowPath(null, () -> pad(masm));
        }
        op.emitCode(crb);
        if (distantTargets) {
            pad(masm);
        }
        for (var block : crb.getLIR().getControlFlowGraph().getBlocks()) {
            var label = ((StandardOp.LabelOp) crb.getLIR().getLIRforBlock(block).getFirst()).getLabel();
            if (!label.isBound()) {
                masm.bind(label);
            }
        }
        if (crb.getLIR().getSlowPaths() != null) {
            for (var slowPath : crb.getLIR().getSlowPaths()) {
                slowPath.emitSlowPathCode();
            }
        }
    }

    private static void pad(AArch64MacroAssembler masm) {
        for (int i = 0; i < 300_000; i++) {
            masm.nop();
        }
    }

    @Test
    public void rangeResults() throws Exception {
        expectedSwitch = AArch64ControlFlow.RangeTableSwitchOp.class;
        InstalledCode code = getCode(getResolvedJavaMethod("rangeSnippet"), null, true);
        for (int key : new int[]{Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, Integer.MAX_VALUE}) {
            Assert.assertEquals(rangeSnippet(key), code.executeVarargs(key));
        }
    }

    @Test
    public void hashResults() throws Exception {
        expectedSwitch = AArch64ControlFlow.HashTableSwitchOp.class;
        InstalledCode code = getCode(getResolvedJavaMethod("hashSnippet"), null, true);
        for (int key : new int[]{Integer.MIN_VALUE, 6160022, 6160024, 6160026, 6160034, 6160058, 6160074, 6160080, 6160108, 6160120, 6160130,
                        6160146, 6160164, 6160190, 6160206, 6160232, 6160254, 6160260, 6160262, Integer.MAX_VALUE}) {
            Assert.assertEquals(hashSnippet(key), code.executeVarargs(key));
        }
    }

    @Test
    public void conservativeRangeResults() throws Exception {
        forceConservative = true;
        rangeResults();
    }

    @Test
    public void conservativeHashResults() throws Exception {
        forceConservative = true;
        hashResults();
    }

    private static final class ConservativeOp extends AArch64LIRInstruction {
        static final LIRInstructionClass<ConservativeOp> TYPE = LIRInstructionClass.create(ConservativeOp.class);

        ConservativeOp() {
            super(TYPE);
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            crb.setConservativeLabelRanges();
        }
    }

    @Override
    protected LIRSuites createLIRSuites(OptionValues options) {
        LIRSuites suites = super.createLIRSuites(options);
        if (forceConservative || expectedSwitch != null) {
            suites.getPostAllocationOptimizationStage().appendPhase(new LIRPhase<PostAllocationOptimizationContext>() {
                @Override
                protected void run(TargetDescription target, LIRGenerationResult result, PostAllocationOptimizationContext context) {
                    var lir = result.getLIR();
                    if (expectedSwitch != null) {
                        int switches = 0;
                        for (var block : lir.getControlFlowGraph().getBlocks()) {
                            for (var instruction : lir.getLIRforBlock(block)) {
                                if (expectedSwitch.isInstance(instruction)) {
                                    switches++;
                                }
                            }
                        }
                        Assert.assertEquals("requested switch must execute", 1, switches);
                    }
                    if (forceConservative) {
                        lir.getLIRforBlock(lir.getControlFlowGraph().getStartBlock()).add(1, new ConservativeOp());
                    }
                }
            });
        }
        return suites;
    }

}
