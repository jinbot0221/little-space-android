#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d /workspace/toolchains/jdk-21.0.12.1 ]]; then source scripts/cloud-env.sh; fi
exec functions/node_modules/.bin/firebase emulators:start --project demo-teumgot --only auth,firestore,functions
