#!/bin/bash
# Archived fixture: incompatible with the active application and Zlink 5.
echo "Archive inactive : lire experiments/tlink4/README.md." >&2
exit 1
# Tests de l'écran CarPlay de C3 Twin sur l'émulateur, contre le faux ZLink (tools/fox_fixture.py) :
# baisse de la musique pendant la voix, changement de format audio, marges recadrées (position
# du doigt), touches du volant, coupure et reprise de la vidéo, allers-retours répétés avec le
# tableau de bord (pages web libérées, mémoire, plantage).
#
# Usage : ./tools/test_robustesse.sh [serial adb]      (l'émulateur doit être démarré)
set -uo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-adb}
[ -n "${1:-}" ] && ADB="$ADB -s $1"
OUT=build/robustesse
LOG=$OUT/fixture.log
PKG=fr.dabi.c3twin
mkdir -p "$OUT"
OK=0; KO=0
ok() { echo "  ok    $1"; OK=$((OK + 1)); }
ko() { echo "  ÉCHEC $1"; KO=$((KO + 1)); }
check() { if eval "$2"; then ok "$1"; else ko "$1"; fi; }

cleanup() { [ -n "${FIXTURE_PID:-}" ] && kill "$FIXTURE_PID" 2>/dev/null; $ADB reverse --remove-all >/dev/null 2>&1; }
trap cleanup EXIT

echo "Construction et installation"
VERSION_CODE=${VERSION_CODE:-$(date +%s | cut -c1-9)} ./build.sh >/dev/null 2>&1 || { echo "build en échec"; exit 1; }
$ADB install -r build/C3Twin.apk >/dev/null || { echo "installation en échec"; exit 1; }

echo "Faux ZLink : voix 3 s / silence 3 s, audio en 44,1 kHz mono à 12 s, coupure vidéo à 22 s"
pkill -f fox_fixture.py 2>/dev/null; sleep 0.5
python3 -u tools/fox_fixture.py --system-burst 3 --audio-switch 12 --video-stop 22 > "$LOG" 2>&1 &
FIXTURE_PID=$!
sleep 1.5
for p in 51818 51838 51848 51858 51888; do $ADB reverse tcp:$p tcp:$((p + 1000)) >/dev/null; done

$ADB shell am force-stop $PKG
$ADB logcat -c
$ADB shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
$ADB shell input tap 575 682            # CarPlay, dans le dock
sleep 7
PID=$($ADB shell pidof $PKG | tr -d '\r')

echo "Marges et position du doigt (mire 640×360, marges 8/4/8/4)"
$ADB exec-out screencap -p > "$OUT/carplay.png"
# where the app placed the frame: "image 640x360 marges 8/4/8/4 écran X,Y WxH"
place=$($ADB logcat -d -s C3Twin:* | grep 'Projection: image' | tail -1)
read -r sx sy sw sh <<< "$(echo "$place" | sed -E 's/.*écran (-?[0-9]+),(-?[0-9]+) ([0-9]+)x([0-9]+).*/\1 \2 \3 \4/')"
echo "        $place"
if [ -z "${sw:-}" ] || [ -z "${sh:-}" ]; then
  ko "l'app indique où elle place l'image"
else
  # the middle of the frame, then a point just inside its useful area (frame 10:6)
  $ADB shell input tap $((sx + sw / 2)) $((sy + sh / 2)); sleep 0.5
  $ADB shell input tap $((sx + sw * 10 / 640)) $((sy + sh * 6 / 360)); sleep 1
  centre=$(grep 'STATE:UP' "$LOG" | sed -n 1p)
  coin=$(grep 'STATE:UP' "$LOG" | sed -n 2p)
  echo "        centre → $centre ; coin utile → $coin"
  check "le milieu de l'image arrive au milieu (≈320:180)" \
    "echo '$centre' | grep -qE '#3(1[5-9]|2[0-5]):1(7[5-9]|8[0-5])#'"
  check "un point juste dans la zone utile arrive à ≈10:6" \
    "echo '$coin' | awk -F'[#:]' '{ exit !(\$4 >= 8 && \$4 <= 12 && \$5 >= 4 && \$5 <= 8) }'"
  # the useful area fills the CarPlay area on its limiting side: the margins are off screen there
  check "les marges sont rognées sur le côté qui limite" \
    "[ $sy -lt 48 ] || [ $sx -lt 0 ]"
fi

echo "Touches du volant"
$ADB shell input keyevent KEYCODE_MEDIA_NEXT
$ADB shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
sleep 1
check "le canal touches est connecté" "grep -q 'touches: client connecté' '$LOG'"
check "suivant et lecture/pause arrivent à ZLink" "grep -q 'KEY VALUE:87#' '$LOG' && grep -q 'KEY VALUE:85#' '$LOG'"

echo "Son"
sleep 8                                  # jusqu'à ~22 s : plusieurs cycles voix/silence et le changement de format
$ADB logcat -d -s C3Twin:* > "$OUT/logcat.txt"
check "la musique baisse quand la voix parle" "grep -q 'musique baissée' '$OUT/logcat.txt'"
check "la musique revient quand la voix se tait" "grep -q 'musique rétablie' '$OUT/logcat.txt'"
check "le changement en 44,1 kHz mono est suivi" "grep -q 'Audio 51858: 44100 Hz, 1 ch' '$OUT/logcat.txt'"

echo "Coupure et reprise de la vidéo"
sleep 10
check "après la coupure, l'app se reconnecte" "grep -q 'connexion 2' '$LOG'"
$ADB exec-out screencap -p > "$OUT/apres-coupure.png"

echo "Allers-retours tableau de bord ↔ CarPlay (8 fois)"
pages() {
  local pid; pid=$($ADB shell pidof $PKG | tr -d '\r')
  $ADB forward tcp:9334 localabstract:webview_devtools_remote_$pid >/dev/null 2>&1
  curl -s http://localhost:9334/json | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?"
}
mem() { $ADB shell dumpsys meminfo $PKG | awk '/TOTAL PSS:/{print $3; exit} /TOTAL:/{print $2; exit}'; }
avant_pages=$(pages); avant_mem=$(mem)
for i in 1 2 3 4 5 6 7 8; do
  $ADB shell input tap 38 22; sleep 2      # « ‹ Voiture »
  $ADB shell input tap 575 682; sleep 3    # CarPlay
done
$ADB shell input tap 38 22; sleep 3
apres_pages=$(pages); apres_mem=$(mem)
echo "        pages web : $avant_pages → $apres_pages ; mémoire (ko) : $avant_mem → $apres_mem"
check "l'app n'a pas redémarré" "[ \"\$($ADB shell pidof $PKG | tr -d '\r')\" = '$PID' ]"
check "aucun plantage" "! $ADB logcat -d | grep -q 'FATAL EXCEPTION'"
check "les pages web des panneaux fermés sont libérées" "[ '$apres_pages' != '?' ] && [ $apres_pages -le $((avant_pages + 1)) ]"
check "la mémoire ne grossit pas de plus de 30 %" "[ $apres_mem -le $((avant_mem * 13 / 10)) ]"

echo
echo "$OK réussis, $KO en échec. Fichiers : $OUT/"
[ $KO -eq 0 ]
