# BandBridge Camera Module

这是 `FINAL` 里的相机板正式目录。

当前目录里同时保留了两条路线：

- `main/`
  - 之前整理过的 ESP-IDF 版本源码
- `arduino/BandBridgeCam/`
  - 这次已经在本机真实编译通过的 Arduino 版本

当前这台电脑上，真正已经编译验证过的是 Arduino 版本。

## 已落地功能

- 板型按你的 Freenove ESP32-S3 WROOM 相机板落地
- 相机引脚按你提供的引脚图配置
- `GPIO14` = 拍照按钮
- `GPIO21` = 分析按钮
- 按拍照按钮后：
  - 板子抓拍一张 JPEG
  - 缓存在板子内存里
- 按分析按钮后：
  - 如果还没有缓存图，会先抓拍
  - 然后按 BLE 协议把缓存图分包发给手机
- 保留 Wi-Fi 调试看图功能
  - 可看最近缓存图
  - 可看实时预览
  - 可在网页上点“拍照并缓存”
  - 可在网页上点“发送到手机”

## Wi-Fi 调试

默认会起一个 SoftAP：

- SSID：`BandBridgeCam`
- Password：`12345678`

连上后打开：

- [http://192.168.4.1](http://192.168.4.1)

可用页面：

- `/`
  - 调试首页
- `/status`
  - 当前状态 JSON
- `/capture`
  - 即时抓拍一张
- `/last.jpg`
  - 最近缓存图
- `/stream`
  - 实时预览
- `/action?type=capture`
  - 触发缓存拍照
- `/action?type=send`
  - 触发发送到手机

## BLE 协议

和安卓 `FINAL/mobile/android-demo` 里预留的协议一致：

- Service UUID：`7D8A1000-4B2A-4D44-9F72-5E7BC9A10000`
- Control：`7D8A1001-4B2A-4D44-9F72-5E7BC9A10000`
- Status：`7D8A1002-4B2A-4D44-9F72-5E7BC9A10000`
- Meta：`7D8A1003-4B2A-4D44-9F72-5E7BC9A10000`
- Chunk：`7D8A1004-4B2A-4D44-9F72-5E7BC9A10000`

支持命令：

- `PING`
- `CAPTURE`
- `FETCH_LAST`

## 已真实编译的草图

草图路径：

- [BandBridgeCam.ino](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/BandBridgeCam.ino>)

本机实际使用的编译参数：

- 板型：`esp32:esp32:esp32s3`
- `PSRAM=enabled`
- `FlashMode=qio`
- `FlashSize=16M`
- `PartitionScheme=app3M_fat9M_16MB`
- `USBMode=hwcdc`
- `CDCOnBoot=default`
- `UploadMode=default`
- `CPUFreq=240`

## 已生成产物

可直接拿来烧录/排查的文件：

- [BandBridgeCam.ino.bin](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.bin>)
- [BandBridgeCam.ino.merged.bin](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.merged.bin>)
- [BandBridgeCam.ino.bootloader.bin](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.bootloader.bin>)
- [BandBridgeCam.ino.partitions.bin](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.partitions.bin>)
- [BandBridgeCam.ino.elf](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.elf>)
- [BandBridgeCam.ino.map](</D:/vs studio/cheat/FINAL/camera-module/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.map>)

## 当前确认结果

- 板子端 Arduino 版本：已真实编译通过
- 安卓 `FINAL` 工程：已再次编译通过
- 安卓里“分析顺位最多 8 个、自定义 AI 最多 8 个 API 槽位”：仍然在，并且没有被这次板子工作弄坏

## 还没收尾的地方

- 安卓端“真实扫描/连接这块板子的 BLE 收图”还没接完
- 所以当前板子已经能按协议发图，但手机端这一步还需要继续落

