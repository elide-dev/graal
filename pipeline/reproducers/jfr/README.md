# Native G1 Allocation Samples

Graal baseline: `66f3cd8b560c12131eb948b56edb45da7960dc25`. Candidate: `c4cfedc70e81c84e684654367f6d789ca77b781d`.
Public native dependency: https://github.com/graalvm/labs-openjdk at `cafc6cf519b36ca5337ceba858190e0e647073ce`, interface 250502.
mx: `e64ff8c8cca770c8cdbb62617ebd0fa17dd184b1`; Labs JDK CE 25.6-b27; Linux AMD64 glibc with compressed references.

The image-build and runtime scripts retain the commands used in the independent review.
The provider compile script is cleaned up to use the final native header and omit an earlier hello-world setup probe.
Change their workspace, cache, artifact and JDK path assignments for your machine before running.
Use separate baseline and candidate worktrees; apply `candidate.patch` only to the candidate.
Build the public native component with `build-native-250502.sh`.
The provider compile script points `native250502/include/g1GCStructs.h` at the same native source revision.
Do not substitute another native interface version or bypass its compatibility guard.

Build Graal and the ni distribution in each worktree with the recorded mx and JDK.
Run image-build scripts from the corresponding Graal worktree.
Run `compile-scaffold.sh`, `compile-probe.sh`, then `python3 package-probe.py` from the package artifact directory after adjusting paths.
Compile the external option provider and exact candidate regression plus strict driver once; use `package-probe.py` to separate provider.jar and probe.jar.
Use those identical JAR bytes and the same native archive for both image builds.
The provider exposes existing upstream G1 selection; it does not replace production classes or singletons.
Run each executable with `-Xmx256m -Xms32m`.

Expected baseline: native/G1/JFR assertions pass, then `AssertionError: missing instance allocation samples`, exit 1.
Expected candidate: exact regression, restart, controlled weights, failed-array exclusion, disabled-event/stacks and virtual-thread allocation checks pass, exit 0.
The observed throttle count is not a rate-bound assertion.
Recording lifecycle directly on virtual threads has an inherited failure on both revisions; the passing virtual-allocation case manages Recording on a platform thread.

The retained local comparison used a minimal non-jlinked candidate distribution and the existing jlinked baseline distribution with the same Labs JDK. This proves functionality, not performance equivalence.
No Windows, AArch64, uncompressed-reference or native fastdebug result is claimed.

Header lookup appends `include/g1GCStructs.h` to `CLibraryPath`; pass the parent library root, not its include directory. The first CI image attempt exposed and corrected this harness path error.
