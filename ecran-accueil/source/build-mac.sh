#!/bin/bash
# Compile ZenHome.apk sur macOS avec le SDK Android d'Android Studio (build-tools + une plateforme) et un JDK 17.
#   bash build-mac.sh        (ANDROID_HOME par défaut : ~/Library/Android/sdk)
set -e
cd "$(dirname "$0")"
SDK_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK_HOME"/build-tools/* | sort -V | head -n 1)"
JAR="$(ls -d "$SDK_HOME"/platforms/android-*/android.jar | sort -V | head -n 1)"
rm -rf out && mkdir -p out/classes out/dex
javac --release 8 -nowarn -cp "$JAR" -d out/classes $(find src -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$JAR" -F out/unsigned.apk
(cd out/dex && "$BT/aapt" add ../unsigned.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
[ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass changeit -keypass changeit -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ZenHome" >/dev/null
"$BT/apksigner" sign --ks key.jks --ks-pass pass:changeit --out out/ZenHome.apk out/aligned.apk
echo "OK : out/ZenHome.apk"
