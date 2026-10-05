#!/usr/bin/env bash
# Builds Ghost Radar without Gradle: javac -> d8 -> aapt2 -> apksigner.
# Works in Termux (pkg install aapt aapt2 d8 apksigner openjdk-21) and on CI
# runners with an Android SDK (ANDROID_HOME set).
set -euo pipefail
cd "$(dirname "$0")"

API=34
OUT=build
APK=${APK:-ghost-radar.apk}
KS=${KEYSTORE:-debug.keystore}
KS_PASS=${KEYSTORE_PASS:-android}

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
    BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
    export PATH="$BT:$PATH"
    SDK="$ANDROID_HOME/platforms/android-$API/android.jar"
else
    SDK=sdk/android.jar
fi
if [ ! -f "$SDK" ]; then
    SDK=sdk/android.jar
    mkdir -p sdk
    curl -sSfL -o sdk/p.zip "https://dl.google.com/android/repository/platform-$API-ext7_r03.zip"
    unzip -j -o -q sdk/p.zip '*/android.jar' -d sdk && rm sdk/p.zip
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

javac --release 8 -classpath "$SDK" -nowarn -Xlint:-options \
    -d "$OUT/classes" $(find src -name '*.java')
d8 --min-api 26 --lib "$SDK" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
aapt2 link -o "$OUT/unsigned.apk" -I "$SDK" --manifest AndroidManifest.xml \
    --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-dev}" --replace-version
(cd "$OUT/dex" && aapt add ../unsigned.apk classes.dex >/dev/null)

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Ghost Radar" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out "$APK" "$OUT/unsigned.apk"
apksigner verify "$APK"
echo "Built $(pwd)/$APK"
