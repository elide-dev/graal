# RISTRETTO Reproducer

Base: `66f3cd8b560c12131eb948b56edb45da7960dc25`. Candidate: `3dc0edcb6f8a396b8ecfcd789db3a708d1b0a4e7`.
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

The baseline must fail during runtime parsing with `PermanentBailoutException`; its absent private guard test is explicitly assumption-skipped. The candidate must execute and pass both tests. A build error does not establish the defect.
