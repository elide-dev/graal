#!/usr/bin/env bash
set -euxo pipefail
sdk=${1:?SDK home}
out=${2:?qualification output}
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)
mkdir -p "$out/src/debugquality"
cp "$scripts/DebugQualityProbe.java" "$out/DebugQualityProbe.java"
cp "$scripts/debug_quality_lldb.py" "$out/debug_quality_lldb.py"
cp "$scripts/DebugQualityProbe.java" "$out/src/debugquality/DebugQualityProbe.java"
"$sdk/bin/javac" --add-modules org.graalvm.nativeimage -g \
  -cp "$sdk/lib/svm/svm-shared.jar" -d "$out/classes" "$out/src/debugquality/DebugQualityProbe.java"
common=(-J-Xmx5g -J-XX:ActiveProcessorCount=4 --no-fallback -g -O2 \
  -H:DebugInfoSourceSearchPath="$out/src" -H:-SourceLevelDebug -H:+TrackNodeSourcePosition -H:+DebugCodeInfoUseSourceMappings --initialize-at-build-time=debugquality -cp "$out/classes")
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
for spec in executable shared; do
  binary="$out/probe"
  image="$out/probe"
  if [ "$spec" = shared ]; then binary="$out/harness"; image="$out/libprobe.dylib"; fi
  dsym="$image.dSYM/Contents/Resources/DWARF/$(basename "$image")"
  # Use Native Image's default dSYM generation and stripping. Validate the exact
  # peer it emitted; do not regenerate it after the producer has finished.
  test -s "$dsym"
  dwarfdump --uuid "$image" > "$out/$spec.image-uuid.txt"
  dwarfdump --uuid "$dsym" > "$out/$spec.dsym-uuid.txt"
  diff <(awk '{print $2,$3}' "$out/$spec.image-uuid.txt") <(awk '{print $2,$3}' "$out/$spec.dsym-uuid.txt")
  dwarfdump --debug-info "$dsym" > "$out/$spec.info.txt"
  grep -q 'debugquality.DebugQualityProbe' "$out/$spec.info.txt"
  dwarfdump --verify "$dsym" > "$out/$spec.verify.txt" 2>&1
  # Verifier failures remain failures; retain complete diagnostics alongside
  # independent source, inline and live-variable assertions.
  if [ "$spec" = executable ]; then
    create=(-o "target create --no-dependents=true --symfile $dsym $binary" -o 'settings set target.run-args test')
  else
    create=(-o "target create --no-dependents=true $binary" -o "target modules add --symfile $dsym $image")
  fi
  debugger=(lldb)
  if sudo -n true 2>/dev/null; then debugger=(sudo -n /usr/bin/lldb); fi
  # The dollar sign belongs to the Java nested-class name sent to LLDB.
  # shellcheck disable=SC2016
  "${debugger[@]}" --batch "${create[@]}" -o 'image list' \
    -o "breakpoint set --func-regex 'DebugQualityProbe.*checkpoint' --skip-prologue false" \
    -o run -o 'frame info' -o 'thread backtrace' -o 'frame select 1' -o 'frame variable value' \
    -o 'image lookup -t "debugquality.DebugQualityProbe$DataOnly"' \
    -o "command script import $out/debug_quality_lldb.py" -o debug-quality-check > "$out/$spec.lldb.txt" 2>&1
  grep -q 'stop reason = breakpoint' "$out/$spec.lldb.txt"
  grep -q 'DebugQualityProbe.java:' "$out/$spec.lldb.txt"
  grep -q 'inner' "$out/$spec.lldb.txt"
  grep -q 'outer' "$out/$spec.lldb.txt"
  grep -Eq 'value = 18([^0-9]|$)' "$out/$spec.lldb.txt"
done
shasum -a 256 "$out/probe" "$out/libprobe.dylib" "$out"/*.dSYM/Contents/Resources/DWARF/* > "$out/SHA256SUMS"
