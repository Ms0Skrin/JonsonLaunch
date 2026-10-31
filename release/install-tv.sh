#!/usr/bin/env bash
# Установка лаунчера JonsonLaunch на любой Android TV / Google TV по сети.
#   ./install-tv.sh 192.168.1.123          — поставить и сделать главным экраном
#   ./install-tv.sh 192.168.1.123 restore  — вернуть родной лаунчер
# На ТВ нужно включить «Отладку по сети» (Настройки → Об устройстве → 7 раз по сборке → Для разработчиков)
# и при первом подключении нажать «Разрешить» на экране телевизора.
set -u
IP="${1:?укажи IP телевизора}"; TV="$IP:5555"; MODE="${2:-install}"
ADB="${ADB:-$HOME/tvhome/tools/platform-tools/adb}"
APK="$(dirname "$0")/JonsonLaunch-latest.apk"
GTV=(com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith)

"$ADB" connect "$TV" >/dev/null
for i in $(seq 20); do "$ADB" -s "$TV" get-state 2>/dev/null | grep -q device && break
  [ $i = 1 ] && echo "Нажми «Разрешить» на телевизоре…"; sleep 3; done
"$ADB" -s "$TV" get-state | grep -q device || { echo "ТВ не подключился"; exit 1; }
sh_tv() { "$ADB" -s "$TV" shell "$@"; }

if [ "$MODE" = restore ]; then
  for p in "${GTV[@]}"; do sh_tv pm enable "$p" 2>/dev/null; done
  sh_tv pm disable-user --user 0 home.lite.tv
  sh_tv input keyevent KEYCODE_HOME; echo "Родной лаунчер возвращён"; exit 0
fi

echo "Модель: $(sh_tv getprop ro.product.model), Android $(sh_tv getprop ro.build.version.release)"
"$ADB" -s "$TV" install -r "$APK" | tail -1
sh_tv pm enable home.lite.tv >/dev/null 2>&1
sh_tv cmd package set-home-activity home.lite.tv/.MainActivity
# Google TV перехватывает «Домой» своим лаунчером и экраном восстановления — их отключаем
for p in "${GTV[@]}"; do sh_tv pm list packages -e | grep -q "package:$p$" && sh_tv pm disable-user --user 0 "$p"; done
sh_tv input keyevent KEYCODE_HOME; sleep 3
sh_tv dumpsys activity activities | grep -m1 mResumedActivity | grep -q home.lite.tv \
  && echo "Готово: JonsonLaunch — главный экран" || echo "Проверь экран ТВ: JonsonLaunch не стал главным"
