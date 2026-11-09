# LCOV Source Identity Reproducer

Use a source checkout of Oracle GraalVM and a matching LabsJDK.
The tested baseline is `a369308fb9f4fbed7746caf33c87eb8f3a61b613`.
From the checkout's _tools_ suite, with `JAVA_HOME` and a separate `MX_ALT_OUTPUT_ROOT` set for each revision, run:

```shell
mx build --build-logs=silent --dependencies TRUFFLE_COVERAGE_TEST,TRUFFLE_RUNTIME
mx java --enable-native-access=ALL-UNNAMED \
  -cp "$(mx classpath TRUFFLE_COVERAGE_TEST TRUFFLE_RUNTIME)" \
  /path/to/LCOVReproducer.java
```

The test language comes from the built Truffle test distribution; this sample does not require GraalJS or Native Image.
On baseline, the sample prints `[SF:null, SF:null]` and exits 1 with `LCOV lost pathless source identity`.
On the candidate, it prints two distinct `SF:truffle:.../same-name` identifiers and exits 0.

For the full regression, apply _regression.patch_ to a clean baseline, rebuild the same distributions, and run `mx unittest CoverageTest`.
The candidate already contains that patch; do not apply it again there.
The final suite covers default URIs, explicit non-empty URIs, explicit empty URIs and file-backed paths.
A source URI identifies a coverage record; it does not guarantee that tools such as `genhtml` can locate source text.
The documented LCOV consumer probe uses `--ignore-errors format` to isolate source identity from existing unrelated `SN:` and unnamed-function formatting warnings.
