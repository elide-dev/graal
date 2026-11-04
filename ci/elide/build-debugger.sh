#!/usr/bin/env bash
# Pin the debugger independently of the old-glibc SDK build container.
# Native Image requires GDB >= 14.2; Ubuntu 22.04 supplies GDB 12.
set -euo pipefail
prefix=${1:?debugger installation directory}
work=${2:?debugger build directory}
mkdir -p "$work"
work=$(cd "$work" && pwd)
curl -fsSL https://ftp.gnu.org/gnu/gdb/gdb-16.3.tar.xz -o "$work/gdb.tar.xz"
echo "bcfcd095528a987917acf9fff3f1672181694926cc18d609c99d0042c00224c5  $work/gdb.tar.xz" | sha256sum -c -
tar -C "$work" -xf "$work/gdb.tar.xz"
mkdir -p "$work/build"
cd "$work/build"
../gdb-16.3/configure --prefix="$prefix" --disable-nls --disable-binutils \
  --disable-gas --disable-gold --disable-gprof --disable-ld --disable-sim \
  --disable-gdbserver --without-guile --without-debuginfod --disable-source-highlight \
  --with-python=python3
make -j4 all-gdb
make install-gdb
"$prefix/bin/gdb" --version
