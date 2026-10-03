#!/usr/bin/env bash
#
# Checks that the files under the given paths run on glibc FLOOR (e.g. 2.28) or newer:
#   - no ELF executable or shared library needs a GLIBC_ symbol version newer than FLOOR;
#   - no static archive or object references glibc's C23 entry points (__isoc23_*), which glibc
#     2.38 and newer headers substitute for sscanf, strtol and friends. Static archives carry no
#     symbol versions, so link something against them (e.g. a smoke-test image) and pass it too.
#
# Usage: check-glibc.sh FLOOR PATH...
#
set -euo pipefail

floor=${1:?usage: check-glibc.sh FLOOR PATH...}
shift
[ $# -gt 0 ] || { echo "usage: check-glibc.sh FLOOR PATH..." >&2; exit 2; }

newer_than_floor() {
  # True if version $1 is newer than the floor.
  [ "$1" != "${floor}" ] && [ "$(printf '%s\n%s\n' "${floor}" "$1" | sort -V | tail -1)" = "$1" ]
}

failures=0
elfs=0
archives=0
while IFS= read -r -d '' f; do
  case "${f}" in
    *.a | *.o)
      archives=$((archives + 1))
      if newer_than_floor 2.38 && syms=$(nm -A "${f}" 2>/dev/null | grep -oE '__isoc23_[A-Za-z0-9_]+' | sort -u | tr '\n' ' ') && [ -n "${syms}" ]; then
        echo "::error file=${f}::references glibc 2.38+ C23 entry points: ${syms}"
        failures=$((failures + 1))
      fi
      ;;
    *)
      # Reads the whole 4 bytes: no early-exiting reader, so no SIGPIPE under pipefail.
      [ "$(head -c 4 "${f}" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = 7f454c46 ] || continue
      elfs=$((elfs + 1))
      too_new=""
      for v in $(objdump -T "${f}" 2>/dev/null | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sed 's/^GLIBC_//' | sort -uV); do
        if newer_than_floor "${v}"; then
          too_new="${too_new} GLIBC_${v}($(objdump -T "${f}" | grep -F "(GLIBC_${v})" | awk '{print $NF}' | sort -u | tr '\n' ',' | sed 's/,$//'))"
        fi
      done
      if [ -n "${too_new}" ]; then
        echo "::error file=${f}::needs glibc newer than ${floor}:${too_new}"
        failures=$((failures + 1))
      fi
      ;;
  esac
done < <(find "$@" -type f \( -name '*.a' -o -name '*.o' -o -name '*.so' -o -name '*.so.*' -o -perm -u+x \) -print0)

echo "glibc floor ${floor}: checked ${elfs} ELF files and ${archives} archives/objects, ${failures} too new"
[ "${failures}" -eq 0 ]
