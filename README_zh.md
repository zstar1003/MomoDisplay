# 墨墨

[English README](./README.md)

墨墨是一个基于 BLE 的墨水屏图片和文字上传项目，面向 Waveshare ESP32-S3-RLCD-4.2 开发板。它可以让手机在不配置 Wi-Fi 的情况下，直接通过蓝牙把图片或文字发送到开发板并显示在反射式 LCD 屏幕上。

这个仓库只保留项目相关代码：

- `firmware/MomoDisplay/`：ESP32-S3-RLCD-4.2 的 Arduino 固件。
- `android/BleImageSender/`：原生 Android App 源码和 APK 打包脚本。

## 功能

- 本地 BLE 连接，不需要把 Wi-Fi 密码烧进固件。
- Android App 名称为 `墨墨`。
- 上传 400 x 300 黑白图片到屏幕。
- 上传居中文字到屏幕。
- 使用 FFat 持久保存最后一次上传的图片、文字和默认页面。
- 默认页显示日期、时间、电量图标和 `power by zstar`。
- App 连接后自动把手机时间同步到开发板。
- 开发板按键：
  - 短按 `KEY`：切换到图片页。
  - 短按 `BOOT`：切换到文字页。
  - 长按 `KEY` 或 `BOOT`：切换到默认时钟页。

## 硬件

- Waveshare ESP32-S3-RLCD-4.2
- 用于烧录的 USB-C 数据线
- 可选 18650 锂电池，用于脱离电脑供电
- 支持 BLE 的 Android 手机

电源键由开发板硬件控制。固件只在开发板开机后读取 `KEY` 和 `BOOT` 按键。

## 固件烧录

需要安装：

- Arduino IDE 2.x 或 `arduino-cli`
- ESP32 Arduino core
- U8g2 库

Arduino IDE 推荐配置：

| 配置项 | 值 |
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

使用 `arduino-cli` 编译：

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install U8g2
arduino-cli compile \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware/MomoDisplay
```

上传固件：

```bash
arduino-cli upload \
  -p /dev/tty.usbmodemXXXX \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,UploadMode=default,FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio,CPUFreq=240,UploadSpeed=921600' \
  firmware/MomoDisplay
```

如果开发板没有自动进入下载模式，先拔掉 USB-C，按住 `BOOT`，重新插入 USB-C，等待约 1 秒后松开 `BOOT`，再重新上传。

## Android 打包

Android App 没有使用 Gradle，直接通过 Android SDK 命令行工具构建。

需要：

- Android SDK Platform 35
- Android Build Tools 35.0.0
- 带有 `javac` 和 `keytool` 的 JDK
- `openssl`

构建 debug APK：

```bash
cd android/BleImageSender
./build_apk.sh debug
```

构建签名 release APK：

```bash
cd android/BleImageSender
./build_apk.sh release
```

输出路径：

- Debug：`android/BleImageSender/app/build/outputs/apk/debug/rlcd-ble-image-debug.apk`
- Release：`android/BleImageSender/app/build/outputs/apk/release/momo-release.apk`

第一次构建 release 时会生成 `release.keystore` 和 `release-signing.properties`，它们不会被 Git 跟踪。后续如果希望 release APK 能覆盖升级同一个已安装 App，需要保留这两个文件。

## 使用方法

1. 将 `firmware/MomoDisplay` 烧录到 ESP32-S3-RLCD-4.2 开发板。
2. 安装 Android APK。
3. 给开发板上电。
4. 打开手机上的 `墨墨`，允许蓝牙权限。
5. 点击连接按钮。App 只扫描 `RLCD-BLE-IMG` 并自动连接。
6. 选择图片或输入文字，然后上传到开发板。

App 连接开发板后会自动同步手机时间，默认时钟页会使用同步后的时间刷新。

## BLE 协议

固件广播信息：

- 设备名：`RLCD-BLE-IMG`
- Service UUID：`7f6b0001-5f02-4fd8-9f23-6f8e4c59a001`
- Control characteristic：`7f6b0002-5f02-4fd8-9f23-6f8e4c59a001`
- Data characteristic：`7f6b0003-5f02-4fd8-9f23-6f8e4c59a001`
- Status characteristic：`7f6b0004-5f02-4fd8-9f23-6f8e4c59a001`

图片数据是 400 x 300 的 1-bit XBM 兼容屏幕数据。

## 仓库说明

这个仓库已经从 Waveshare 原始资源包中清理出来。无关示例、Factory 固件、LVGL 演示、ESP-IDF 示例和内置第三方库都已移除，只保留当前项目需要的固件和 Android App。
