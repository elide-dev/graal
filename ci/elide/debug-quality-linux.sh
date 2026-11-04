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
  -H:-SourceLevelDebug -H:+TrackNodeSourcePosition -H:+DebugInfoNonSafepoints \
  -H:DebugInfoSourceSearchPath="$out/src" --initialize-at-build-time=debugquality \
  -cp "$out/classes" debugquality.DebugQualityProbe -o "$out/probe" > "$out/build.log" 2>&1
test "$("$out/probe" test)" = '42:84'
line=$(awk '/DEBUG_QUALITY_INNER/ {print NR}' "$out/src/debugquality/DebugQualityProbe.java")
cat > "$out/values.gdb" <<'GDB'
set pagination off
set disable-randomization off
set args test
GDB
printf 'break DebugQualityProbe.java:%s\nrun\n' "$line" >> "$out/values.gdb"
cat >> "$out/values.gdb" <<'GDB'
backtrace
info args
info locals
python
import gdb
frame = gdb.newest_frame()
assert frame.find_sal().symtab.filename.endswith('DebugQualityProbe.java')
assert int(gdb.parse_and_eval('value')) == 18
assert int(gdb.parse_and_eval('shifted')) == 21
names = []
while frame:
    names.append(frame.name() or '')
    frame = frame.older()
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
