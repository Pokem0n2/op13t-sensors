#!/usr/bin/env bash
# Build op13t-sensors-v{version}.apk  (同 tookies/android-sys-info 管线)
# Requires: JDK 21, Android SDK (build-tools 34.0.0 + platforms;android-34), aarch64 需 box64
set -e
WS="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/34.0.0"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
HOST_ARCH=$(uname -m)
if [ "$HOST_ARCH" = "x86_64" ]; then
  X=aapt2; Y="bash $BT/d8"; Z="bash $BT/apksigner"; W=zipalign; A=aapt
else
  X="box64 $BT/aapt2"
  Y="bash $BT/d8"
  Z="java -jar $APKSIGNER_JAR"
  W="box64 $BT/zipalign"
  A="box64 $BT/aapt"
fi
APK_DIR="$WS/apk"
BUILD_DIR="$APK_DIR/build"
VERSION_TAG=$(cd "$WS" && git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0")
VERSION="${VERSION_TAG#v}"
# versionCode = major*10000 + minor*100 + patch
VC_MAJOR=$(echo "$VERSION" | cut -d. -f1)
VC_MINOR=$(echo "$VERSION" | cut -d. -f2)
VC_PATCH=$(echo "$VERSION" | cut -d. -f3)
VC_PATCH="${VC_PATCH:-0}"; VC_MINOR="${VC_MINOR:-0}"; VC_MAJOR="${VC_MAJOR:-0}"
VERSION_CODE=$(( VC_MAJOR * 10000 + VC_MINOR * 100 + VC_PATCH ))
APK_NAME="op13t-sensors-v${VERSION}"
echo "Building: $APK_NAME.apk (versionCode=$VERSION_CODE, versionName=$VERSION)"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/compiled"
# 注入版本号（aapt2 --version-name 在 box64 下不可靠）
MANIFEST_TMP="$BUILD_DIR/AndroidManifest.xml"
sed -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION\"/" \
    "$APK_DIR/AndroidManifest.xml" > "$MANIFEST_TMP"
echo "=== 1. Compile Java ==="
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD_DIR/obj" \
  "$APK_DIR"/src/com/sensors/app/*.java
echo "=== 2. DEX ==="
$Y --output "$BUILD_DIR/dex" \
  --min-api 21 \
  --lib "$ANDROID_JAR" \
  --release \
  "$BUILD_DIR/obj/com/sensors/app/"*.class
echo "=== 3. aapt2 compile ==="
$X compile -o "$BUILD_DIR/compiled/" --dir "$APK_DIR/res"
echo "=== 4. aapt2 link ==="
$X link -o "$BUILD_DIR/${APK_NAME}.unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST_TMP" \
  --auto-add-overlay \
  -A "$APK_DIR/assets" \
  "$BUILD_DIR/compiled/"*.flat
echo "=== 5. Add DEX to APK ==="
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes.dex"
(cd "$BUILD_DIR" && $A add "${APK_NAME}.unsigned.apk" "classes.dex")
echo "=== 6. zipalign ==="
$W -f 4 "$BUILD_DIR/${APK_NAME}.unsigned.apk" "$BUILD_DIR/${APK_NAME}.aligned.apk"
echo "=== 7. Sign ==="
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$HOME/.android"
  echo "Generating debug.keystore..."
  keytool -genkey -v -keystore "$KS" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android --key-pass pass:android \
    -dname "CN=Android Debug,O=Android,C=US"
fi
$Z sign --ks "$KS" \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$BUILD_DIR/${APK_NAME}.apk" \
  "$BUILD_DIR/${APK_NAME}.aligned.apk"
echo "=== 8. Verify ==="
$Z verify --verbose "$BUILD_DIR/${APK_NAME}.apk" | head -8
cp "$BUILD_DIR/${APK_NAME}.apk" "$WS/${APK_NAME}.apk"
ls -la "$WS/${APK_NAME}.apk"
echo "✅ APK built: $WS/${APK_NAME}.apk"
