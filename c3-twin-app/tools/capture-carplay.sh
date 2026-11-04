#!/system/bin/sh
# Read-only diagnostic, 10 minutes. Files remain on the head unit after ADB disconnects.
OUT="$1"
[ -n "$OUT" ] || exit 2
mkdir -p "$OUT" || exit 1
echo $$ > "$OUT/pid"
echo running > "$OUT/status"
trap 'echo interrupted > "$OUT/status"; exit 0' TERM INT
{
    date
    getprop ro.product.model
    getprop ro.build.version.release
    dumpsys package com.zjinnova.zlink | grep -E 'versionName|versionCode|userId='
    pm path com.zjinnova.zlink
} > "$OUT/device.txt" 2>&1
n=0
while [ "$n" -lt 100 ]; do
    {
        echo "--- sample $n ---"
        date
        ip addr show
        ip route
        ps -A | grep -iE 'zlink|z-link|tlink|usbmux|netshare'
        cat /proc/net/tcp /proc/net/tcp6 /proc/net/unix
        dumpsys SurfaceFlinger --list | grep -iE 'zlink|carplay|tlink|vde|c3twin'
        dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'
    } >> "$OUT/state.txt" 2>&1
    {
        echo "--- sample $n ---"
        date
        logcat -d -v threadtime -t 600 | grep -iE 'zlink|z-link|carplay|tlink|iap2|usbmux|netshare|AirPlay|MFi|VideoPthread|ControlPthread|TouchPthread|ConnectivityService|WifiStateMachine|ClientModeImpl'
    } >> "$OUT/events.txt" 2>&1
    n=$((n + 1))
    echo "$n" > "$OUT/samples"
    sleep 6
done
echo complete > "$OUT/status"
