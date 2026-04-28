# 公开拍摄辅助系统 说明书

## 1. 文档目的

本文件用于完整说明当前项目的：

- 总体目标
- 三端架构
- 功能清单
- 目录结构
- 模块职责
- 关键方法
- 连接方式
- 操作流程
- 安装与烧录步骤
- 调试命令
- 当前默认参数与约定
- 使用注意事项

本文件描述的是当前公开仓库中已经落地的版本，主要覆盖以下三个正式工程：

- 安卓主控端  
  `mobile/android-demo`
- 手环显示端  
  `band/nb666`
- 相机板固件端  
  `board/esp32-s3-cam-ble`

另有一个仅用于实时预览的独立板子固件工程：

- `preview/esp32-s3-cam-live-preview（如需单独加入仓库请另行复制）`

---

## 2. 项目总体目标

本项目目标是构建一套“公开拍摄 + 手机中转 + 手环显示”的辅助系统。

标准主链路如下：

1. 相机板接收控制指令
2. 相机板拍照
3. 相机板将图片发送给安卓手机
4. 安卓手机执行 OCR 或直接调用图像 AI
5. 安卓手机得到分析结果
6. 安卓手机把实时状态和结果同步到手环
7. 手环显示日志、结果和历史

当前项目原则：

- 手机永远是主控
- 手环只做显示和轻交互
- 相机板只做拍照、缓存、传输和少量控制逻辑
- AI 与 OCR 主要放在手机端

---

## 3. 当前三端职责划分

### 3.1 安卓手机端职责

安卓 App 是整个系统的大脑，负责：

- 维持前台保活
- 管理手环连接
- 管理相机板连接
- 处理 Wi-Fi 主链和 BLE 备用链
- 接收板子图片并加入图片队列
- 管理本地图片队列和板子图片队列
- 调用 OCR / AI 分析
- 管理分析顺位与 API 槽位
- 把实时状态推送到手环
- 把最终结果推送到手环
- 保存历史记录和调试日志

### 3.2 手环端职责

手环当前是小米 Band 10 / Vela 应用，负责：

- 显示下半块实时日志
- 显示分析成功后的最终结果
- 显示历史列表
- 点标题进入详情查看完整内容
- 本地存储历史
- 震动提示
- 下半块轻交互发控制指令

### 3.3 相机板端职责

相机板当前是 ESP32-S3 + OV5640 自动对焦路线，负责：

- 接收拍照/发送/分析/定时拍照指令
- 拍照
- 自动对焦
- Wi-Fi 主链传图
- BLE 备用传图
- 定时拍照缓存到 SD 卡
- 统一上传十张图片
- 摄像头按需开关，降低发热

---

## 4. 目录结构

### 4.1 安卓端

根目录：

`mobile/android-demo`

关键文件：

- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\MainActivity.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\BridgeApplication.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\ui\DemoViewModel.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\bridge\BandBridge.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\camera\CameraBridge.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\camera\CameraBleProtocol.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\ai\AiAnalyzer.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\ocr\OcrEngine.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\image\ImageRepository.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\storage\AppSettingsStore.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\model\Messages.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\service\KeepAliveService.kt`
- `mobile/android-demo\app\src\main\java\com\demo\bandbridge\receiver\BootReceiver.kt`

### 4.2 手环端

根目录：

`band/nb666`

关键文件：

- `band/nb666\src\manifest.json`
- `band/nb666\src\app.ux`
- `band/nb666\src\utils\link.js`
- `band/nb666\src\pages\text\index\index.ux`
- `band/nb666\src\pages\detail\index\index.ux`

### 4.3 相机板端

根目录：

`board/esp32-s3-cam-ble`

关键文件：

- `board/esp32-s3-cam-ble\arduino\BandBridgeCam\BandBridgeCam.ino`
- `board/esp32-s3-cam-ble\arduino\BandBridgeCam\ESP32_OV5640_AF.h`
- `board/esp32-s3-cam-ble\arduino\BandBridgeCam\ESP32_OV5640_AF.cpp`
- `board/esp32-s3-cam-ble\arduino\BandBridgeCam\ESP32_OV5640_cfg.h`

### 4.4 预览专用板子固件

根目录：

`preview/esp32-s3-cam-live-preview（如需单独加入仓库请另行复制）`

用途：

- 仅用于连接板子热点后实时看画面
- 不参与主项目的拍照/分析/手环链路

---

## 5. 安卓端功能总表

### 5.1 页面结构

安卓 App 当前保留四个页面：

- 首页
- 历史
- 设置
- 调试

### 5.2 首页功能

- 显示相机连接状态
- 显示手环连接状态
- 显示后台保活状态
- 手动拍照
- 手动上传分析
- 测试 AI
- 自定义文本发手环
- 查看最新拍照预览
- 查看当前图片队列

### 5.3 历史页面

- 查看已发送或已分析的历史记录

### 5.4 设置页面

- 编辑 Prompt
- 编辑延时
- 编辑相机分辨率
- 编辑 JPEG 压缩值
- 管理多个自定义 AI API 槽位
- 管理最多 8 个分析顺位
- 设置顺位对应的 API 槽位

### 5.5 调试页面

- 查看来自相机、手环、AI、系统的日志

---

## 6. 安卓端 AI 逻辑

当前安卓端以“自定义 AI”为主。

### 6.1 当前支持的 AI 组织方式

- 只保留自定义接口作为核心配置方式
- 自定义 API 槽位可配置多个
- 分析时按“顺位”选择 API 槽位

### 6.2 分析顺位规则

- 最多 8 个顺位
- 每个顺位可启用或关闭
- 每个顺位绑定一个 API 槽位
- 分析时按顺位依次尝试
- 每个 API 在单次分析里只尝试一次
- 当前顺位失败则切到下一顺位

### 6.3 手环同步规则

开始分析时，手环下半块显示：

- `顺位 N`
- `正在使用顺位 N（API M）分析。`

分析成功后：

- 下半块改为显示最终结果

后续再有新操作时：

- 下半块重新切回显示实时日志

### 6.4 队列清空规则

图片队列不会在分析成功后立刻清空。  
必须同时满足：

1. 上一次分析成功
2. 后续再次触发拍照

这时才清空旧队列，再装入新图。

---

## 7. 安卓端主要模块与关键方法

### 7.1 `MainActivity.kt`

职责：

- 负责 Compose UI 入口
- 负责四个页面布局
- 展示按钮、卡片、预览图、历史、设置和日志

主要 UI 方法：

- `BandBridgeApp`
- `HomeScreen`
- `ManualPushSection`
- `HistoryScreen`
- `SettingsScreen`
- `PriorityRouteCard`
- `DebugScreen`
- `SelectedImageCard`
- `PreviewImageCard`
- `ConnectionCard`
- `GestureCard`

### 7.2 `DemoViewModel.kt`

职责：

- 负责安卓端主流程控制
- 统一调度板子、手环、AI、OCR、图片队列、日志

主要公开方法：

- `refreshAllStates()`
- `onLocalImagesSelected(uris)`
- `refreshCameraConnection()`
- `refreshBandConnection()`
- `refreshKeepAliveState()`
- `updateCustomBandText(value)`
- `pushCustomTextToBand()`
- `clickCapture()`
- `clickUpload()`
- `clickTest()`
- `addCustomEndpointSlot()`
- `removeCurrentEndpointSlot()`
- `updateEditSlot(slot)`
- `updatePrompt(value)`
- `updateDelay(value)`
- `cameraFrameSizeOptions()`
- `currentCameraFrameSize()`
- `currentCameraJpegQuality()`
- `updateCameraFrameSize(value)`
- `updateCameraJpegQuality(value)`
- `applyCameraConfig()`
- `cameraConfigSummary()`
- `currentEditableSlots()`
- `currentEditingApiKey()`
- `currentEditingModel()`
- `currentEditingBaseUrl()`
- `updateCurrentEditingApiKey(value)`
- `updateCurrentEditingModel(value)`
- `updateCurrentEditingBaseUrl(value)`
- `priorityRoute(index)`
- `priorityRouteSlot(index)`
- `priorityRouteEnabled(index)`
- `priorityRouteSlots()`
- `updatePriorityRouteEnabled(index, enabled)`
- `updatePriorityRouteSlot(index, slot)`
- `clearPriorityRoute(index)`
- `testAiProvider()`

主要内部流程方法：

- `handleCameraUploadResult(...)`
- `handleCameraPreviewResult(...)`
- `handleBoardAnalyzeRequest()`
- `handleTimedCaptureRequest()`
- `handleBandCommand(message)`
- `handleBandTimedCaptureCommand()`
- `handleBandAnalyzeCommand()`
- `startAnalyzeFlow(...)`
- `runAnalyzeOnce(...)`
- `rotateConfigsForSource(...)`
- `buildOrderedConfigs()`
- `resolveConfig(slot)`
- `applyCameraConfigThen(...)`
- `sendLiveStatusToBand(...)`
- `sendResultToBand(...)`
- `buildSelectedImageState(...)`
- `buildBoardImageState(...)`
- `refreshSelectedImageState()`
- `appendBoardImage(image)`
- `clearCurrentImageQueueForNextCaptureIfNeeded(triggerSource)`
- `startAutoRefreshLoop()`
- `finishAnalyzeFlow()`
- `handleCameraLog(message)`
- `buildBandCameraLog(message)`
- `logAiRaw(result)`
- `addLog(source, message)`

### 7.3 `BandBridge.kt`

职责：

- 管理手机和手环的官方通信链路
- 发送文本给手环
- 接收手环主动发回的控制指令

接口与主要方法：

- `BandBridge.refreshConnection(callback)`
- `BandBridge.send(message, callback)`
- `WearableBandBridge.refreshConnection(...)`
- `WearableBandBridge.send(...)`
- `sendWithNodeRefreshRetry(...)`
- `ensureDeviceManagerPermission(...)`
- `requestDeviceManagerPermission(...)`
- `sendPayload(...)`
- `withConnectedNode(...)`
- `ensureMessageListener(node)`
- `handleIncomingMessage(nodeId, message)`

说明：

- 手环下半块三击与长按，最终也是通过这里送回手机

### 7.4 `CameraBridge.kt`

职责：

- 管理相机板连接
- 管理 Wi-Fi 主链
- 管理 BLE 备用链
- 管理从板子接收图片
- 管理向板子发命令

接口方法：

- `refreshConnection()`
- `requestCapture(callback)`
- `requestPreview(callback)`
- `requestUpload(callback)`
- `acknowledgeTimedCaptureArmed(callback)`
- `requestTimedCaptureStart(callback)`
- `requestAnalyzeCommand(callback)`
- `applyCameraConfig(frameSize, jpegQuality, callback)`
- `requiredPermissions()`
- `hasRequiredPermissions(context)`

`Esp32BleCameraBridge` 主要内部方法：

- `startWifiServerIfNeeded()`
- `handleWifiSocket(socket)`
- `handleWifiRegister(socket, body)`
- `handleWifiUpload(...)`
- `handleWifiAnalyze(socket)`
- `handleWifiTimedCapture(socket)`
- `fetchLatestPreviewOverWifi(...)`
- `sendWifiAction(...)`
- `startBleScanIfNeeded(background)`
- `connectBleGatt(device)`
- `bindBleCharacteristics(gatt)`
- `enableBleNotifications(gatt)`
- `writeBleCommand(...)`
- `flushBleCommandQueue()`
- `handleBleStatus(bytes)`
- `handleBleMeta(bytes)`
- `handleBleChunk(bytes)`
- `prepareUploadWait(...)`
- `consumeIncomingImage(result)`
- `writeHttpResponse(socket, code, body)`
- `matchesTargetBoard(result)`

### 7.5 `CameraBleProtocol.kt`

职责：

- 统一安卓端和板子端的 BLE 指令协议字符串

主要内容：

- `buildConfigCommand(frameSize, jpegQuality)`
- `COMMAND_CAPTURE`
- `COMMAND_FETCH_LAST`
- `COMMAND_PING`
- `COMMAND_ACK_TIMED_CAPTURE`
- `COMMAND_ANALYZE`
- `COMMAND_TIMED_START`

### 7.6 `AiAnalyzer.kt`

职责：

- 发起 AI 测试与正式分析
- 处理自定义接口兼容
- 解析多种返回格式

主要方法：

- `test(config)`
- `analyzeImages(...)`
- `resolveCustomEndpoints(rawUrl)`
- `runCustomWithFallback(...)`
- `shouldTryNextCustomEndpoint(result)`
- `parseSingleTextResult(...)`
- `parseOpenAiText(json)`
- `parseChatCompletionsText(json)`
- `parseLooseText(json)`
- `postJson(...)`

### 7.7 `OcrEngine.kt`

职责：

- 本地 OCR

主要方法：

- `recognize(uri)`
- `recognize(image)`
- `recognizeInternal(image)`
- `awaitResult()`
- `awaitText()`

### 7.8 `ImageRepository.kt`

职责：

- 加载图片
- 压缩图片
- 规范图片大小

主要方法：

- `load(uri)`
- `loadAll(uris)`
- `optimizeImage(...)`
- `scaleBitmapIfNeeded(bitmap)`
- `calculateInSampleSize(...)`
- `resolveDisplayName(...)`

### 7.9 `AppSettingsStore.kt`

职责：

- 读写本地设置

主要方法：

- `load()`
- `save(settings)`
- `readEndpoints(array)`
- `writeEndpoints(endpoints)`
- `readRoutes(array)`
- `writeRoutes(routes)`

### 7.10 保活模块

文件：

- `BridgeApplication.kt`
- `KeepAliveService.kt`
- `BootReceiver.kt`

作用：

- 应用启动时拉起保活服务
- 开机后尝试恢复保活
- 尽量保持前台运行和部分唤醒

---

## 8. 手环端功能总表

### 8.1 主页面结构

当前直接进入：

- `pages/text/index`

页面分为上下两块：

- 上半块：历史
- 下半块：接收 / 日志 / 结果

### 8.2 上半块历史功能

- 仅显示少量历史项，支持上下滑动查看更多
- 点标题进入详情页
- 删除历史
- 本地存储历史，强退不丢

### 8.3 下半块功能

- 显示实时日志
- 显示分析结果
- 支持上下滚动查看长文本
- 支持接收状态更新
- 支持接收震动型状态

### 8.4 下半块交互功能

在不影响原有日志显示和功能的前提下：

- 3 秒内点击 3 次：发送定时拍照指令到手机
- 长按 3 秒：发送分析指令到手机

### 8.5 震动功能

当前支持：

- 单震
- 双震

当前用于：

- 定时模式开始
- 拍照前 3 秒提示
- 拍照后完成提示

### 8.6 亮屏功能

应用打开后：

- 尽量保持常亮

---

## 9. 手环端主要模块与关键方法

### 9.1 `pages/text/index/index.ux`

职责：

- 手环主页面
- 历史显示
- 日志显示
- 结果显示
- 下半块控制手势
- 震动反馈

关键方法：

- `normalizeIncoming(raw)`
- `onShow()`
- `onHide()`
- `onDestroy()`
- `vibrateShort()`
- `vibrateDouble()`
- `clearReceiverLongPressTimer()`
- `handleReceiverTouchStart(event)`
- `handleReceiverTouchMove(event)`
- `handleReceiverTouchEnd()`
- `removeItem(id)`
- `openDetailById(id)`
- `handleTouchStart(event)`
- `handleTouchEnd(event)`

消息类型重点：

- `status_update`
- `status_vibrate`
- `status_vibrate_double`
- `text_result`
- `watch_command`

### 9.2 `pages/detail/index/index.ux`

职责：

- 显示历史详情
- 正文上下滚动
- 顶部区域负责返回手势

关键方法：

- `onShow()`
- `onHide()`
- `onDestroy()`
- `handleTouchStart(event)`
- `handleTouchEnd(event)`

### 9.3 `utils/link.js`

职责：

- 手环本地状态和历史存储

关键方法：

- `loadBandState(callback)`
- `setCurrentStatus(status)`
- `updateLiveMessage(message)`
- `saveReceivedMessage(message)`
- `deleteHistoryItem(id)`
- `selectHistoryItemById(id, callback)`
- `loadSelectedHistoryItem(callback)`

---

## 10. 板子端功能总表

### 10.1 当前硬件路线

- ESP32-S3
- OV5640 自动对焦镜头
- Wi-Fi + BLE
- SD 卡缓存

### 10.2 当前板子默认控制方式

- `GPIO14`
  - 短按：拍 1 张并自动发到手机图片队列，不自动分析
  - 长按 3 秒：进入定时十连拍模式
- `GPIO21`
  - 发送分析请求
  - 不重新拍照

### 10.3 传输链路

- Wi-Fi 是主链
- BLE 是备用链

### 10.4 板子主功能

- 自动连接手机热点
- 维持手机本地服务器注册
- 拍照
- 自动对焦
- 单张图 Wi-Fi 发送
- Wi-Fi 失败时 BLE 备用发图
- 定时十连拍
- 存 SD 卡
- 十张后统一上传并自动请求分析
- 摄像头 10 秒空闲自动关闭，降低发热

### 10.5 定时十连拍逻辑

当前逻辑：

1. 长按 `GPIO14` 3 秒，或手环三击下半块
2. 手机先同步状态到手环
3. 手环震动
4. 板子收到 ACK 后 5 秒开始第 1 张
5. 后续每 10 秒拍 1 张
6. 每张先存到 SD 卡
7. 满 10 张统一上传
8. 上传完成自动请求分析

### 10.6 自动关相机逻辑

- 平时不持续保持摄像头一直工作
- 拍照、预览、上传前自动唤醒
- 完成后 10 秒无活动自动关闭

---

## 11. 板子端主要模块与关键方法

### 11.1 主文件

`board/esp32-s3-cam-ble\arduino\BandBridgeCam\BandBridgeCam.ino`

### 11.2 按动作与命令队列相关

- `queueCapture()`
- `queueCaptureAndSend()`
- `queueSend()`
- `queueAnalyze()`
- `onTimedCaptureArmed()`

### 11.3 SD 卡与定时缓存相关

- `ensureSdCardReady()`
- `timedFramePath(index)`
- `timedFramePathNoSlash(index)`
- `timedFramePathMounted(index)`
- `canWriteSdTestFile()`
- `resetTimedCaptureState(clearStoredFrames)`
- `startTimedCaptureMode()`
- `startTimedCaptureDirect()`
- `saveCachedFrameToSd(index)`
- `uploadTimedFrameFromSd(index)`
- `processTimedCaptureSchedule()`
- `processTimedUploadQueue()`

### 11.4 Wi-Fi 相关

- `ipToString(ip)`
- `writeAllToPhone(client, data, length)`
- `isStaReady()`
- `startStaConnection(forceRestart)`
- `postToPhoneServer(...)`
- `registerToPhoneServer()`
- `requestAnalyzeOnPhone()`
- `requestTimedCaptureOnPhone()`
- `ensurePhoneServerReadyForUpload()`
- `sendJpegBytesToPhoneWifi(...)`
- `sendFrameToPhoneWifi()`
- `startPreviewServer()`
- `initWifi()`
- `maintainWifiLink()`

### 11.5 相机与自动对焦相关

- `frameSizeToDimensions(...)`
- `parseFrameSize(...)`
- `tryInitCameraConfig(config, tag)`
- `markCameraActivity()`
- `resetAutofocusState()`
- `configureSensorDefaults(sensor)`
- `setupOv5640Autofocus(sensor)`
- `waitForFocusIfNeeded(timeoutMs)`
- `initCamera()`
- `deinitCamera()`
- `ensureCameraReady()`
- `applyCameraConfig(frameSize, jpegQuality)`
- `cacheLatestFrame()`
- `ensureFrameReady()`
- `notifyFrameToPhone()`
- `shutdownCameraIfIdle()`

### 11.6 BLE 相关

- `initBle()`
- `initButtons()`
- `pollButtons()`
- `processPendingActions()`

### 11.7 主循环

- `setup()`
- `loop()`

### 11.8 OV5640 AF 支持文件

- `ESP32_OV5640_AF.h`
- `ESP32_OV5640_AF.cpp`
- `ESP32_OV5640_cfg.h`

---

## 12. 当前关键默认行为

### 12.1 手机热点参数

当前板子默认连接的手机热点：

- SSID：`Xiaomiband10`
- 密码：`Zz072547`
- 频段：`2.4GHz`

### 12.2 Wi-Fi 主链说明

当前主链优先级：

1. Wi-Fi 主链
2. BLE 备用链

### 12.3 图片队列行为

- 板子拍照发到手机后，会进入手机图片队列
- 普通拍照不自动分析
- 分析成功后，不会立刻清空队列
- 需下一次拍照时才清空旧队列

### 12.4 相机分辨率与压缩

当前常用设置：

- `XGA`
- `q10` 或 `q12`

说明：

- JPEG 压缩值越小越清晰
- `q10` 比 `q30` 清晰

### 12.5 SD 卡要求

建议：

- 格式化为 `FAT32`

---

## 13. 操作流程说明

### 13.1 普通拍照上传但不分析

方式 1：手机点拍照  
方式 2：板子短按 `GPIO14`

流程：

1. 板子拍照
2. 自动发图到手机
3. 手机加入图片队列
4. 不自动分析

### 13.2 手动分析当前队列

方式：

- 手机点分析
- 板子按 `GPIO21`
- 手环下半块长按 3 秒

流程：

1. 手机读取当前队列
2. 按顺位依次选 API
3. 开始分析
4. 手环显示“顺位 N（API M）”
5. 成功后手环显示最终结果
6. 历史正常保存

### 13.3 定时十连拍模式

触发方式：

- 板子长按 `GPIO14` 3 秒
- 手环下半块 3 秒内点击 3 次

流程：

1. 触发定时请求
2. 手机通知手环
3. 手环震动
4. 板子收到 ACK
5. 5 秒后第 1 张
6. 每 10 秒 1 张
7. 每张拍前 3 秒震一下
8. 每张拍完震两下
9. 每张先存 SD
10. 十张后统一上传
11. 上传完成自动请求分析

---

## 14. 编译产物位置

以下路径是开发者在本地完成构建后默认产生的位置，公开仓库中不应直接包含这些产物。

### 14.1 安卓 APK

`mobile/android-demo/app/build/outputs/apk/debug/app-debug.apk`

### 14.2 手环 RPK

`band/nb666/.temp_nb666/dist/com.demo.bandbridge.debug.0.1.0.rpk`

### 14.3 板子固件

`board/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3/BandBridgeCam.ino.merged.bin`

---

## 15. 开源仓库签名与发布说明

如果准备把本项目上传到公开仓库，建议遵循以下规则：

### 15.1 不要上传的内容

- 安卓 keystore / `.jks`
- 已签名的 `.apk`
- 已签名的 `.rpk`
- 手环 `sign` 目录
- 各端 `build` / `dist` / 临时输出目录

### 15.2 安卓签名说明

公开仓库只保留源码，不保留你的私有签名文件。  
其他开发者需要自行：

1. 创建自己的 Android keystore
2. 在本地 Gradle 或 Android Studio 中配置签名
3. 本地自行生成 APK

### 15.3 手环签名说明

公开仓库只保留手环源码，不保留你的私有签名目录。  
其他开发者需要自行：

1. 准备自己的手环签名材料
2. 在本地构建环境中签名
3. 本地自行生成 `.rpk`

### 15.4 板子固件说明

板子固件不依赖 APK / RPK 这种签名体系。  
公开仓库建议只保留源码，不保留现成编译输出。

---

## 16. 安装与烧录操作

### 16.1 安装安卓 APK

直接安装：

说明：以下文件需要开发者在本地自行构建并签名后获得。

`mobile/android-demo/app/build/outputs/apk/debug/app-debug.apk`

### 16.2 安装手环 RPK

安装：

说明：以下文件需要开发者在本地自行构建并签名后获得。

`band/nb666/.temp_nb666/dist/com.demo.bandbridge.debug.0.1.0.rpk`

### 16.3 板子烧录

进入目录：

```powershell
cd "board/esp32-s3-cam-ble/arduino/BandBridgeCam/build/esp32.esp32.esp32s3"
```

烧录：

```powershell
& "C:\Users\ZQ\AppData\Local\Programs\Python\Python310\python.exe" -m esptool --chip esp32s3 --port COM4 --baud 921600 --before default-reset --after hard-reset write-flash -z 0x0 BandBridgeCam.ino.merged.bin
```

### 16.4 查看板子串口日志

```powershell
& "C:\Users\ZQ\AppData\Local\Programs\Python\Python310\python.exe" -m serial.tools.miniterm COM4 115200
```

退出日志：

- `Ctrl+]`

---

## 17. 备份目录

当前已有备份目录：

`本地备份目录（不建议上传到公开仓库）`

其中包含：

- `mobile_android-demo`
- `band_nb666`
- `board_ovnewcam`

---

## 18. 当前推荐测试顺序

### 17.1 普通单张拍照链路

1. 板子短按 `14`
2. 手机图片队列是否加入新图
3. 手环下半块是否显示日志

### 17.2 单次分析链路

1. 触发分析
2. 手环下半块是否先显示：
   - `顺位 N`
   - `正在使用顺位 N（API M）分析。`
3. 分析成功后是否显示结果
4. 历史是否正常保存

### 17.3 定时模式链路

1. 长按 `14` 3 秒，或手环下半块 3 秒内三击
2. 手环是否先震一下
3. 5 秒后是否开始第 1 张
4. 每张前 3 秒是否震一下
5. 每张拍完是否震两下
6. 十张后是否自动上传
7. 上传后是否自动分析

---

## 19. 使用注意事项

- 手环只做显示和轻交互，不承担核心 AI 逻辑
- 板子发热明显时，优先使用“按需开关相机”模式
- SD 卡建议先格式化为 `FAT32`
- 手机热点应尽量保持稳定，不要频繁切换
- Wi-Fi 是主链，BLE 是备用链
- 分析时手环显示的是实时状态，分析完成后会显示结果
- 新操作开始后，手环下半块会再次切回日志显示

---

## 20. 本说明书对应的文件版本范围

本说明书适用于当前工作区内以下工程的现版本：

- 安卓正式工程：`FINAL/mobile/android-demo`
- 手环正式工程：`FINAL/band/nb666`
- 板子正式工程：`OVNEWCAM/esp32-s3-cam-ble`

如后续功能再次大改，应同步更新本文件。


