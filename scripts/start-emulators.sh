#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d /workspace/toolchains/jdk-21.0.12.1 ]]; then source scripts/cloud-env.sh; fi
sdk_emulator_dir=${LITTLESPACE_FIRESTORE_SDK_DIR:-/workspace/toolchains/firestore-emulator}
launcher="$sdk_emulator_dir/platform/cloud-firestore-emulator/cloud_firestore_emulator"
if [[ ! -x "$launcher" ]]; then
    exec firebase/node_modules/.bin/firebase emulators:start --project demo-teumgot --only auth,firestore
fi
# The SDK distribution has its own launcher (not a java -jar manifest).
auth_pid='';firestore_pid=''
cleanup() {
    for child in "$auth_pid" "$firestore_pid"; do
        if [[ -n "$child" ]]; then kill "$child" 2>/dev/null || true; fi
    done
    for child in "$auth_pid" "$firestore_pid"; do
        if [[ -n "$child" ]]; then wait "$child" 2>/dev/null || true; fi
    done
}
trap cleanup EXIT
trap 'exit 0' INT TERM
"$launcher" --host 127.0.0.1 --port 8080 --project_id demo-teumgot --rules firestore.rules &
firestore_pid=$!
firebase/node_modules/.bin/firebase emulators:start --project demo-teumgot --only auth &
auth_pid=$!
wait -n "$firestore_pid" "$auth_pid"
