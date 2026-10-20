#!/usr/bin/env bash
# Source this file in cloud shells. Android Studio users do not need it.
export JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1
export ANDROID_HOME=/workspace/toolchains/android
export ANDROID_USER_HOME=/workspace/toolchains/android-user
export GRADLE_USER_HOME=/workspace/.gradle
export FIREBASE_EMULATORS_PATH=/workspace/.cache/firebase/emulators
export XDG_CONFIG_HOME=/workspace/.config
export npm_config_cache=/workspace/.npm
export PATH="/workspace/toolchains/node-v22.23.3-linux-x64/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Use the machine's trusted CA store, including the managed HTTPS proxy CA.
case "${JAVA_TOOL_OPTIONS:-}" in
  *javax.net.ssl.trustStore*) ;;
  *) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts" ;;
esac
