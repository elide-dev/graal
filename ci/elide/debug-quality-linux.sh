#!/usr/bin/env bash
set -euxo pipefail
sdk=${1:?SDK home}
out=${2:?qualification output}
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)
mkdir -p "$out/src/debugquality" "$out/classes"
cp "$scripts/DebugQualityProbe.java" "$out/src/debugquality/DebugQualityProbe.java"
"$sdk/bin/javac" --add-modules org.graalvm.nativeimage -g \
  -cp "$sdk/lib/svm/svm-shared.jar" -d "$out/classes" "$out/src/debugquality/DebugQualityProbe.java"
"$sdk/bin/native-image" -J-Xmx5g -J-XX:ActiveProcessorCount=4 -g -O2 \
  -H:-SourceLevelDebug -H:+TrackNodeSourcePosition -H:+DebugCodeInfoUseSourceMappings \
  -H:DebugInfoSourceSearchPath="$out/src" --initialize-at-build-time=debugquality \
  -cp "$out/classes" debugquality.DebugQualityProbe -o "$out/probe" > "$out/build.log" 2>&1
test "$("$out/probe" test)" = '42:84'
symbol=$(nm "$out/probe.debug" | awk '/DebugQualityProbe.*checkpoint/ && $2 ~ /[tT]/ && !found { print $3; found=1 }')
test -n "$symbol"
cat > "$out/values.gdb" <<'GDB'
set pagination off
set disable-randomization off
set args test
GDB
printf 'break %s\nrun\n' "$symbol" >> "$out/values.gdb"
cat >> "$out/values.gdb" <<'GDB'
backtrace
info args
info locals
python
import gdb
frame = gdb.newest_frame()
names = []
value_frame = None
while frame:
    name = frame.name() or ''
    names.append(name)
    if 'inner' in name:
        value_frame = frame
    frame = frame.older()
assert value_frame is not None, names
value_frame.select()
assert value_frame.find_sal().symtab.filename.endswith('DebugQualityProbe.java')
assert int(gdb.parse_and_eval('value')) == 18
assert any('inner' in name for name in names), names
assert any('outer' in name for name in names), names
symbol = gdb.lookup_global_symbol('debugquality.DebugQualityProbe$DataOnly', gdb.SYMBOL_STRUCT_DOMAIN)
assert symbol is not None
assert symbol.symtab.filename.endswith('DebugQualityProbe.java'), symbol.symtab.filename
fields = [field.name for field in symbol.type.fields()]
assert 'value' in fields and 'other' in fields, fields
print('Optimized source, inline chain, live values and code-less declaration verified')
end
disable breakpoints
continue
GDB
gdb --batch -q -x "$out/values.gdb" "$out/probe" > "$out/gdb.txt" 2>&1
grep -q 'Optimized source, inline chain, live values and code-less declaration verified' "$out/gdb.txt"
sha256sum "$out/probe" "$out/probe.debug" > "$out/SHA256SUMS"

# A failed debug split must abort before the executable can lose its only DWARF copy.
mkdir -p "$out/failing-objcopy"
cat > "$out/failing-objcopy/objcopy" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == --version ]]; then exec "$REAL_OBJCOPY" "$@"; fi
if [[ ${1:-} == --only-keep-debug ]]; then
  echo 'controlled separate-debug write failure' >&2
  exit 42
fi
touch "$STRIP_AFTER_FAILURE"
exec "$REAL_OBJCOPY" "$@"
SH
chmod +x "$out/failing-objcopy/objcopy"
set +e
REAL_OBJCOPY="$(command -v objcopy)" STRIP_AFTER_FAILURE="$out/stripped-after-failure" \
  PATH="$out/failing-objcopy:$PATH" \
  "$sdk/bin/native-image" -J-Xmx5g -J-XX:ActiveProcessorCount=4 -g -O2 \
  -H:+ReportExceptionStackTraces -H:-SourceLevelDebug -H:+TrackNodeSourcePosition \
  -H:+DebugCodeInfoUseSourceMappings -H:DebugInfoSourceSearchPath="$out/src" \
  --initialize-at-build-time=debugquality -cp "$out/classes" debugquality.DebugQualityProbe \
  -o "$out/split-failure" > "$out/split-failure.log" 2>&1
split_status=$?
set -e
test "$split_status" -ne 0
grep -q 'Native debug command exited with code 42' "$out/split-failure.log"
test ! -e "$out/stripped-after-failure"
if [[ -f "$out/split-failure" ]]; then
  readelf -S "$out/split-failure" | grep -q '\.debug_info'
fi
