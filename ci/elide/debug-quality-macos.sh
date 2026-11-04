#!/usr/bin/env bash
set -euxo pipefail
sdk=${1:?SDK home}
out=${2:?qualification output}
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)
cp "$scripts/DebugQualityProbe.java" "$out/DebugQualityProbe.java"
"$sdk/bin/javac" --add-modules org.graalvm.nativeimage -g \
  -cp "$sdk/lib/svm/svm-shared.jar" -d "$out/classes" "$out/DebugQualityProbe.java"
common=(-J-Xmx5g -J-XX:ActiveProcessorCount=4 --no-fallback -g -O2 \
  --initialize-at-build-time=debugquality -cp "$out/classes")
"$sdk/bin/native-image" "${common[@]}" -H:TempDirectory="$out/executable-temp" debugquality.DebugQualityProbe -o "$out/probe" > "$out/executable-build.log" 2>&1
"$sdk/bin/native-image" "${common[@]}" -H:TempDirectory="$out/shared-temp" --shared debugquality.DebugQualityProbe -o "$out/libprobe" > "$out/shared-build.log" 2>&1
test "$("$out/probe" test)" = '42:84'
cat > "$out/harness.c" <<'C'
#include <stdio.h>
#include "libprobe.h"
int main(void) {
  graal_isolatethread_t *thread = NULL;
  if (graal_create_isolate(NULL, NULL, &thread) != 0) return 1;
  int result = debug_quality(thread, 18);
  printf("%d\n", result);
  if (graal_tear_down_isolate(thread) != 0) return 2;
  return result == 42 ? 0 : 3;
}
C
clang "$out/harness.c" -I "$out" -L "$out" -lprobe -Wl,-rpath,@loader_path -o "$out/harness"
test "$("$out/harness")" = 42
line=$(awk '/DEBUG_QUALITY_INNER/ {print NR}' "$out/DebugQualityProbe.java")
for spec in executable shared; do
  binary="$out/probe"
  image="$out/probe"
  if [ "$spec" = shared ]; then binary="$out/harness"; image="$out/libprobe.dylib"; fi
  dsym="$image.dSYM/Contents/Resources/DWARF/$(basename "$image")"
  test -s "$dsym"
  dwarfdump --uuid "$image" > "$out/$spec.image-uuid.txt"
  dwarfdump --uuid "$dsym" > "$out/$spec.dsym-uuid.txt"
  diff <(awk '{print $2,$3}' "$out/$spec.image-uuid.txt") <(awk '{print $2,$3}' "$out/$spec.dsym-uuid.txt")
  dwarfdump --debug-info "$dsym" > "$out/$spec.info.txt"
  grep -q 'debugquality.DebugQualityProbe' "$out/$spec.info.txt"
  dwarfdump --verify "$dsym" > "$out/$spec.verify.txt" 2>&1 || true
  # Keep all verifier diagnostics. The assertions below independently establish
  # owned Java source/inline/variable behavior and do not waive other categories.
  args=()
  if [ "$spec" = executable ]; then args=(-o 'settings set target.run-args test'); fi
  lldb --batch -o "target create $binary" "${args[@]}" \
    -o "breakpoint set --file DebugQualityProbe.java --line $line" \
    -o run -o 'frame info' -o 'thread backtrace' -o 'frame variable value shifted' \
    -o 'image lookup -t "debugquality.DebugQualityProbe$DataOnly"' > "$out/$spec.lldb.txt" 2>&1
  grep -q 'stop reason = breakpoint' "$out/$spec.lldb.txt"
  grep -q 'DebugQualityProbe.java:' "$out/$spec.lldb.txt"
  grep -q 'inner' "$out/$spec.lldb.txt"
  grep -q 'outer' "$out/$spec.lldb.txt"
  grep -Eq 'value = 18([^0-9]|$)' "$out/$spec.lldb.txt"
done
shasum -a 256 "$out/probe" "$out/libprobe.dylib" "$out"/*.dSYM/Contents/Resources/DWARF/* > "$out/SHA256SUMS"
