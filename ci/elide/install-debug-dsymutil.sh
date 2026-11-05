#!/usr/bin/env bash
set -euo pipefail
out=${1:?debug-linker installation directory}
test "$(uname -s)" = Darwin
test "$(uname -m)" = arm64
mkdir -p "$out"
out=$(cd "$out" && pwd)
archive="$out/elide-toolchain-2026.11.1-darwin-arm64.tar.xz"
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
  https://github.com/elide-dev/toolchain/releases/download/v2026.11.1/elide-toolchain-2026.11.1-darwin-arm64.tar.xz \
  --output "$archive"
printf '%s  %s\n' 37e56bad391f8397c99951a529ef84bdc99c9d19bdf91edbc153b5bdecb69e88 "$archive" | shasum -a 256 -c -
tool=$(python3 - "$archive" "$out/tools" <<'PYTHON'
from pathlib import Path
import sys
import tarfile
archive, target = Path(sys.argv[1]), Path(sys.argv[2])
with tarfile.open(archive) as stream:
    members = [member for member in stream.getmembers()
               if Path(member.name).name == 'dsymutil' or '.dylib' in Path(member.name).name]
    stream.extractall(target, members=members, filter='data')
tools = [path for path in target.rglob('dsymutil') if path.is_file()]
assert len(tools) == 1, tools
print(tools[0].resolve())
PYTHON
)
mkdir -p "$out/tool-bin"
# Select only the debug linker; preserve the normal compiler, linker and verifier.
printf '#!/usr/bin/env bash\nexec %q "$@"\n' "$tool" > "$out/tool-bin/dsymutil"
chmod +x "$out/tool-bin/dsymutil"
"$out/tool-bin/dsymutil" --version | tee "$out/version.txt"
printf '%s\n' "$out/tool-bin" >> "${GITHUB_PATH:?CI PATH output}"
