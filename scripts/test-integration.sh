#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d /workspace/toolchains/jdk-21.0.12.1 ]]; then source scripts/cloud-env.sh; fi
# Never stop or reuse unknown running emulators: tests clear their test database.
python3 - <<'PY'
import socket
for port in [8080,9099]:
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1',port))==0:
            raise SystemExit(f'Port {port} is occupied. Stop your own emulator before running this destructive local test suite.')
PY
test_logs=$(mktemp -d /tmp/littlespace-spark-tests.XXXXXX)
./scripts/start-emulators.sh > "$test_logs/emulators.log" 2>&1 &
startup_pid=$!
cleanup() { kill "$startup_pid" 2>/dev/null || true; wait "$startup_pid" 2>/dev/null || true; }
trap cleanup EXIT
python3 - "$startup_pid" <<'PY'
import os,socket,sys,time
for attempt in range(60):
    os.kill(int(sys.argv[1]),0)
    ready=True
    for port in [8080,9099]:
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=1):pass
        except OSError:ready=False
    if ready:break
    time.sleep(1)
else:raise SystemExit('Emulator startup timed out; inspect the log directory printed by this script.')
PY
printf 'Emulator log: %s/emulators.log\n' "$test_logs"
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 npm --prefix firebase test
