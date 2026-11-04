#!/bin/bash
# Archived fixture: incompatible with the active application and Zlink 5.
echo "Archive inactive : lire experiments/tlink4/README.md." >&2
exit 1
# Teste l'écran CarPlay de C3 Twin sur l'émulateur, sans iPhone ni autoradio.
# Le fixture sert une mire H.264 et affiche les messages tactiles.
#
# Usage : ./tools/test_projection.sh [serial adb]
set -euo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-adb}
[ -n "${1:-}" ] && ADB="$ADB -s $1"
OUT=build/projection-test
LOG=$OUT/fixture.log
mkdir -p "$OUT"

cleanup() {
  [ -n "${FIXTURE_PID:-}" ] && kill "$FIXTURE_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "1/5 build"
./build.sh >/dev/null

echo "2/5 installation"
$ADB install -r build/C3Twin.apk >/dev/null

echo "3/5 fixture"
# libère les ports si un ancien fixture traîne encore
for port in 52818 52848; do
  pid=$(lsof -ti tcp:$port 2>/dev/null || true)
  [ -n "$pid" ] && kill $pid 2>/dev/null || true
done
pkill -f fox_fixture.py 2>/dev/null || true
sleep 0.5
python3 -u tools/fox_fixture.py > "$LOG" 2>&1 &
FIXTURE_PID=$!
sleep 1
if ! grep -q "écoute 52848" "$LOG"; then
  echo "Le fixture n'a pas démarré :"; cat "$LOG"; exit 1
fi
$ADB reverse tcp:51818 tcp:52818
$ADB reverse tcp:51848 tcp:52848
$ADB reverse tcp:51858 tcp:52858
$ADB reverse tcp:51888 tcp:52888

echo "4/5 ouverture depuis le dock"
$ADB shell am force-stop fr.dabi.c3twin
sleep 1
$ADB shell am start -n fr.dabi.c3twin/.MainActivity >/dev/null
sleep 5
$ADB shell input tap 575 682
sleep 5
if ! grep -q "vidéo: requête" "$LOG"; then
  echo "L'app ne s'est pas connectée au fixture :"; cat "$LOG"; exit 1
fi
grep -q "audio: requête" "$LOG" || echo "note : audio non connecté (le test vidéo reste valable)"

echo "5/5 capture et tactile"
$ADB exec-out screencap -p > "$OUT/ecran.png"
$ADB shell input tap 640 383
sleep 1
$ADB shell input swipe 400 300 700 400 300
sleep 1
$ADB exec-out screencap -p > "$OUT/ecran-apres-tactile.png"

echo
echo "--- fixture ---"
cat "$LOG"
echo
echo "--- logcat ---"
$ADB shell logcat -d -t 200 2>/dev/null \
  | grep -iE 'C3Twin|MediaCodec|FATAL EXCEPTION' | tail -15 || true
echo
echo "Captures : $OUT/ecran.png et $OUT/ecran-apres-tactile.png"
