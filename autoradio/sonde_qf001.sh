#!/bin/bash
# Sonde QF001 : relève ce que l'autoradio expose sur la voiture, sans rien y modifier.
# Lecture seule : propriétés, paquets, services, réglages, journal du MCU, copie des apps du constructeur,
# relevé de ZLink (version, ports, fichiers) et, si tu le veux, quelques secondes de son flux CarPlay.
#
# Usage :
#   ./sonde_qf001.sh <IP_AUTORADIO>    autoradio et Mac sur le même Wi-Fi (adb sans fil, port 9876)
#   ./sonde_qf001.sh usb             câble USB
#   ./sonde_qf001.sh <ip|usb> zlink  seulement l'identité, les apps et ZLink (2 minutes, sans le parcours)
#
# Résultat : un dossier releve_<date>/ et son archive .tar.gz, à me renvoyer.

set -u
CIBLE=${1:-}
if [ -z "$CIBLE" ]; then
  echo "Usage : $0 <adresse IP de l'autoradio> | usb"; exit 1
fi
command -v adb >/dev/null || { echo "adb introuvable (brew install android-platform-tools)"; exit 1; }

if [ "$CIBLE" != "usb" ]; then
  adb connect "$CIBLE:9876" || exit 1
  export ANDROID_SERIAL="$CIBLE:9876"
fi
adb wait-for-device

OUT="releve_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"
sh_() { adb shell "$@" < /dev/null 2>&1 | tr -d '\r'; }

echo "1/5  Identité de l'autoradio"
sh_ getprop > "$OUT/getprop.txt"
sh_ id > "$OUT/id.txt"
sh_ uname -a >> "$OUT/id.txt"
grep -iE 'qf|mcu|can|version|platform|fingerprint|incremental' "$OUT/getprop.txt" > "$OUT/getprop_utile.txt"
echo "   utilisateur adb : $(cat "$OUT/id.txt" | head -1)"

echo "2/5  Apps, services et réglages"
sh_ pm list packages -f > "$OUT/paquets.txt"
sh_ service list > "$OUT/services.txt"
sh_ settings list global > "$OUT/settings_global_avant.txt"
sh_ settings list system > "$OUT/settings_system.txt"
sh_ 'ls -l /dev/ttyS* /dev/ttyGS* /dev/ttyUSB* /dev/ttyACM*' > "$OUT/ports_serie.txt"
for p in com.qf.vehicle com.qf.framework com.qf.backcar com.zjinnova.zlink; do
  sh_ dumpsys package "$p" > "$OUT/dumpsys_$p.txt"
done
grep -E 'qf|mcu|util|can' "$OUT/services.txt"

echo "3/5  Copie des apps du constructeur (pour les lire hors de la voiture)"
for p in com.qf.vehicle com.qf.framework; do
  chemin=$(sh_ pm path "$p" | head -1 | sed 's/^package://')
  if [ -n "$chemin" ]; then adb pull "$chemin" "$OUT/$p.apk" >/dev/null && echo "   $p copiée"; fi
done

echo "4/5  ZLink (CarPlay)"
Z="$OUT/zlink"
mkdir -p "$Z"
grep -E 'versionName|versionCode|codePath|nativeLibraryPath|primaryCpuAbi|lastUpdateTime' "$OUT/dumpsys_com.zjinnova.zlink.txt" \
  | sed 's/^ *//' | sort -u > "$Z/version.txt"
sed 's/^/   /' "$Z/version.txt" | grep -E 'versionName|versionCode'
sh_ 'ps -A -o PID,USER,NAME,ARGS 2>/dev/null || ps -A' | grep -iE 'zlink|z-link|carplay|fox' > "$Z/processus.txt"
sh_ 'cat /proc/net/tcp /proc/net/tcp6' > "$Z/proc_net_tcp.txt"
sh_ 'pm list packages -U' > "$Z/paquets_uid.txt"
# ports TCP en écoute sur l'autoradio, avec l'app qui les ouvre
python3 - "$Z/proc_net_tcp.txt" "$Z/paquets_uid.txt" > "$Z/ports_ecoute.txt" <<'PY'
import sys
uids = {}
for line in open(sys.argv[2]):
    if 'uid:' in line:
        name, uid = line.strip().replace('package:', '').split(' uid:')
        uids.setdefault(uid.split(',')[0], []).append(name)
seen = set()
for line in open(sys.argv[1]):
    f = line.split()
    if len(f) < 8 or f[3] != '0A' or ':' not in f[1]:
        continue
    port = int(f[1].split(':')[1], 16)
    if port in seen:
        continue
    seen.add(port)
    who = ', '.join(uids.get(f[7], [])) or 'uid ' + f[7]
    fox = '  <- Fox ZLink' if 51800 <= port <= 51899 else ''
    print('%5d  %s%s' % (port, who, fox))
PY
echo "   ports Fox ouverts : $(grep -c 'Fox ZLink' "$Z/ports_ecoute.txt")"
sh_ "find /system /vendor /product /odm /data/local -iname '*zlink*' -o -iname 'libzj*' -o -iname 'z-link*' 2>/dev/null" > "$Z/fichiers.txt"
chemin=$(grep -m1 '^codePath=' "$Z/version.txt" | sed 's/^codePath=//')
if [ -n "$chemin" ]; then
  adb pull "$chemin" "$Z/apk" >/dev/null 2>&1 && echo "   app ZLink copiée (avec ses bibliothèques)"
fi
n=0
while read -r f; do
  [ -z "$f" ] && continue
  n=$((n + 1)); [ $n -gt 20 ] && break
  adb pull "$f" "$Z/fichiers/" >/dev/null 2>&1
done < <(mkdir -p "$Z/fichiers"; grep -vE '^/data/app' "$Z/fichiers.txt")
echo
printf "L'iPhone est-il connecté en CarPlay ? Je lis alors 5 s du flux de ZLink, sans rien toucher [o/N] : "
read -r rep
if [ "$rep" = "o" ] || [ "$rep" = "O" ]; then
  BASE=15800
  for p in 51808 51818 51828 51858 51868 51878 51888; do adb forward tcp:$((BASE + p - 51800)) tcp:$p >/dev/null; done
  python3 "$(dirname "$0")/sonde_zlink.py" "$Z" "$BASE"
  for p in 51808 51818 51828 51858 51868 51878 51888; do adb forward --remove tcp:$((BASE + p - 51800)) >/dev/null 2>&1; done
fi

if [ "${2:-}" = "zlink" ]; then
  tar czf "$OUT.tar.gz" "$OUT"
  echo; echo "Terminé : $OUT.tar.gz"
  exit 0
fi

echo "5/5  Enregistrement du journal pendant que tu actionnes la voiture"
adb logcat -v threadtime -b main,system,events > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!
sleep 2

marque() { adb shell log -t TWIN_MARK "$1" >/dev/null 2>&1; }

ETAPES=(
  "Rien : contact mis, tout fermé, feux éteints (attends 10 s)"
  "Ouvre la portière conducteur (avant gauche)"
  "Referme-la"
  "Ouvre la portière passager (avant droite)"
  "Referme-la"
  "Ouvre la portière arrière gauche"
  "Referme-la"
  "Ouvre la portière arrière droite"
  "Referme-la"
  "Ouvre le coffre"
  "Referme-le"
  "Ouvre le capot (si facile, sinon s)"
  "Referme-le"
  "Feux de position"
  "Feux de croisement"
  "Feux de route (plein phare)"
  "Appel de phare"
  "Retour aux feux de croisement"
  "Antibrouillard avant"
  "Antibrouillard arrière"
  "Coupe les antibrouillards"
  "Éteins les phares"
  "Clignotant gauche (laisse 5 s)"
  "Coupe-le"
  "Clignotant droit (laisse 5 s)"
  "Coupe-le"
  "Warning (5 s)"
  "Coupe le warning"
  "Appuie sur la pédale de frein (garde 3 s)"
  "Relâche le frein"
  "Desserre le frein à main (pied sur le frein, voiture à plat)"
  "Resserre le frein à main"
  "Moteur coupé, embrayage : passe la marche arrière"
  "Repasse au point mort"
  "Démarre le moteur (laisse tourner 10 s)"
  "Donne deux petits coups d'accélérateur au point mort"
  "Tourne le volant à fond à gauche"
  "Tourne le volant à fond à droite"
  "Volant droit, coupe le moteur"
)

echo
echo "Pour chaque étape : fais l'action, puis Entrée. Tape s puis Entrée pour sauter une étape."
n=0
for e in "${ETAPES[@]}"; do
  n=$((n+1))
  printf '\n[%02d/%02d] %s\n' "$n" "${#ETAPES[@]}" "$e"
  marque "debut $n : $e"
  read -r rep
  if [ "$rep" = "s" ]; then marque "saute $n"; else marque "fin $n : $e"; fi
  sleep 1
done

echo
printf "Enregistrer aussi un trajet ? Durée en minutes (0 = non) : "
read -r minutes
if [ "${minutes:-0}" -gt 0 ] 2>/dev/null; then
  marque "trajet : $minutes min"
  echo "Enregistrement pendant $minutes min. Ne touche à rien, roule normalement."
  sleep $((minutes*60))
  marque "fin du trajet"
fi

kill $LOGPID 2>/dev/null
wait $LOGPID 2>/dev/null
sh_ settings list global > "$OUT/settings_global_apres.txt"
sh_ getprop sys.qf.backcar_state > "$OUT/marche_arriere_fin.txt"
grep -E 'TWIN_MARK|dispatchToClients|mcu|Mcu|MCU|canbus|CanBus|qf\.vehicle' "$OUT/logcat.txt" > "$OUT/logcat_voiture.txt"

tar czf "$OUT.tar.gz" "$OUT"
echo
echo "Terminé : $OUT.tar.gz"
echo "Lignes du MCU relevées : $(grep -c dispatchToClients "$OUT/logcat.txt")"
