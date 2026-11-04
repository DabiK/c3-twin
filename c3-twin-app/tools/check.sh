#!/bin/bash
# Local checks only: never connect to a head unit or install an APK.
set -euo pipefail
cd "$(dirname "$0")/.."
bash -n build.sh installer_autoradio.sh tools/check.sh tools/capture-carplay.sh
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" android/src/fr/dabi/c3twin/RaiseLights.java tests/RaiseLightsTest.java
java -cp "$OUT" fr.dabi.c3twin.RaiseLightsTest
node --check web/main.js
node --check web/alertes.js
./build.sh
