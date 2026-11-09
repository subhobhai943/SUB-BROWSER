#!/bin/bash
# Direct SUB-BROWSER APK build (no Gradle): aapt2 + javac + d8 + apksigner
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar
A=/opt/android-build/app
B=/opt/android-build/build
rm -rf $B && mkdir -p $B/obj $B/classes $B/apks

echo "== compile resources"
$BT/aapt2 compile --dir $A/res -o $B/res.zip

echo "== link"
$BT/aapt2 link -o $B/app.unsigned.apk -I $AJ --manifest $A/AndroidManifest.xml \
  --java $B/gen -R $B/res.zip --auto-add-overlay

echo "== javac"
javac -source 11 -target 11 -classpath $AJ \
  -d $B/classes $(find $B/gen $A/src -name '*.java')

echo "== dex"
$BT/d8 --lib $AJ --release --output $B/classes $(find $B/classes -name '*.class')

echo "== package dex+assets"
cd $B/classes && zip -q ../app.unsigned.apk classes.dex
cd $A && zip -q -r $B/app.unsigned.apk assets

echo "== zipalign + sign"
$BT/zipalign -f 4 $B/app.unsigned.apk $B/app.aligned.apk
$BT/apksigner sign --ks $SDK/debug.jks --ks-pass pass:android --key-pass pass:android \
  --out $B/SUB-BROWSER-1.0.apk $B/app.aligned.apk
$BT/apksigner verify --print-certs $B/SUB-BROWSER-1.0.apk | head -3
ls -la $B/SUB-BROWSER-1.0.apk
echo "APK_BUILD_OK"
