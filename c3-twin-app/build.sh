#!/bin/bash
# Builds build/C3Twin.apk without Gradle: esbuild for the page, then the SDK's own tools
# (aapt2, javac, d8, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
BT=$SDK/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}
JAR=$SDK/platforms/android-${ANDROID_PLATFORM:-34}/android.jar
OUT=build/apk
VERSION_CODE=${VERSION_CODE:-22}
VERSION_NAME=${VERSION_NAME:-0.22-public}

for command in node javac java keytool zip; do
  command -v "$command" >/dev/null || { echo "Commande manquante : $command" >&2; exit 1; }
done
for tool in aapt2 d8 zipalign apksigner; do
  [ -x "$BT/$tool" ] || { echo "Outil SDK absent : $BT/$tool (voir docs/installation.md)" >&2; exit 1; }
done
[ -f "$JAR" ] || { echo "Plateforme SDK absente : $JAR" >&2; exit 1; }
[ -x node_modules/.bin/esbuild ] || { echo "Exécuter npm ci dans c3-twin-app avant de compiler." >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/assets/web/draco" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "page"
./node_modules/.bin/esbuild web/main.js --bundle --minify --target=chrome69 --format=iife --outfile=web/app.js --log-level=warning
cp web/index.html web/app.js web/car-mobile.glb "$OUT/assets/web/"
cp web/draco/draco_decoder.js web/draco/draco_wasm_wrapper.js web/draco/draco_decoder.wasm "$OUT/assets/web/draco/"

echo "ressources"
"$BT/aapt2" compile --dir android/res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest android/AndroidManifest.xml -o "$OUT/base.apk" \
  --java "$OUT/gen" -A "$OUT/assets" -0 glb -0 wasm \
  --min-sdk-version 26 --target-sdk-version 29 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

echo "code"
javac --release 11 -nowarn -encoding UTF-8 -cp "$JAR" -d "$OUT/classes" \
  $(find android/src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "paquet"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# a local key, only to sign test builds
KS=build/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias c3twin \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=C3 Twin" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out build/C3Twin.apk "$OUT/aligned.apk"
ls -la build/C3Twin.apk
