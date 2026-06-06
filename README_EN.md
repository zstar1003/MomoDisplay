<div align="center">
  <img src="assets/logo.png" width="200" alt="MomoDisplay">
</div>

<div align="center">
  <h4>
    <a href="README.md">🇨🇳 中文</a>
    <span> | </span>
    <a href="README_EN.md">🇬🇧 English</a>
  </h4>
</div>

This project is a secondary development based on the Waveshare ESP32-S3-RLCD-4.2 development board. It allows an Android app to send images or text to the board via Bluetooth and display them on the e-paper screen.

## Demo

The Android app connects to the development board and uploads an image.

<div align="center">
  <img src="assets/show.jpg" width="500" alt="MomoDisplay">
</div>

The development board displays the uploaded image.

<div align="center">
  <img src="assets/show2.jpg" width="500" alt="MomoDisplay">
</div>

The development board has three buttons: `KEY`, `POWER`, and `BOOT` from left to right.

- Short press `KEY`: Switch to the image page.
- Short press `BOOT`: Switch to the text page.
- Long press `KEY` or `BOOT`: Switch to the default clock page.

Default clock page:

<div align="center">
  <img src="assets/show3.jpg" width="500" alt="MomoDisplay">
</div>

## Hardware Requirements

- Waveshare ESP32-S3-RLCD-4.2
- USB-C data cable for flashing
- Optional 18650 lithium battery for standalone power
- Android smartphone with BLE support

The power button is controlled by the development board hardware. The firmware only reads the `KEY` and `BOOT` buttons after the board has been powered on.

## Firmware Flashing

Required:

- Arduino IDE 2.x or `arduino-cli`
- ESP32 Arduino Core
- U8g2 library

Recommended Arduino IDE configuration:

| Option | Value |
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

Compile using `arduino-cli`:

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install U8g2
arduino-cli compile \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware
```

Upload the firmware:

```bash
arduino-cli upload \
  -p /dev/tty.usbmodemXXXX \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware
```

If the board does not automatically enter download mode, unplug the USB-C cable, hold down the `BOOT` button, reconnect the USB-C cable, wait about 1 second, release `BOOT`, and then upload the firmware again.

## Building the Android App

The Android app does not use Gradle and is built directly using Android SDK command-line tools.

Requirements:

- Android SDK Platform 35
- Android Build Tools 35.0.0
- JDK with `javac` and `keytool`
- `openssl`

Build a debug APK:

```bash
cd android
./build_apk.sh debug
```

Build a signed release APK:

```bash
cd android
./build_apk.sh release
```

Output paths:

- Debug: `android/app/build/outputs/apk/debug/rlcd-ble-image-debug.apk`
- Release: `android/app/build/outputs/apk/release/momo-release.apk`

During the first release build, `release.keystore` and `release-signing.properties` will be generated automatically. These files are not tracked by Git. If you want future release APKs to upgrade the same installed app, keep these files safe and reuse them.

## Usage

1. Flash `firmware` onto the ESP32-S3-RLCD-4.2 development board.
2. Install the Android APK.
3. Power on the development board.
4. Open the **Momo** app on your Android phone and grant Bluetooth permissions.
5. Tap the connect button. The app only scans for `RLCD-BLE-IMG` and connects automatically.
6. Select an image or enter text, then upload it to the development board.

After the app connects to the development board, it automatically synchronizes the phone's time. The default clock page will refresh using the synchronized time.

## License

This project is licensed under the [Apache License 2.0](./LICENSE).
