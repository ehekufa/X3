#!/usr/bin/env bash
# Локальная сборка X3 Recorder без Gradle (aapt + javac + d8),
# в том же стиле, что и CI (.github/workflows/build-recorder.yml).
#
# Требования: $ANDROID_HOME с platforms;android-34 и build-tools;34.0.0,
#             JAVA_HOME (JDK 11+), zip.
# Результат: X3Recorder-unsigned.apk рядом со скриптом.
set -euo pipefail

cd "$(dirname "$0")"

BT="$ANDROID_HOME/build-tools/34.0.0"
SDK_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

rm -rf build out
mkdir -p build/classes build/dex out

VERSION_CODE="$(sed -n 's/.*android:versionCode="\([0-9]\+\).*/\1/p' AndroidManifest.xml)"
VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]\+\)".*/\1/p' AndroidManifest.xml)"

echo "== aapt: resources (R.java не нужен — id берём по имени, см. Res.java)"
"$BT/aapt" package \
  -M AndroidManifest.xml \
  -S res \
  -I "$SDK_JAR" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -F out/unsigned.apk

echo "== javac"
"$JAVA_HOME/bin/javac" \
  -source 11 -target 11 \
  -bootclasspath "$SDK_JAR" \
  -classpath "$SDK_JAR" \
  -encoding UTF-8 \
  -d build/classes \
  $(find java -name '*.java')

echo "== d8"
"$BT/d8" --release --lib "$SDK_JAR" --min-api 24 \
  --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -X ../../out/unsigned.apk classes.dex)

echo "== zipalign"
"$BT/zipalign" -f 4 out/unsigned.apk X3Recorder-unsigned.apk

echo "Готово: $(pwd)/X3Recorder-unsigned.apk"
echo "Подпиши, например: apksigner sign --ks <твой.ks> X3Recorder-unsigned.apk"
