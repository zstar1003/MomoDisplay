#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
PLATFORM_DIR="$SDK_DIR/platforms/android-35"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/35.0.0"

ANDROID_JAR="$PLATFORM_DIR/android.jar"
AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

BUILD_DIR="$ROOT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
CLASS_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
OUT_DIR="$ROOT_DIR/app/build/outputs/apk/debug"

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$CLASS_DIR" "$DEX_DIR" "$OUT_DIR"

"$AAPT2" compile --dir "$ROOT_DIR/app/src/main/res" -o "$BUILD_DIR/resources.zip"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$GEN_DIR" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  -o "$BUILD_DIR/unsigned-res.apk" \
  "$BUILD_DIR/resources.zip"

find "$ROOT_DIR/app/src/main/java" "$GEN_DIR" -name '*.java' > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$CLASS_DIR" @"$BUILD_DIR/sources.txt"

"$D8" --lib "$ANDROID_JAR" --output "$DEX_DIR" $(find "$CLASS_DIR" -name '*.class')
cp "$BUILD_DIR/unsigned-res.apk" "$BUILD_DIR/unsigned.apk"
(cd "$DEX_DIR" && zip -q -r "$BUILD_DIR/unsigned.apk" classes.dex)

"$ZIPALIGN" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

KEYSTORE="$ROOT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUT_DIR/rlcd-ble-image-debug.apk" \
  "$BUILD_DIR/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_DIR/rlcd-ble-image-debug.apk"
echo "$OUT_DIR/rlcd-ble-image-debug.apk"
