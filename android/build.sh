#!/bin/bash
# SUB-BROWSER (Gecko engine edition) build: GeckoView 159 omni AAR, no Gradle.
# Pattern: aapt2 compile/link (library res via -R + --extra-packages so library R classes exist)
#          + javac + d8 over app classes AND engine/dep jars + zip lib/<abi>/*.so + apksigner.
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar
G=/opt/gecko-build
A=$G/app
B=$G/build
V=159.0.20261102144711
UA="Mozilla/5.0 (X11; Linux x86_64) build-agent"

fetch(){ [ -s "$2" ] || { echo "fetch $1"; curl -sfL -A "$UA" -o "$2" "$1"; }; }
mkdir -p $G/deps $G/tools
fetch https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar $G/tools/r8.jar

echo "== fetch artifacts"
fetch https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni-arm64-v8a/$V/geckoview-omni-arm64-v8a-$V.aar $G/geckoview-arm64-v8a.aar
fetch https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni-x86_64/$V/geckoview-omni-x86_64-$V.aar $G/geckoview-x86_64.aar
fetch https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.jar $G/deps/kotlin-stdlib.jar
# snakeyaml: optional GeckoView dep needed by DebugConfig when the app is debuggable
fetch https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar $G/deps/snakeyaml.jar
fetch https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar $G/deps/annotations-13.0.jar
fetch https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.11.0/annotation-jvm-1.11.0.jar $G/deps/androidx-annotation-jvm.jar
fetch https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.6.0/collection-jvm-1.6.0.jar $G/deps/androidx-collection-jvm.jar

# library AARs -> unpacked dirs; classes.jar added to d8, res compiled, R generated via --extra-packages
unzip_aar(){ # $1=artifact-name $2=url
  fetch "$2" $G/deps/$1.aar
  rm -rf $G/aardeps/$1 && mkdir -p $G/aardeps/$1
  (cd $G/aardeps/$1 && unzip -oq $G/deps/$1.aar)
}
unzip_aar androidx-annotation-experimental https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.6.0/annotation-experimental-1.6.0.aar
unzip_aar androidx-core https://dl.google.com/dl/android/maven2/androidx/core/core/1.19.1/core-1.19.1.aar
unzip_aar androidx-lifecycle-process https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.6.2/lifecycle-process-2.6.2.aar
unzip_aar androidx-lifecycle-runtime https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.6.2/lifecycle-runtime-2.6.2.aar

unzip_aar androidx-arch-core-runtime https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.aar
fetch https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.6.2/lifecycle-common-2.6.2.jar $G/deps/androidx-lifecycle-common.jar
fetch https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.jar $G/deps/androidx-arch-core-common.jar
fetch https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.jar $G/deps/androidx-concurrent-futures.jar
unzip_aar androidx-startup https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1.aar
for m in common datasource decoder exoplayer exoplayer-hls container extractor database; do
  unzip_aar media3-$m https://dl.google.com/dl/android/maven2/androidx/media3/media3-$m/1.11.1/media3-$m-1.11.1.aar
done
# WebAuthn (play-services-fido) + its transitive deps, and guava/coroutines referenced by them
unzip_aar gms-fido https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-fido/21.3.1/play-services-fido-21.3.1.aar
unzip_aar gms-tasks https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-tasks/18.2.1/play-services-tasks-18.2.1.aar
unzip_aar gms-basement https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-basement/18.5.0/play-services-basement-18.5.0.aar
unzip_aar gms-base https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-base/18.5.0/play-services-base-18.5.0.aar
# fragment/activity/savedstate/viewmodel referenced by play-services-base + androidx-startup chains
for p in "androidx/fragment/fragment/1.5.7/fragment-1.5.7.aar|androidx-fragment" \
         "androidx/activity/activity/1.7.2/activity-1.7.2.aar|androidx-activity" \
         "androidx/lifecycle/lifecycle-viewmodel/2.6.2/lifecycle-viewmodel-2.6.2.aar|androidx-lifecycle-viewmodel" \
         "androidx/lifecycle/lifecycle-livedata-core/2.6.2/lifecycle-livedata-core-2.6.2.aar|androidx-lifecycle-livedata-core" \
         "androidx/lifecycle/lifecycle-viewmodel-savedstate/2.6.2/lifecycle-viewmodel-savedstate-2.6.2.aar|androidx-lifecycle-viewmodel-savedstate" \
         "androidx/savedstate/savedstate/1.2.1/savedstate-1.2.1.aar|androidx-savedstate"; do
  u=${p%%|*}; n=${p##*|}; unzip_aar $n https://dl.google.com/dl/android/maven2/$u
done
for p in "androidx/loader/loader/1.0.0/loader-1.0.0.aar|androidx-loader" \
         "androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.aar|androidx-viewpager" \
         "androidx/customview/customview/1.1.0/customview-1.1.0.aar|androidx-customview"; do
  u=${p%%|*}; n=${p##*|}; unzip_aar $n https://dl.google.com/dl/android/maven2/$u
done
fetch https://repo1.maven.org/maven2/com/google/guava/guava/33.4.8-android/guava-33.4.8-android.jar $G/deps/guava.jar
fetch https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar $G/deps/failureaccess.jar
fetch https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar $G/deps/kotlinx-coroutines-core.jar
fetch https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.jar $G/deps/kotlinx-coroutines-android.jar
unzip_aar androidx-versionedparcelable https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.aar
for ABI in arm64-v8a x86_64; do
  rm -rf $G/aar-$ABI && mkdir -p $G/aar-$ABI
  (cd $G/aar-$ABI && unzip -oq $G/geckoview-$ABI.aar)
done

rm -rf $B && mkdir -p $B/classes

echo "== compile resources (app + geckoview + libraries)"
$BT/aapt2 compile --dir $A/res -o $B/res-app.zip
$BT/aapt2 compile --dir $G/aar-x86_64/res -o $B/res-geckoview.zip
for d in $G/aardeps/*/res; do
  [ -d "$d" ] && $BT/aapt2 compile --dir "$d" -o $B/res-$(basename $(dirname $d)).zip || true
done

echo "== link"
LINK_R=""; EXTRA=""
for z in $B/res-*.zip; do LINK_R="$LINK_R -R $z"; done
# generate R classes for library packages referenced by their compiled classes.jar
for p in org.mozilla.geckoview androidx.core androidx.versionedparcelable \
         androidx.lifecycle androidx.arch.core.runtime androidx.annotation.experimental; do
  EXTRA="$EXTRA --extra-packages $p"
done
$BT/aapt2 link -o $B/app.unsigned.apk -I $AJ --manifest $A/AndroidManifest.xml \
  --java $B/gen $LINK_R $EXTRA --auto-add-overlay

echo "== gather engine + dep jars"
JARS="$G/aar-x86_64/classes.jar"
JARS="$JARS $G/deps/kotlin-stdlib.jar $G/deps/annotations-13.0.jar $G/deps/snakeyaml.jar"
JARS="$JARS $G/deps/androidx-annotation-jvm.jar $G/deps/androidx-collection-jvm.jar"
JARS="$JARS $G/deps/androidx-lifecycle-common.jar $G/deps/androidx-arch-core-common.jar $G/deps/androidx-concurrent-futures.jar"
JARS="$JARS $G/deps/guava.jar $G/deps/failureaccess.jar $G/deps/kotlinx-coroutines-core.jar $G/deps/kotlinx-coroutines-android.jar"
for c in $G/aardeps/*/classes.jar; do [ -f "$c" ] && JARS="$JARS $c"; done
CPJ="$AJ"; D8CP=""; for j in $JARS; do CPJ="$CPJ:$j"; D8CP="$D8CP --classpath $j"; done

echo "== javac"
javac -source 11 -target 11 -classpath "$CPJ" \
  -d $B/classes $(find $B/gen $A/src -name '*.java')

echo "== d8 (R8 8.5.35; build-tools-34 d8 NPEs on Kotlin 2.x metadata)"
(cd $B/classes && jar cf $B/app.jar .)
rm -rf $B/dexout && mkdir -p $B/dexout
java -cp $G/tools/r8.jar com.android.tools.r8.D8 \
  --lib $AJ --release --min-api 26 --output $B/dexout \
  $D8CP \
  $B/app.jar $JARS 2>$B/d8.err || { head -40 $B/d8.err; exit 1; }
grep -iE 'error|missing' $B/d8.err | grep -v 'WARNING' | head -10 || true
wc -c $B/d8.err

echo "== package dex + assets + native libs"
cd $B/dexout && zip -q ../app.unsigned.apk classes*.dex
cd $A && zip -q -r $B/app.unsigned.apk assets
# Gecko engine omni.ja (from AAR assets) is mandatory at runtime
cd $G/aar-x86_64 && zip -q -0 $B/app.unsigned.apk assets/omni.ja
for ABI in arm64-v8a x86_64; do
  rm -rf $B/lib && mkdir -p $B/lib/$ABI && cp $G/aar-$ABI/jni/$ABI/*.so $B/lib/$ABI/
  cd $B && zip -q -r app.unsigned.apk lib/$ABI
done

echo "== zipalign + sign"
$BT/zipalign -f 4 $B/app.unsigned.apk $B/app.aligned.apk
$BT/apksigner sign --ks $SDK/debug.jks --ks-pass pass:android --key-pass pass:android \
  --out $B/SUB-BROWSER-2.0.apk $B/app.aligned.apk
$BT/apksigner verify $B/SUB-BROWSER-2.0.apk && echo SIGN_OK
ls -la $B/SUB-BROWSER-2.0.apk
echo "APK_BUILD_OK"
