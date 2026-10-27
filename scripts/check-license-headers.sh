#!/usr/bin/env bash
#
# Fails when a tracked source file is missing the GPLv3 license header.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

patterns=('*.java' '*.kt' '*.kts' '*.xml' '*.svg')
header_lines=30

missing=()
while IFS= read -r -d '' file; do
    header=$(head -n "$header_lines" "$file")
    if ! grep -qE 'VREM Software Development|Ambihertz contributors' <<<"$header" ||
        ! grep -q 'GNU General Public License' <<<"$header"; then
        missing+=("$file")
    fi
done < <(git ls-files -z "${patterns[@]}")

if [ ${#missing[@]} -gt 0 ]; then
    echo "ERROR: missing GPLv3 license header in ${#missing[@]} file(s):"
    printf '  %s\n' "${missing[@]}"
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        printf '::error file=%s,line=1::Missing GPLv3 license header\n' "${missing[@]}"
    fi
    exit 1
fi

echo "GPLv3 license header present in all ${#patterns[@]} checked file types."
