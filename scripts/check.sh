#!/usr/bin/env bash
# Mirrors the CI "verify" job (.github/workflows/android.yml): unit tests + debug lint.
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/lib.sh

./gradlew --no-daemon test lintDebug
