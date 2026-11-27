#!/usr/bin/env bash
# Build helper for this 2 GB sandbox: one worker, small heap, Kotlin compiled
# in-process (no separate 300 MB Kotlin daemon), and memory limits that let the
# daemon be reclaimed instead of swapping the whole VM to death.
set -uo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME=/home/user/.arena/jdk17
export ANDROID_HOME=/home/user/.arena/android-sdk
export GRADLE_USER_HOME=/home/user/.arena/gradle-home

exec ./gradlew --no-daemon \
  --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx900m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8" \
  -Dkotlin.compiler.execution.strategy=in-process \
  -Dkotlin.incremental=false \
  "$@"
