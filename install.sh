#!/bin/sh
# One explicit step per invocation. Default: install only.
set -e
cd "$(dirname "$0")"
if [ -z "$LUMA_HOME_STEP" ]; then LUMA_HOME_STEP=install; fi
export LUMA_HOME_STEP
exec sh ./install-step.sh "$@"
