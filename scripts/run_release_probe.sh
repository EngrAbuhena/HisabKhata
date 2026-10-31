#!/usr/bin/env bash
# Build a self-contained test-only APK and exercise the installed, minified release.
# The probe uses a temporary in-memory database; it never deletes the app's data.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
: "${KEYSTORE_PATH:?Set KEYSTORE_PATH to the same keystore used for the installed release}"
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?Set KEY_ALIAS}"
: "${KEY_PASSWORD:?Set KEY_PASSWORD}"
export PATH="$JAVA_HOME/bin:$PATH"
[[ -f "$KEYSTORE_PATH" ]] || { echo 'Keystore file not found' >&2; exit 1; }

bt="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
adb="$ANDROID_HOME/platform-tools/adb"
app="app/build/outputs/apk/release/app-release.apk"
mapping="app/build/outputs/mapping/release/mapping.txt"
[[ -f "$app" && -f "$mapping" ]] || { echo 'Build the signed release and its R8 mapping first.' >&2; exit 1; }
for tool in "$bt/aapt" "$bt/d8" "$bt/zipalign" "$bt/apksigner" "$adb" "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/jar"; do
  [[ -x "$tool" ]] || { echo "Missing tool: $tool" >&2; exit 1; }
done
[[ -f "$platform" ]] || { echo 'Android SDK platform 35 not found' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required' >&2; exit 1; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/hisab-release-probe.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/dex"

# Verify that the device has exactly the release whose mapping is being used.
installed=$("$adb" shell pm path com.hisabkhata | sed -n '1s/^package://p' | tr -d '\r')
[[ -n "$installed" ]] || { echo 'Install app-release.apk on a test device first.' >&2; exit 1; }
"$adb" pull "$installed" "$tmp/installed.apk" >/dev/null
cmp -s "$app" "$tmp/installed.apk" || { echo 'Installed APK differs from app-release.apk; install the matching build first.' >&2; exit 1; }

# These four app-runtime classes may be renamed by R8; take their names from the
# matching mapping rather than shipping fixed obfuscated names.
map_names=$(python3 - "$mapping" <<'PY'
import re, sys
mapping = open(sys.argv[1], encoding='utf-8').read()
for original in ('kotlin.Pair', 'kotlin.coroutines.EmptyCoroutineContext',
                 'kotlin.coroutines.intrinsics.CoroutineSingletons', 'kotlin.Result$Failure'):
    match = re.search(r'^' + re.escape(original) + r' -> ([^:]+):$', mapping, re.M)
    if not match or match.group(1).startswith('R8$$REMOVED$$'):
        raise SystemExit('Required runtime mapping missing: ' + original)
    print(match.group(1))
PY
)
read -r pair_name empty_name suspended_name failure_name <<< "$(printf '%s' "$map_names" | tr '\n' ' ')"
"$bt/aapt" package -f -M scripts/release-smoke/AndroidManifest.xml -I "$platform" -F "$tmp/base.apk"
"$JAVA_HOME/bin/javac" --release 8 -classpath "$platform" -d "$tmp/classes" \
  scripts/release-smoke/com/hisabkhata/releaseprobe/ReleaseProbe.java
"$JAVA_HOME/bin/jar" cf "$tmp/classes.jar" -C "$tmp/classes" .
"$bt/d8" --min-api 24 --output "$tmp/dex" "$tmp/classes.jar"
python3 - "$tmp/base.apk" "$tmp/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
PY
"$bt/zipalign" -f 4 "$tmp/base.apk" "$tmp/aligned.apk"
# Passwords remain in environment variables, not command-line arguments or logs.
"$bt/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD \
  --out "$tmp/probe.apk" "$tmp/aligned.apk"
"$adb" install -r "$tmp/probe.apk"

run=("$adb" shell am instrument -w -r -e pairClass "$pair_name" \
     -e emptyContext "$empty_name" -e suspendedClass "$suspended_name" \
     -e failureClass "$failure_name" \
     com.hisabkhata.releaseprobe/com.hisabkhata.releaseprobe.ReleaseProbe)
if command -v timeout >/dev/null; then
  timeout 180s "${run[@]}" | tee "$tmp/results.txt"
else
  "${run[@]}" | tee "$tmp/results.txt"
fi
grep -q 'RELEASE PROBE PASS' "$tmp/results.txt" || { echo 'Release probe failed' >&2; exit 1; }
echo 'Probe passed. The separate test package can be removed with: adb uninstall com.hisabkhata.releaseprobe'
