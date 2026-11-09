# NFI Reproducer

Base: `66f3cd8b560c12131eb948b56edb45da7960dc25`. Candidate: `e8fbf0ccdac0767aac00b8c04ef9f3589d3f0d20`.
Repository: https://github.com/oracle/graal.git
mx: `e64ff8c8cca770c8cdbb62617ebd0fa17dd184b1`.
Labs JDK CE: `25.6-b27`, Linux AMD64. Native Image uses eight compiler threads.

Use two fresh worktrees at the base revision under `/build/worktrees`.
Apply `candidate.patch` only to the candidate.
Copy the contents of `tests/` onto both worktrees, preserving paths.
These are identical test bytes; the suite metadata overlay supplies test exports.
Set `JAVA_HOME` to the pinned Labs JDK and use separate `MX_ALT_OUTPUT_ROOT` directories under `/build/caches`.
Run `mx --max-cpus=8 --native-images=false build --build-logs=silent` from each `substratevm` directory, then the identical argument array in `subject.json` with the same mx prefix.
Never run mx concurrently within one workspace.

Both revisions must pass the functional suite. This is an optimization comparison, not a baseline functional defect. Before running, create `substratevm/svmbuild/linux-amd64/nfi-test-native` under each output root as a symlink to that root’s `truffle/linux-amd64/truffle-test-native` directory. Retain all five timing samples and verify compiled entry and completion. Local timings under concurrent load do not establish product benefit or shipment.
