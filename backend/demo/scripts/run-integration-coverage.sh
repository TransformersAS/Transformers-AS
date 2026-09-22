#!/usr/bin/env bash
# Run from any directory. Requires JDK 21, Docker and Python 3 (allowlist audit only).
set -euo pipefail
backend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_dir"
python3 scripts/integration-suite.py
./mvnw -Pintegration-coverage clean verify
