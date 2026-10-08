#!/bin/bash
# Manual APK build for Murchie AdBlock (no Gradle, no AARs, pure Java).
set -e
export JAVA_HOME=/home/hatch/workspace/android/jdk17
SDK=/home/hatch/workspace/android/sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$PATH

PROJ=/home/hatch/workspace/adblock
SRC=$PROJ/app/src/main
BUILD=$PROJ/manual-build
rm -rf $BUILD
mkdir -p $BUILD/gen $BUILD/classes $BUILD/dex $BUILD/apk

echo "[1/6] linking..."
$BT/aapt2 compile --dir $SRC/res -o $BUILD/app-res.zip
$BT/aapt2 link -o $BUILD/apk/base.apk \
  -I $PLATFORM \
  --manifest $SRC/AndroidManifest.xml \
  -A $SRC/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java $BUILD/gen \
  $BUILD/app-res.zip

echo "[2/6] compiling java..."
find $SRC/java $BUILD/gen -name "*.java" > $BUILD/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -classpath $PLATFORM \
  -d $BUILD/classes @$BUILD/sources.txt 2>&1 | grep -v "bootstrap\|deprecat" || true
[ $(find $BUILD/classes -name "*.class" | wc -l) -gt 0 ] || { echo "JAVAC FAILED"; exit 1; }

echo "[3/6] dexing..."
$BT/d8 --lib $PLATFORM --min-api 26 \
  --output $BUILD/dex \
  $(find $BUILD/classes -name "*.class" | tr '\n' ' ')
[ -f $BUILD/dex/classes.dex ] || { echo "DEX FAILED"; exit 1; }

echo "[4/6] adding dex to apk..."
cp $BUILD/apk/base.apk $BUILD/apk/app.apk
cd $BUILD/dex && $JAVA_HOME/bin/jar uf $BUILD/apk/app.apk classes.dex && cd - > /dev/null

echo "[5/6] aligning + signing..."
$BT/zipalign -f 4 $BUILD/apk/app.apk $BUILD/apk/app-aligned.apk
KEYSTORE=$PROJ/debug.keystore
if [ ! -f $KEYSTORE ]; then
  keytool -genkeypair -keystore $KEYSTORE -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi
$BT/apksigner sign --ks $KEYSTORE --ks-pass pass:android --key-pass pass:android \
  --out $BUILD/adblock.apk $BUILD/apk/app-aligned.apk

echo "[6/6] verifying..."
$BT/apksigner verify --print-certs $BUILD/adblock.apk 2>&1 | head -3
cp $BUILD/adblock.apk ~/workspace/your_files/maple-adblock.apk
echo "BUILD OK: ~/workspace/your_files/maple-adblock.apk"
ls -la ~/workspace/your_files/maple-adblock.apk
