#!/usr/bin/env bash
# Installs the Android SDK packages the build needs (uses the runner's preinstalled SDK when present).
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Android cmdline-tools not found; installing into $SDK"
  mkdir -p "$SDK/cmdline-tools"
  curl -sSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/cmdtools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0" "platform-tools" > /dev/null
echo "ANDROID_HOME=$SDK" >> "$GITHUB_ENV"
echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
echo "sdk.dir=$SDK" > local.properties
