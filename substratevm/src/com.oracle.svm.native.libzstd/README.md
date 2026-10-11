# libzstd (decoder only)

Vendored subset of [Zstandard](https://github.com/facebook/zstd) **1.5.7** (`lib/common` and
`lib/decompress`, plus `zstd.h` and `zstd_errors.h`), BSD licensed (see `LICENSE`).

The files are unmodified. Only the decompression side is included: the image builder compresses
image-heap blobs with zstd-jni, and images link this static library to inflate them at run time
(see `com.oracle.svm.core.compress.Zstd` and `CompressedBlob`). The library is built with
`ZSTD_DISABLE_ASM` so that no assembly source has to be compiled, `ZSTD_LEGACY_SUPPORT=0`, and
`XXH_NAMESPACE=ZSTD_` so that its xxhash symbols cannot clash with other static libraries.

To update: copy the same file set from a newer release, keep `LICENSE` in sync, and bump the version
in this file and in the `com.oracle.svm.native.libzstd` project description in `suite.py`.
