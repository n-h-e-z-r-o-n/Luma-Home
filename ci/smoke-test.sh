#!/bin/sh
# Dedicated emulator only; previews without changing the default Home.
set -e
cd "$(dirname "$0")/.."
exec python3 ci/smoke_test.py "$@"
