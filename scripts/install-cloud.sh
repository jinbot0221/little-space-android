#!/usr/bin/env bash
# Reproduce the toolchain without changing application source or lockfiles.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p /workspace/toolchains /workspace/.gradle /workspace/.cache/firebase/emulators /workspace/.config
task_tmp=$(mktemp -d /tmp/littlespace-setup.XXXXXX)
trap 'rm -rf "$task_tmp"' EXIT
fetch_verified() {
    curl --fail --location --retry 2 "$1" --output "$task_tmp/artifact"
    printf '%s  %s\n' "$2" "$task_tmp/artifact" | "$3" --check --status
}
if [[ ! -x /workspace/toolchains/jdk-21.0.12.1/bin/javac ]]; then
    # The checksum pins the tested release. If Oracle rotates latest, fail
    # verification and update URL + checksum from the official source together.
    fetch_verified https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz 12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111 sha256sum
    tar -xf "$task_tmp/artifact" -C /workspace/toolchains
fi
if [[ ! -x /workspace/toolchains/node-v22.23.3-linux-x64/bin/node ]]; then
    fetch_verified https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de sha256sum
    tar -xf "$task_tmp/artifact" -C /workspace/toolchains
fi
source scripts/cloud-env.sh
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    fetch_verified https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip e025545c62a8e64c7559119566a569fb1dec5f60 sha1sum
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$task_tmp/artifact" -d "$task_tmp/android"
    mv "$task_tmp/android/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
# Configure the existing managed HTTPS proxy, preserving other Gradle settings.
python3 - <<'PYPROXY'
import os, urllib.parse
from pathlib import Path
p=Path('/workspace/.gradle/gradle.properties')
s=p.read_text() if p.exists() else ''
start='# BEGIN littlespace cloud proxy'
end='# END littlespace cloud proxy'
if start in s:
    before,rest=s.split(start,1)
    if end not in rest: raise RuntimeError('Incomplete managed proxy block')
    s=before+rest.split(end,1)[1]
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname:
    s+='\n'+start+'\n'+''.join(f'systemProp.{scheme}.proxyHost={u.hostname}\nsystemProp.{scheme}.proxyPort={u.port or 80}\n' for scheme in ['http','https'])+'systemProp.http.nonProxyHosts=localhost|127.*|[::1]\n'+end+'\n'
p.write_text(s)
PYPROXY
if [[ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" || ! -f "$ANDROID_HOME/build-tools/35.0.0/source.properties" || ! -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
fi
# Avoid overwriting a user's local SDK configuration.
if [[ ! -f local.properties ]]; then printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties; fi
npm --prefix firebase ci --no-audit --no-fund
./gradlew :app:assembleDebug :app:lintDebug --no-daemon
./scripts/install-firestore-emulator.sh
./scripts/test-integration.sh
