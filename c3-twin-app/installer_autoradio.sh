#!/bin/bash
# Install on one explicit device. See ../docs/installation.md.
set -euo pipefail
cd "$(dirname "$0")"
usage() {
  cat <<'HELP'
Usage: ./installer_autoradio.sh <IP|IP:PORT|serial|usb> [install|launcher|journal]
  install   Install APK, grant supported permissions, open C3 Twin (default).
  launcher  Save current HOME/QF settings, then make installed C3 Twin the home app.
  journal   Retrieve MCU logs into journaux/ (private local data).
USB: set ANDROID_SERIAL when more than one device is connected.
Wi-Fi port defaults to 9876 for an IPv4 address without a port.
HELP
}
[[ ${1:-} == --help || ${1:-} == -h ]] && { usage; exit 0; }
[[ $# -ge 1 && $# -le 2 ]] || { usage >&2; exit 1; }
TARGET=$1
ACTION=${2:-install}
case "$ACTION" in install|launcher|journal) ;; *) usage >&2; exit 1 ;; esac
command -v adb >/dev/null || { echo 'adb absent du PATH' >&2; exit 1; }
if [[ $TARGET == usb ]]; then
  SERIAL=${ANDROID_SERIAL:-}
  if [[ -z $SERIAL ]]; then
    SERIAL=$(adb -d get-serialno)
    [[ -n $SERIAL && $SERIAL != unknown ]] || { echo 'Choisir ANDROID_SERIAL.' >&2; exit 1; }
  fi
elif [[ $TARGET =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  SERIAL="$TARGET:9876"
  adb connect "$SERIAL"
else
  SERIAL=$TARGET
  [[ $SERIAL != *:* ]] || adb connect "$SERIAL"
fi
ADB=(adb -s "$SERIAL")
[[ $("${ADB[@]}" get-state) == device ]] || { echo 'Appareil absent, hors ligne ou non autorisé.' >&2; exit 1; }
PKG=fr.dabi.c3twin
shell_value() { "${ADB[@]}" shell "$@" | tr -d '\r'; }
warn_permission() {
  if ! "${ADB[@]}" shell "$@"; then
    echo "Non accordé : $* — voir docs/installation.md." >&2
  fi
}
case "$ACTION" in
  journal)
    OUT="journaux/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$OUT"
    for name in mcu.log mcu.old.log; do
      "${ADB[@]}" pull "/sdcard/Android/data/$PKG/files/$name" "$OUT/$name" || true
    done
    echo "Journaux locaux : $OUT (ne pas publier sans examen)."
    ;;
  install)
    [[ -f build/C3Twin.apk ]] || { echo 'Compiler avec ./build.sh avant installation.' >&2; exit 1; }
    "${ADB[@]}" install --no-incremental -r build/C3Twin.apk
    warn_permission pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
    warn_permission pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
    warn_permission appops set "$PKG" SYSTEM_ALERT_WINDOW allow
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity"
    echo 'Installation terminée. Launcher inchangé ; tester avant la commande launcher.'
    ;;
  launcher)
    [[ $(shell_value pm path "$PKG") == package:* ]] || { echo 'Installer C3 Twin avant de choisir le launcher.' >&2; exit 1; }
    OUT="backups/launcher-$(date +%Y%m%d_%H%M%S)-$$"
    mkdir -p "$OUT"
    shell_value cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-before.txt"
    QF_BEFORE=$(shell_value getprop persist.sys.qf.launcher)
    printf '%s\n' "$QF_BEFORE" > "$OUT/qf-launcher-before.txt"
    printf '%s\n' "$SERIAL" > "$OUT/device.txt"
    RESULT=$(shell_value cmd package set-home-activity "$PKG/.MainActivity")
    printf '%s\n' "$RESULT"
    [[ $RESULT == *Success* ]] || { echo "Choix HOME refusé. Sauvegarde : $OUT" >&2; exit 1; }
    if [[ -n $QF_BEFORE ]]; then
      if ! "${ADB[@]}" shell setprop persist.sys.qf.launcher "$PKG"; then
        echo "HOME Android changé, mais réglage QF refusé. Voir $OUT et le guide de restauration." >&2
        exit 1
      fi
      [[ $(shell_value getprop persist.sys.qf.launcher) == "$PKG" ]] || {
        echo "Le firmware QF n'a pas conservé le choix. Sauvegarde : $OUT" >&2; exit 1;
      }
    fi
    "${ADB[@]}" shell input keyevent KEYCODE_HOME
    echo "Réglages précédents : $OUT"
    echo 'Vérifier C3 Twin à l’écran, puis au prochain démarrage du véhicule.'
    ;;
esac
