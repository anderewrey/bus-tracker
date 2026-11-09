#!/bin/sh
# SPDX-FileCopyrightText: 2026 anderewrey
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Checks that source files carry the project's SPDX license header in their first lines.
#   scripts/check-license-headers.sh            every tracked source file (CI)
#   scripts/check-license-headers.sh --staged   the staged version of staged files (pre-commit hook)
# Source files: Kotlin (.kt), Gradle Kotlin scripts (.kts), shell scripts (.sh), and the git hooks.

LICENSE_LINE='SPDX-License-Identifier: GPL-3.0-or-later'
COPYRIGHT_LINE='SPDX-FileCopyrightText:'
LINES=5

is_source() {
    case "$1" in
        *.kt | *.kts | *.sh | .githooks/*) return 0 ;;
        *) return 1 ;;
    esac
}

if [ "$1" = "--staged" ]; then
    files=$(git diff --cached --name-only --diff-filter=ACMR)
    show() { git show ":$1"; }
else
    files=$(git ls-files)
    show() { cat "$1"; }
fi

missing=0
for f in $files; do
    is_source "$f" || continue
    head=$(show "$f" | head -n "$LINES")
    if ! printf '%s\n' "$head" | grep -q "$LICENSE_LINE" || ! printf '%s\n' "$head" | grep -q "$COPYRIGHT_LINE"; then
        echo "missing license header: $f"
        missing=$((missing + 1))
    fi
done

if [ "$missing" -gt 0 ]; then
    echo
    echo "$missing file(s) need these two lines at the top (use # instead of // in shell scripts):"
    echo "  // SPDX-FileCopyrightText: $(date +%Y) <your name>"
    echo "  // $LICENSE_LINE"
    exit 1
fi
echo "license headers: ok"
