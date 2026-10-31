#!/usr/bin/env bash
# Забрать с телевизора всё для разбора проблемы: журнал лаунчера, свежий logcat, состояние.
#   ./tv-log.sh 192.168.0.102   → папка ~/tvhome/logs/<ip>-<время>/
set -u
IP="${1:?укажи IP телевизора}"; TV="$IP:5555"
ADB="${ADB:-$HOME/tvhome/tools/platform-tools/adb}"
OUT="$HOME/tvhome/logs/$IP-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
"$ADB" connect "$TV" >/dev/null
sh_tv() { "$ADB" -s "$TV" shell "$@"; }
D=/sdcard/Android/data/home.lite.tv/files
sh_tv cat $D/journal.old.txt > "$OUT/journal.old.txt" 2>/dev/null
sh_tv cat $D/journal.txt > "$OUT/journal.txt"
"$ADB" -s "$TV" logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
"$ADB" -s "$TV" logcat -d -v time > "$OUT/logcat.txt"
{
  echo "модель: $(sh_tv getprop ro.product.model), Android $(sh_tv getprop ro.build.version.release)"
  sh_tv dumpsys package home.lite.tv | grep -E "versionName|versionCode|lastUpdateTime"
  sh_tv dumpsys activity activities | grep -m1 mResumedActivity
  sh_tv dumpsys power | grep -m1 mWakefulness=
  sh_tv "grep -E 'MemTotal|MemAvailable' /proc/meminfo"
  sh_tv dumpsys meminfo home.lite.tv | grep -m1 TOTAL
} > "$OUT/state.txt" 2>&1
echo "$OUT"; echo "--- ошибки в журнале:"; grep -E " E |ПАДЕНИЕ" "$OUT/journal.txt" | tail -20
