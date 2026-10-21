#!/usr/bin/env bash
# Official Google Cloud SDK distribution, SHA-256 verified; no artifact rewriting.
set -euo pipefail
sdk_emulator_dir=${LITTLESPACE_FIRESTORE_SDK_DIR:-/workspace/toolchains/firestore-emulator}
if [[ -x "$sdk_emulator_dir/platform/cloud-firestore-emulator/cloud_firestore_emulator" ]]; then exit 0; fi
sdk_archive=$(mktemp /tmp/littlespace-firestore.XXXXXX)
trap 'rm -f "$sdk_archive"' EXIT
curl --fail --location --retry 2 https://dl.google.com/dl/cloudsdk/channels/rapid/components/google-cloud-sdk-cloud-firestore-emulator-20260717053915.tar.gz --output "$sdk_archive"
printf '%s  %s\n' e962450b637827664094959571fd92c3680de863936bd481bd0a050f76ca91ba "$sdk_archive" | sha256sum --check --status
mkdir -p "$sdk_emulator_dir"
tar -xf "$sdk_archive" -C "$sdk_emulator_dir"
