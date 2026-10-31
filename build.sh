#!/usr/bin/env bash
# Сборка без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-$(pwd)/tools/sdk}"
BT="$SDK/android-11"
JAR="$SDK/android-9/android.jar"
export JAVA_HOME="$SDK/jdk" PATH="$SDK/jdk/bin:$PATH"

rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
# Версия — минуты с 1970 года: каждая сборка больше предыдущей, тег на GitHub — v<код>
VCODE="${VCODE:-$(( $(date +%s) / 60 ))}"
VNAME="${VNAME:-$(date +%Y.%m.%d-%H%M)}"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets --min-sdk-version 21 --target-sdk-version 28 \
    --version-code "$VCODE" --version-name "$VNAME" --replace-version \
    -o build/unsigned.apk build/res.zip --java build/gen
javac -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -encoding UTF-8 \
    -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -qj ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS=debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias home \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Home" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out home.apk build/aligned.apk
ls -l home.apk
