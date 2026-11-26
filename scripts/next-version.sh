#!/bin/sh
# SPDX-FileCopyrightText: 2026 anderewrey
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The minor version after the last version tag reachable from a commit: 0.20 -> 0.21; 0.1 when
# there is none. DESIGN.md §8, GitHub Actions.
#   scripts/next-version.sh [commit]
set -eu
last=$(git describe --tags --abbrev=0 --match "[0-9]*" ${1:-HEAD} 2>/dev/null || echo 0.0)
echo "$last" | awk -F. '{ print $1 "." $2 + 1 }'
