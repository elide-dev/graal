# Resource URL Identity Reproducer

This is a Native Image reproducer; `resource:` is not a standard HotSpot URL protocol.
It uses two numeric spellings of the same IPv4 address, avoiding DNS and localhost address-family dependencies.
The authorities identify resources independently and must not compare equal merely because they denote the same network address.

The tested upstream baseline is `a369308fb9f4fbed7746caf33c87eb8f3a61b613`.
From each source checkout's _substratevm_ suite, with matching `JAVA_HOME` and isolated `MX_ALT_OUTPUT_ROOT`, run:

```shell
mx --native-images=false build --build-logs=silent
javac -d /path/to/classes /path/to/ResourceURLReproducer.java
mx --native-images=false native-image --no-fallback --parallelism=8 -J-Xmx8g \
  -cp /path/to/classes ResourceURLReproducer -o /path/to/resource-url
/path/to/resource-url
```

Use the identical source/class file for both revisions.
The baseline prints `equal=true, sameFile=true, setSize=1` and fails with `Resource URL identity used a network address`.
The candidate prints `equal=false, sameFile=false, setSize=2` and exits 0.
Run the regression suite with:

```shell
mx --native-images=false native-unittest com.oracle.svm.test.NativeImageResourceTest \
  --build-args --parallelism=8 -J-Xmx8g
mx checkstyle --primary
```

For a portable Native Image bundle, append `--bundle-create=/path/to/resource-url.nib` to the image build command.
Bundle creation redirects the executable to _resource-url.output/default/resource-url_.
You can replay the same bundle with the builder from each source revision using `--bundle-apply=/path/to/resource-url.nib`.
Inspect bundle metadata and embedded output before publishing it; the source-only ZIP avoids distributing host-specific paths and an executable.
The collection/identity test is deterministic; direct instrumentation of the host resolver is outside this sample's claim.
