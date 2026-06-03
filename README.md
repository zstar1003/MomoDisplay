# Momo

[中文说明](./README_zh.md)

Momo is a BLE image and text sender for the Waveshare ESP32-S3-RLCD-4.2 board. It turns the board into a small reflective display that can be updated from an Android phone without Wi-Fi provisioning.

The repository contains only the project-specific code:

- `firmware/MomoDisplay/`: Arduino firmware for the ESP32-S3-RLCD-4.2 board.
- `android/BleImageSender/`: native Android app source and shell-based APK build script.

## Features

- BLE-only local connection, no Wi-Fi password burned into firmware.
- Android app named `墨墨`.
- Upload a 400 x 300 black-and-white image to the RLCD panel.
- Upload centered text to the panel.
- Persist the last uploaded image, text, and selected page in FFat flash storage.
- Default dashboard page with date, time, battery icon, and `power by zstar`.
- BLE clock sync from the phone after connection.
- Hardware buttons:
  - Short press `KEY`: show image page.
  - Short press `BOOT`: show text page.
  - Long press `KEY` or `BOOT`: show default dashboard page.

## Hardware

- Waveshare ESP32-S3-RLCD-4.2
- USB-C cable for flashing
- Optional 18650 lithium battery for standalone use
- Android phone with BLE support

Board power keys are handled by the board hardware. The firmware only reads `KEY` and `BOOT` after the board has powered on.

## Firmware Setup

Install the Arduino toolchain:

- Arduino IDE 2.x or `arduino-cli`
- ESP32 Arduino core
- U8g2 library

Arduino IDE settings for this board:

| Setting | Value |
| --- | --- |
| Board | ESP32S3 Dev Module |
| USB CDC On Boot | Enabled |
| USB Mode | Hardware CDC and JTAG |
| Flash Size | 16MB |
| Partition Scheme | 3M app / 9M FATFS |
| PSRAM | OPI PSRAM |
| Flash Mode | QIO |
| CPU Frequency | 240MHz |
| Upload Speed | 921600 |

Build with `arduino-cli`:

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install U8g2
arduino-cli compile \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware/MomoDisplay
```

Upload:

```bash
arduino-cli upload \
  -p /dev/tty.usbmodemXXXX \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware/MomoDisplay
```

If the board does not enter upload mode automatically, unplug USB-C, hold `BOOT`, plug USB-C back in, wait about one second, then release `BOOT` and upload again.

## Android Build

The Android app is intentionally simple and does not require Gradle. It is built with Android SDK command-line tools.

Requirements:

- Android SDK Platform 35
- Android Build Tools 35.0.0
- JDK with `javac` and `keytool`
- `openssl`

Build debug APK:

```bash
cd android/BleImageSender
./build_apk.sh debug
```

Build signed release APK:

```bash
cd android/BleImageSender
./build_apk.sh release
```

Output paths:

- Debug: `android/BleImageSender/app/build/outputs/apk/debug/rlcd-ble-image-debug.apk`
- Release: `android/BleImageSender/app/build/outputs/apk/release/momo-release.apk`

The release build creates `release.keystore` and `release-signing.properties` on first run. They are ignored by Git. Keep them if you want future release APKs to upgrade the same installed app.

## Usage

1. Flash `firmware/MomoDisplay` to the ESP32-S3-RLCD-4.2 board.
2. Install the Android APK.
3. Power on the board.
4. Open `墨墨` on the phone and allow Bluetooth permissions.
5. Tap the connection button. The app scans only for `RLCD-BLE-IMG` and connects automatically.
6. Select an image or enter text, then upload it to the board.

After connection, the app sends the phone time to the board. The default dashboard page updates from that synchronized clock.

## BLE Protocol

The firmware advertises:

- Device name: `RLCD-BLE-IMG`
- Service UUID: `7f6b0001-5f02-4fd8-9f23-6f8e4c59a001`
- Control characteristic: `7f6b0002-5f02-4fd8-9f23-6f8e4c59a001`
- Data characteristic: `7f6b0003-5f02-4fd8-9f23-6f8e4c59a001`
- Status characteristic: `7f6b0004-5f02-4fd8-9f23-6f8e4c59a001`

Image data is sent as 1-bit XBM-compatible screen data for a 400 x 300 frame.

## Repository Notes

This repo was trimmed from the original Waveshare resource package. Unrelated examples, factory firmware binaries, LVGL demos, ESP-IDF samples, and vendored third-party libraries were removed so the project stays focused and easy to build.
