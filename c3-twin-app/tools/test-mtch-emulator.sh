#!/bin/bash
# Requires built app. Does not connect to network devices, and rejects non-emulator targets.
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL=${1:?Usage: test-mtch-emulator.sh emulator-PORT}
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Emulator only' >&2; exit 1; }
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes" "$OUT/dex"
cat > "$OUT/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="fr.dabi.c3twin.tests">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="29"/>
<application android:label="C3 Twin synthetic tests"/>
<instrumentation android:name="fr.dabi.c3twin.MtcInstrumentedTest" android:targetPackage="fr.dabi.c3twin"/>
</manifest>
MANIFEST
javac --release 11 -cp "$JAR:build/apk/classes" -d "$OUT/classes" tests/MtcInstrumentedTest.java
"$BT/d8" --min-api 26 --lib "$JAR" --classpath build/apk/classes --output "$OUT/dex" "$OUT/classes/fr/dabi/c3twin/MtcInstrumentedTest.class"
"$BT/aapt2" link -I "$JAR" --manifest "$OUT/AndroidManifest.xml" -o "$OUT/test.apk"
(cd "$OUT/dex" && zip -q "$OUT/test.apk" classes.dex)
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android "$OUT/test.apk"
adb -s "$SERIAL" install -r build/C3Twin.apk
adb -s "$SERIAL" install -r "$OUT/test.apk"
for phase in save reload; do
  adb -s "$SERIAL" shell am instrument -w -e phase "$phase" fr.dabi.c3twin.tests/fr.dabi.c3twin.MtcInstrumentedTest > "$OUT/result"
  cat "$OUT/result"
  grep -q "MTCH $phase tests passed" "$OUT/result"
done
adb -s "$SERIAL" uninstall fr.dabi.c3twin.tests
