# 墨屏蓝牙传图

这个 Android App 用 BLE 把手机图片发送到 Waveshare ESP32-S3-RLCD-4.2。

匹配的开发板固件：

`02_Example/Arduino/12_BLE_Image_Display`

## 使用步骤

1. 给开发板烧录匹配固件并上电。
2. 安装 APK：`app/build/outputs/apk/debug/rlcd-ble-image-debug.apk`
3. 打开 App，授权蓝牙权限。
4. 点“选择图片”，App 会把图片裁剪/缩放成 400x300 黑白预览。
5. 点“扫描开发板”。列表只显示匹配 `RLCD-BLE-IMG` 的开发板。
6. 点列表里的开发板，再点“发送到墨屏”。

上传成功后，开发板会把 15000 字节的屏幕图像保存到 FATFS：

`/last_image.bin`

下次开发板上电会自动读取并显示最后一次上传的图片。

## BLE 协议

- 设备名：`RLCD-BLE-IMG`
- Service：`7f6b0001-5f02-4fd8-9f23-6f8e4c59a001`
- Control write：`7f6b0002-5f02-4fd8-9f23-6f8e4c59a001`
- Data write：`7f6b0003-5f02-4fd8-9f23-6f8e4c59a001`
- Status notify/read：`7f6b0004-5f02-4fd8-9f23-6f8e4c59a001`

图片格式是 XBM 兼容 1-bit 数据：从左到右，低位在前，`1` 表示屏幕浅色像素，`0` 表示屏幕深色像素。
