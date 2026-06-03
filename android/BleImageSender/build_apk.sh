#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
PLATFORM_DIR="$SDK_DIR/platforms/android-35"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/35.0.0"
BUILD_TYPE="${1:-debug}"

ANDROID_JAR="$PLATFORM_DIR/android.jar"
AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

BUILD_DIR="$ROOT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
CLASS_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"

if [ "$BUILD_TYPE" != "debug" ] && [ "$BUILD_TYPE" != "release" ]; then
  echo "Usage: $0 [debug|release]" >&2
  exit 2
fi

OUT_DIR="$ROOT_DIR/app/build/outputs/apk/$BUILD_TYPE"

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

if [ "$BUILD_TYPE" = "release" ]; then
  SIGNING_PROPS="$ROOT_DIR/release-signing.properties"
  if [ -f "$SIGNING_PROPS" ]; then
    # shellcheck disable=SC1090
    . "$SIGNING_PROPS"
  fi

  KEYSTORE="${RELEASE_KEYSTORE:-$ROOT_DIR/release.keystore}"
  if [[ "$KEYSTORE" != /* ]]; then
    KEYSTORE="$ROOT_DIR/$KEYSTORE"
  fi
  KEY_ALIAS="${RELEASE_KEY_ALIAS:-momo}"
  STORE_PASS="${RELEASE_STORE_PASS:-}"

  if [ -z "$STORE_PASS" ]; then
    STORE_PASS="$(openssl rand -hex 24)"
    umask 077
    {
      echo "RELEASE_KEYSTORE=release.keystore"
      echo "RELEASE_KEY_ALIAS=$KEY_ALIAS"
      echo "RELEASE_STORE_PASS=$STORE_PASS"
      echo "RELEASE_KEY_PASS=$STORE_PASS"
    } > "$SIGNING_PROPS"
  fi
  KEY_PASS="$STORE_PASS"

  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
      -keystore "$KEYSTORE" \
      -storepass "$STORE_PASS" \
      -keypass "$KEY_PASS" \
      -alias "$KEY_ALIAS" \
      -keyalg RSA \
      -keysize 4096 \
      -validity 36500 \
      -dname "CN=Momo,O=zstar,C=CN" >/dev/null
  fi

  APK_PATH="$OUT_DIR/momo-release.apk"
else
  KEYSTORE="$ROOT_DIR/debug.keystore"
  KEY_ALIAS="androiddebugkey"
  STORE_PASS="android"
  KEY_PASS="android"

  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair \
      -keystore "$KEYSTORE" \
      -storepass "$STORE_PASS" \
      -keypass "$KEY_PASS" \
      -alias "$KEY_ALIAS" \
      -keyalg RSA \
      -keysize 2048 \
      -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  fi

  APK_PATH="$OUT_DIR/rlcd-ble-image-debug.apk"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$APK_PATH" \
  "$BUILD_DIR/aligned.apk"

"$APKSIGNER" verify --verbose "$APK_PATH"
echo "$APK_PATH"
