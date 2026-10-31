#!/usr/bin/env bash
# Выпустить новую версию JonsonLaunch: сборка → коммит → релиз на GitHub.
# Телевизоры с включённой автоустановкой подхватят её сами (проверка раз в 6 часов).
#   ./release/publish.sh "что изменилось"
set -euo pipefail
cd "$(dirname "$0")/.."
GH="${GH:-$HOME/.local/bin/gh}"
NOTES="${1:-Обновление}"
./build.sh >/dev/null
CODE=$("tools/sdk/android-11/aapt2" dump badging home.apk | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
NAME=$("tools/sdk/android-11/aapt2" dump badging home.apk | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
cp home.apk release/JonsonLaunch-latest.apk
cp home.apk "release/JonsonLaunch-$NAME.apk"
git add -A
git commit -qm "JonsonLaunch $NAME: $NOTES" || true
git push -q origin main
"$GH" release create "v$CODE" home.apk#JonsonLaunch.apk --title "JonsonLaunch $NAME" --notes "$NOTES"
echo "Выпущено: v$CODE ($NAME)"
