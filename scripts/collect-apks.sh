#!/bin/sh
# SPDX-FileCopyrightText: 2026 anderewrey
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Copies a build's APKs into out/ under the names of local builds (bus-tracker.apk for phones,
# bus-tracker-emulator.apk) and writes out/version.txt: "versionCode versionName".
#   scripts/collect-apks.sh debug|release
set -eu
type=$1
dir=app/build/outputs/apk/$type
mkdir -p out
cp "$dir/app-arm64-v8a-$type.apk" out/bus-tracker.apk
cp "$dir/app-x86_64-$type.apk" out/bus-tracker-emulator.apk
jq -r '.elements[0] | "\(.versionCode) \(.versionName)"' "$dir/output-metadata.json" > out/version.txt
cat out/version.txt
