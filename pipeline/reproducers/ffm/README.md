# Foreign Downcall Candidate

Exact revisions and native test arguments are in `subject.json`; the complete production/test/doc extraction is `candidate.patch`.
Use Labs JDK CE 25.6-b27 and mx e64ff8c8cca770c8cdbb62617ebd0fa17dd184b1 on Linux AMD64.
Apply the patch to a fresh worktree at the specified upstream base.
Use a separate MX_ALT_OUTPUT_ROOT under /build/caches and run from substratevm:

```shell
mx --max-cpus=8 --native-images=false build --build-logs=silent
```

Run the `regression` argument array in subject.json with that same mx prefix.
Default-mode local validation passes 14 tests, including graph checks.
To test isolated compilation, replace `-H:-SupportCompileInIsolates` with `-H:+SupportCompileInIsolates` to build arguments and `-Dtruffle.test.FFMDowncallPartialEvaluationTest.checkGraphs=false -XX:+CompileInIsolates` to run arguments.
All 14 tests must execute; an OK banner with whitebox assumption skips is not a pass.

The baseline with identical test bytes fails image analysis on blocklisted runtime-compilation methods.
This does not provide a comparable runtime performance baseline or establish a functional bug.
No speedup is claimed. Upstream publication remains held for product shipment and repeatable product measurements.
Native Windows execution and the inherited empty capture-state limitation remain unqualified.

CI attempt 8 omitted the explicit graph-mode flag and skipped all 14 tests despite a green workflow. The corrected subject now matches the local graph-mode command; it has not been rerun in CI.
