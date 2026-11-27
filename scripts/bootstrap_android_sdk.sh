#!/usr/bin/env bash
# ============================================================================
# Provision a throwaway Android build environment inside the sandbox.
#
# Everything lands under ~/.arena so it stays out of the repository and out of
# workspace snapshots. Safe to re-run: each step is skipped when already done.
#
# Does NOT touch app source, signing keys, or the network beyond downloading
# the SDK and Gradle.
# ============================================================================
set -euo pipefail

ARENA="$HOME/.arena"
SDK="$ARENA/android-sdk"
JDK="$ARENA/jdk17"
GRADLE="$ARENA/gradle-8.9"
CMDLINE_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
GRADLE_SHA256="d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"

mkdir -p "$ARENA"

log() { echo "[bootstrap] $*"; }

# ------------------------------------------------------------------ JDK 21 --
# The distro ships JDK 21 (not 17). AGP 8.7 needs 17+ and the project targets
# Java 17 bytecode, so 21 works; set jvmTarget explicitly in the build.
if [[ ! -x "$JDK/bin/java" ]]; then
    log "installing OpenJDK 21"
    sudo apt-get install -y --no-install-recommends openjdk-21-jdk-headless >/dev/null
    ln -sfn /usr/lib/jvm/java-21-openjdk-amd64 "$JDK"
fi
"$JDK/bin/java" -version 2>&1 | head -1

# ------------------------------------------------------- Android SDK tools --
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "downloading Android command-line tools"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/cmdline.zip" \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    echo "$CMDLINE_SHA256  $tmp/cmdline.zip" | sha256sum -c - >/dev/null
    mkdir -p "$SDK/cmdline-tools"
    unzip -q "$tmp/cmdline.zip" -d "$tmp/unpacked"
    mv "$tmp/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
fi

export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

if [[ ! -d "$SDK/platforms/android-35" ]]; then
    log "accepting licences and installing platform 35 + build-tools"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
        "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null
fi

# --------------------------------------------------------------- Gradle 8.9 --
if [[ ! -x "$GRADLE/bin/gradle" ]]; then
    log "downloading Gradle 8.9"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/gradle.zip" \
        https://services.gradle.org/distributions/gradle-8.9-bin.zip
    echo "$GRADLE_SHA256  $tmp/gradle.zip" | sha256sum -c - >/dev/null
    unzip -q "$tmp/gradle.zip" -d "$ARENA"
    rm -rf "$tmp"
fi

log "ready: JAVA_HOME=$JDK ANDROID_HOME=$SDK GRADLE=$GRADLE/bin/gradle"
