#include <Arduino.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "ESP32_OV5640_AF.h"
#include <FS.h>
#include <SD_MMC.h>
#include <WiFi.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>

// Freenove ESP32-S3 WROOM camera pins from the user-provided pinout.
static constexpr const char *kBoardName = "Freenove ESP32-S3 WROOM";
static constexpr const char *kBleDeviceName = "BandBridgeCam";
static constexpr const char *kApSsid = "BandBridgeCam";
static constexpr const char *kApPassword = "12345678";
static constexpr const char *kStaSsid = "Xiaomiband10";
static constexpr const char *kStaPassword = "Zz072547";
static constexpr bool kStaHidden = true;
static constexpr uint16_t kPhoneServerPort = 8787;
static constexpr uint32_t kRegisterIntervalMs = 30000;
static constexpr uint32_t kStaReconnectIntervalMs = 10000;
static constexpr uint32_t kStaConnectTimeoutMs = 20000;
static constexpr uint32_t kPhoneUploadTimeoutMs = 90000;
static constexpr uint32_t kPhoneRegisterTimeoutMs = 5000;
static constexpr uint32_t kPreUploadRegisterWindowMs = 5000;
static constexpr uint32_t kCameraIdleShutdownMs = 10000;
static constexpr uint32_t kAutoFocusWaitMs = 1200;
static constexpr size_t kPhoneUploadWriteChunkSize = 4096;
static constexpr uint32_t kTimedCaptureLongPressMs = 3000;
static constexpr uint32_t kTimedCaptureFirstDelayMs = 5000;
static constexpr uint32_t kTimedCaptureIntervalMs = 10000;
static constexpr uint32_t kTimedCaptureAckTimeoutMs = 30000;
static constexpr uint32_t kTimedUploadRetryDelayMs = 3000;
static constexpr uint8_t kTimedCaptureTargetCount = 10;
static constexpr int kSdClkPin = 39;
static constexpr int kSdCmdPin = 38;
static constexpr int kSdData0Pin = 40;
static constexpr const char *kSdMountPoint = "/sdcard";

static constexpr gpio_num_t kCaptureButtonPin = GPIO_NUM_14;
static constexpr gpio_num_t kAnalyzeButtonPin = GPIO_NUM_21;

static constexpr int kCameraPinPwdn = -1;
static constexpr int kCameraPinReset = -1;
static constexpr int kCameraPinXclk = 15;
static constexpr int kCameraPinSiod = 4;
static constexpr int kCameraPinSioc = 5;
static constexpr int kCameraPinY9 = 16;
static constexpr int kCameraPinY8 = 17;
static constexpr int kCameraPinY7 = 18;
static constexpr int kCameraPinY6 = 12;
static constexpr int kCameraPinY5 = 10;
static constexpr int kCameraPinY4 = 8;
static constexpr int kCameraPinY3 = 9;
static constexpr int kCameraPinY2 = 11;
static constexpr int kCameraPinVsync = 6;
static constexpr int kCameraPinHref = 7;
static constexpr int kCameraPinPclk = 13;

static framesize_t gCurrentFrameSize = FRAMESIZE_XGA;
static int gCurrentJpegQuality = 12;
static constexpr size_t kProtocolChunkSize = 120;
static constexpr uint32_t kButtonDebounceMs = 60;
static constexpr uint32_t kChunkDelayMs = 15;

static constexpr const char *kCameraServiceUuid = "7D8A1000-4B2A-4D44-9F72-5E7BC9A10000";
static constexpr const char *kCameraControlUuid = "7D8A1001-4B2A-4D44-9F72-5E7BC9A10000";
static constexpr const char *kCameraStatusUuid = "7D8A1002-4B2A-4D44-9F72-5E7BC9A10000";
static constexpr const char *kCameraMetaUuid = "7D8A1003-4B2A-4D44-9F72-5E7BC9A10000";
static constexpr const char *kCameraChunkUuid = "7D8A1004-4B2A-4D44-9F72-5E7BC9A10000";

static constexpr const char *kCommandPing = "PING";
static constexpr const char *kCommandCapture = "CAPTURE";
static constexpr const char *kCommandFetchLast = "FETCH_LAST";
static constexpr const char *kCommandSetConfig = "SETCFG";
static constexpr const char *kCommandAnalyze = "ANALYZE";
static constexpr const char *kCommandTimedAck = "TIMEDACK";
static constexpr const char *kCommandTimedStart = "TIMEDSTART";

struct __attribute__((packed)) FrameMeta {
  uint32_t frameId;
  uint32_t totalSize;
  uint16_t chunkSize;
  uint16_t width;
  uint16_t height;
};

struct CachedFrame {
  uint8_t *data = nullptr;
  size_t length = 0;
  uint16_t width = 0;
  uint16_t height = 0;
  uint32_t frameId = 0;
  uint64_t capturedAtMs = 0;
  bool ready = false;
};

static SemaphoreHandle_t gFrameMutex = nullptr;
static BLEServer *gBleServer = nullptr;
static BLECharacteristic *gControlCharacteristic = nullptr;
static BLECharacteristic *gStatusCharacteristic = nullptr;
static BLECharacteristic *gMetaCharacteristic = nullptr;
static BLECharacteristic *gChunkCharacteristic = nullptr;
static httpd_handle_t gHttpServer = nullptr;
static httpd_handle_t gStreamServer = nullptr;

static CachedFrame gCachedFrame;
static String gStatusText = "booting";
static bool gCameraReady = false;
static bool gApReady = false;
static bool gBleConnected = false;
static bool gStaConnected = false;
static bool gStaConnectInProgress = false;
static bool gPhoneServerRegistered = false;
static bool gCaptureRequested = false;
static bool gSendRequested = false;
static bool gAnalyzeRequested = false;
static uint32_t gNextFrameId = 1;
static uint32_t gLastRegisterAttemptMs = 0;
static uint32_t gLastRegisterSuccessMs = 0;
static uint32_t gLastStaReconnectAttemptMs = 0;
static uint32_t gStaConnectStartedMs = 0;
static uint32_t gLastCameraActivityMs = 0;
static bool gAutoFocusAvailable = false;
static bool gAutoFocusContinuous = false;
static OV5640 gOv5640Af;
static bool gSdReady = false;
static bool gTimedModeAwaitingAck = false;
static bool gTimedModeActive = false;
static bool gTimedUploadPending = false;
static uint32_t gTimedAckRequestedAtMs = 0;
static uint32_t gTimedNextCaptureAtMs = 0;
static uint32_t gTimedNextUploadAttemptAtMs = 0;
static uint32_t gTimedSessionId = 0;
static uint8_t gTimedCapturedCount = 0;
static bool gTimedPreCaptureSignalSent = false;
static bool gCaptureLongPressHandled = false;
static uint32_t gCaptureButtonPressedAtMs = 0;
static String gTimedSessionDir;

static bool gLastCaptureButtonState = HIGH;
static bool gLastAnalyzeButtonState = HIGH;
static uint32_t gLastCaptureButtonTick = 0;
static uint32_t gLastAnalyzeButtonTick = 0;
static bool gCaptureButtonLatched = false;
static bool gAnalyzeButtonLatched = false;

static const char *kStreamContentType = "multipart/x-mixed-replace;boundary=frame";
static const char *kStreamBoundary = "\r\n--frame\r\n";
static const char *kStreamPart = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

static const char kIndexHtml[] PROGMEM = R"HTML(
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>BandBridgeCam</title>
  <style>
    body { font-family: Arial, sans-serif; margin: 18px; background: #10131a; color: #f3f5f8; }
    .card { background: #181d26; border-radius: 14px; padding: 16px; margin-bottom: 16px; }
    button { margin-right: 10px; margin-bottom: 10px; border: 0; border-radius: 999px; padding: 12px 18px; font-size: 16px; background: #4b8bff; color: #fff; }
    .muted { color: #9fb0c4; font-size: 14px; }
    img { width: 100%; border-radius: 12px; background: #000; }
    code { color: #8ee6a4; }
  </style>
</head>
<body>
  <div class="card">
    <h2>BandBridge 相机板调试页</h2>
    <div class="muted">GPIO14 = 拍照按钮，GPIO21 = 分析按钮。Wi-Fi 保留给你调试看图，BLE 给手机走正式链路。</div>
    <p id="status">状态读取中...</p>
    <button onclick="triggerAction('capture')">拍照并缓存</button>
    <button onclick="triggerAction('send')">发送到手机</button>
    <button onclick="refreshStatus()">刷新状态</button>
  </div>
  <div class="card">
    <h3>最近缓存</h3>
    <img id="lastImage" src="/last.jpg?t=0" alt="最近缓存图像">
  </div>
  <div class="card">
    <h3>实时预览</h3>
    <img src="/stream" alt="实时预览">
  </div>
  <script>
    async function refreshStatus() {
      try {
        const res = await fetch('/status');
        const data = await res.json();
        document.getElementById('status').innerHTML =
          '板子: <code>' + data.board + '</code><br>' +
          'BLE: <code>' + data.ble + '</code><br>' +
          '状态: <code>' + data.state + '</code><br>' +
          '缓存图像: <code>' + data.cached_bytes + ' bytes</code><br>' +
          '帧号: <code>' + data.frame_id + '</code><br>' +
          'AP 地址: <code>' + data.ap_ip + '</code>';
      } catch (err) {
        document.getElementById('status').textContent = '状态读取失败: ' + err;
      }
    }
    async function triggerAction(action) {
      await fetch('/action?type=' + action);
      if (action === 'capture') {
        setTimeout(() => {
          document.getElementById('lastImage').src = '/last.jpg?t=' + Date.now();
          refreshStatus();
        }, 500);
      } else {
        refreshStatus();
      }
    }
    refreshStatus();
    setInterval(refreshStatus, 3000);
  </script>
</body>
</html>
)HTML";

static void queueCapture() {
  gCaptureRequested = true;
}

static void queueCaptureAndSend() {
  gCaptureRequested = true;
  gSendRequested = true;
}

static void queueSend() {
  gSendRequested = true;
}

static void queueAnalyze() {
  gAnalyzeRequested = true;
}

static void onTimedCaptureArmed() {
  gTimedModeAwaitingAck = false;
  gTimedModeActive = true;
  gTimedUploadPending = false;
  gTimedNextCaptureAtMs = millis() + kTimedCaptureFirstDelayMs;
  gTimedNextUploadAttemptAtMs = 0;
  gTimedPreCaptureSignalSent = false;
  updateStatus("timed_capture_armed");
}

static bool ensureSdCardReady() {
  if (gSdReady && SD_MMC.cardType() != CARD_NONE) {
    return true;
  }

  SD_MMC.end();
  if (!SD_MMC.setPins(kSdClkPin, kSdCmdPin, kSdData0Pin)) {
    updateStatus("sd_setpins_failed");
    return false;
  }

  gSdReady = SD_MMC.begin(kSdMountPoint, true, false);
  if (!gSdReady || SD_MMC.cardType() == CARD_NONE) {
    gSdReady = false;
    updateStatus("sd_mount_failed");
    return false;
  }

  updateStatus("sd_ready");
  if (!canWriteSdTestFile()) {
    gSdReady = false;
    updateStatus("sd_test_write_failed");
    SD_MMC.end();
    return false;
  }
  return true;
}

static void frameSizeToDimensions(framesize_t frameSize, uint16_t *width, uint16_t *height) {
  if (width == nullptr || height == nullptr) {
    return;
  }
  switch (frameSize) {
    case FRAMESIZE_SVGA:
      *width = 800;
      *height = 600;
      return;
    case FRAMESIZE_UXGA:
      *width = 1600;
      *height = 1200;
      return;
    case FRAMESIZE_XGA:
    default:
      *width = 1024;
      *height = 768;
      return;
  }
}

static String timedFramePath(uint8_t index) {
  char path[96];
  snprintf(path, sizeof(path), "/bandbridge_%lu_%02u.jpg", static_cast<unsigned long>(gTimedSessionId), index);
  return String(path);
}

static String timedFramePathNoSlash(uint8_t index) {
  char path[96];
  snprintf(path, sizeof(path), "bandbridge_%lu_%02u.jpg", static_cast<unsigned long>(gTimedSessionId), index);
  return String(path);
}

static String timedFramePathMounted(uint8_t index) {
  char path[128];
  snprintf(path, sizeof(path), "%s/bandbridge_%lu_%02u.jpg", kSdMountPoint, static_cast<unsigned long>(gTimedSessionId), index);
  return String(path);
}

static bool canWriteSdTestFile() {
  const char *testPaths[] = {
    "/bandbridge_test.tmp",
    "bandbridge_test.tmp",
    "/sdcard/bandbridge_test.tmp"
  };

  for (size_t i = 0; i < sizeof(testPaths) / sizeof(testPaths[0]); ++i) {
    const char *path = testPaths[i];
    if (SD_MMC.exists(path)) {
      SD_MMC.remove(path);
    }
    File file = SD_MMC.open(path, FILE_WRITE);
    if (!file) {
      continue;
    }
    const uint8_t marker[] = {'O', 'K'};
    size_t written = file.write(marker, sizeof(marker));
    file.close();
    SD_MMC.remove(path);
    if (written == sizeof(marker)) {
      return true;
    }
  }
  return false;
}

static void resetTimedCaptureState(bool clearStoredFrames) {
  gTimedModeAwaitingAck = false;
  gTimedModeActive = false;
  gTimedUploadPending = false;
  gTimedAckRequestedAtMs = 0;
  gTimedNextCaptureAtMs = 0;
  gTimedNextUploadAttemptAtMs = 0;
  gTimedCapturedCount = 0;
  gTimedPreCaptureSignalSent = false;
  gCaptureLongPressHandled = false;
  if (clearStoredFrames && gSdReady && gTimedSessionDir.length() > 0) {
    for (uint8_t index = 1; index <= kTimedCaptureTargetCount; ++index) {
      String paths[3] = {
        timedFramePath(index),
        timedFramePathNoSlash(index),
        timedFramePathMounted(index)
      };
      for (uint8_t pathIndex = 0; pathIndex < 3; ++pathIndex) {
        String path = paths[pathIndex];
        if (SD_MMC.exists(path.c_str())) {
          SD_MMC.remove(path.c_str());
        }
      }
    }
  }
  gTimedSessionDir = "";
}

static bool startTimedCaptureMode() {
  if (gTimedModeAwaitingAck || gTimedModeActive || gTimedUploadPending) {
    updateStatus("timed_capture_busy");
    return false;
  }
  if (!ensureSdCardReady()) {
    return false;
  }

  gTimedSessionId = millis();
  gTimedSessionDir = "/";
  for (uint8_t index = 1; index <= kTimedCaptureTargetCount; ++index) {
    String paths[3] = {
      timedFramePath(index),
      timedFramePathNoSlash(index),
      timedFramePathMounted(index)
    };
    for (uint8_t pathIndex = 0; pathIndex < 3; ++pathIndex) {
      String oldPath = paths[pathIndex];
      if (SD_MMC.exists(oldPath.c_str())) {
        SD_MMC.remove(oldPath.c_str());
      }
    }
  }

  gTimedCapturedCount = 0;
  gTimedModeAwaitingAck = true;
  gTimedAckRequestedAtMs = millis();
  gTimedNextCaptureAtMs = 0;
  gTimedNextUploadAttemptAtMs = 0;
  gTimedPreCaptureSignalSent = false;
  updateStatus("timed_capture_requested");
  return true;
}

static bool startTimedCaptureDirect() {
  if (!startTimedCaptureMode()) {
    return false;
  }
  onTimedCaptureArmed();
  updateStatus("timed_capture_direct");
  return true;
}

static String ipToString(const IPAddress &ip) {
  return String(ip[0]) + "." + String(ip[1]) + "." + String(ip[2]) + "." + String(ip[3]);
}

static bool writeAllToPhone(WiFiClient &client, const uint8_t *data, size_t length) {
  if (data == nullptr || length == 0) {
    return true;
  }

  size_t offset = 0;
  while (offset < length) {
    const size_t remain = length - offset;
    const size_t slice = remain > kPhoneUploadWriteChunkSize ? kPhoneUploadWriteChunkSize : remain;
    const size_t written = client.write(data + offset, slice);
    if (written == 0) {
      return false;
    }
    offset += written;
    yield();
  }
  return true;
}

static bool isStaReady() {
  return WiFi.status() == WL_CONNECTED && WiFi.localIP()[0] != 0;
}

static void startStaConnection(bool forceRestart) {
  if (strlen(kStaSsid) == 0) {
    return;
  }

  wl_status_t status = WiFi.status();
  if (!forceRestart && gStaConnectInProgress &&
      (status == WL_IDLE_STATUS || status == WL_DISCONNECTED)) {
    return;
  }

  if (forceRestart) {
    WiFi.disconnect(false, true);
    delay(200);
  }

  Serial.printf("STA connect start%s: %s\n", forceRestart ? " (restart)" : "", kStaSsid);
  WiFi.begin(kStaSsid, kStaPassword, 0, nullptr, kStaHidden);
  gStaConnectInProgress = true;
  gStaConnectStartedMs = millis();
  gLastStaReconnectAttemptMs = gStaConnectStartedMs;
}

static void updateStatus(const String &statusText) {
  gStatusText = statusText;
  Serial.println("[status] " + statusText);
  if (gStatusCharacteristic != nullptr) {
    gStatusCharacteristic->setValue(statusText.c_str());
    if (gBleConnected) {
      gStatusCharacteristic->notify();
    }
  }
}

static const char *frameSizeToString(framesize_t frameSize) {
  switch (frameSize) {
    case FRAMESIZE_SVGA:
      return "SVGA";
    case FRAMESIZE_XGA:
      return "XGA";
    case FRAMESIZE_UXGA:
      return "UXGA";
    default:
      return "XGA";
  }
}

static bool parseFrameSize(String value, framesize_t *outFrameSize) {
  value.trim();
  value.toUpperCase();
  if (value == "SVGA") {
    *outFrameSize = FRAMESIZE_SVGA;
    return true;
  }
  if (value == "XGA") {
    *outFrameSize = FRAMESIZE_XGA;
    return true;
  }
  if (value == "UXGA") {
    *outFrameSize = FRAMESIZE_UXGA;
    return true;
  }
  return false;
}

static bool postToPhoneServer(
  const char *path,
  const uint8_t *body,
  size_t bodyLength,
  const char *contentType,
  const char *extraHeaders,
  uint32_t timeoutMs
) {
  if (!isStaReady()) {
    return false;
  }

  WiFiClient client;
  const IPAddress phoneIp = WiFi.gatewayIP();
  if (!client.connect(phoneIp, kPhoneServerPort)) {
    return false;
  }
  client.setTimeout(timeoutMs);
  client.setNoDelay(true);

  client.printf("POST %s HTTP/1.1\r\n", path);
  client.printf("Host: %s:%u\r\n", ipToString(phoneIp).c_str(), kPhoneServerPort);
  client.print("Connection: close\r\n");
  client.printf("Content-Type: %s\r\n", contentType);
  client.printf("Content-Length: %u\r\n", static_cast<unsigned>(bodyLength));
  if (extraHeaders != nullptr && strlen(extraHeaders) > 0) {
    client.print(extraHeaders);
  }
  client.print("\r\n");

  if (bodyLength > 0 && body != nullptr) {
    if (!writeAllToPhone(client, body, bodyLength)) {
      client.stop();
      return false;
    }
  }
  client.flush();

  const uint32_t startWaitMs = millis();
  while (!client.available() && client.connected() &&
         millis() - startWaitMs < timeoutMs) {
    delay(10);
  }
  if (!client.available()) {
    client.stop();
    return false;
  }

  const String statusLine = client.readStringUntil('\n');
  client.stop();
  return statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200");
}

static bool registerToPhoneServer() {
  if (!isStaReady()) {
    return false;
  }

  String payload = "{";
  payload += "\"board\":\"" + String(kBoardName) + "\",";
  payload += "\"board_ip\":\"" + ipToString(WiFi.localIP()) + "\",";
  payload += "\"ap_ip\":\"" + ipToString(WiFi.softAPIP()) + "\",";
  payload += "\"sta_ip\":\"" + ipToString(WiFi.localIP()) + "\",";
  payload += "\"frame_size\":\"" + String(frameSizeToString(gCurrentFrameSize)) + "\",";
  payload += "\"jpeg_quality\":" + String(gCurrentJpegQuality);
  payload += "}";

  return postToPhoneServer(
    "/camera/register",
    reinterpret_cast<const uint8_t *>(payload.c_str()),
    payload.length(),
    "application/json",
    nullptr,
    kPhoneRegisterTimeoutMs
  );
}

static bool requestAnalyzeOnPhone() {
  return postToPhoneServer(
    "/camera/analyze",
    nullptr,
    0,
    "application/json",
    nullptr,
    kPhoneRegisterTimeoutMs
  );
}

static bool requestTimedCaptureOnPhone() {
  return postToPhoneServer(
    "/camera/timed-capture",
    nullptr,
    0,
    "application/json",
    nullptr,
    kPhoneRegisterTimeoutMs
  );
}

static bool ensurePhoneServerReadyForUpload() {
  if (!isStaReady()) {
    return false;
  }

  const uint32_t now = millis();
  const bool recentlyRegistered =
    gPhoneServerRegistered &&
    (now - gLastRegisterSuccessMs) <= kPreUploadRegisterWindowMs;
  if (recentlyRegistered) {
    return true;
  }

  gLastRegisterAttemptMs = now;
  gPhoneServerRegistered = registerToPhoneServer();
  if (gPhoneServerRegistered) {
    gLastRegisterSuccessMs = millis();
  }
  return gPhoneServerRegistered;
}

static bool sendJpegBytesToPhoneWifi(
  const uint8_t *data,
  size_t length,
  uint16_t width,
  uint16_t height,
  uint32_t frameId
) {
  if (!isStaReady()) {
    return false;
  }
  if (!ensurePhoneServerReadyForUpload()) {
    updateStatus("wifi_register_before_send_failed");
  }

  char extraHeaders[256];
  snprintf(
    extraHeaders,
    sizeof(extraHeaders),
    "X-Frame-Id: %lu\r\nX-Image-Width: %u\r\nX-Image-Height: %u\r\n",
    static_cast<unsigned long>(frameId),
    width,
    height
  );

  updateStatus("sending_wifi");
  bool ok = postToPhoneServer(
    "/camera/upload",
    data,
    length,
    "image/jpeg",
    extraHeaders,
    kPhoneUploadTimeoutMs
  );
  if (!ok) {
    delay(200);
    updateStatus("sending_wifi_retry");
    ok = postToPhoneServer(
      "/camera/upload",
      data,
      length,
      "image/jpeg",
      extraHeaders,
      kPhoneUploadTimeoutMs
    );
  }
  if (!ok && isStaReady()) {
    updateStatus("wifi_reregister");
    gLastRegisterAttemptMs = millis();
    gPhoneServerRegistered = registerToPhoneServer();
    delay(200);
    if (gPhoneServerRegistered) {
      updateStatus("sending_wifi_retry2");
      ok = postToPhoneServer(
        "/camera/upload",
        data,
        length,
        "image/jpeg",
        extraHeaders,
        kPhoneUploadTimeoutMs
      );
    }
  }

  if (ok) {
    gPhoneServerRegistered = true;
    gLastRegisterSuccessMs = millis();
    markCameraActivity();
    updateStatus("frame_sent_wifi");
  } else {
    gPhoneServerRegistered = false;
    updateStatus("wifi_send_failed");
  }
  return ok;
}

static camera_config_t buildCameraConfig() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = kCameraPinY2;
  config.pin_d1 = kCameraPinY3;
  config.pin_d2 = kCameraPinY4;
  config.pin_d3 = kCameraPinY5;
  config.pin_d4 = kCameraPinY6;
  config.pin_d5 = kCameraPinY7;
  config.pin_d6 = kCameraPinY8;
  config.pin_d7 = kCameraPinY9;
  config.pin_xclk = kCameraPinXclk;
  config.pin_pclk = kCameraPinPclk;
  config.pin_vsync = kCameraPinVsync;
  config.pin_href = kCameraPinHref;
  config.pin_sccb_sda = kCameraPinSiod;
  config.pin_sccb_scl = kCameraPinSioc;
  config.pin_pwdn = kCameraPinPwdn;
  config.pin_reset = kCameraPinReset;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = gCurrentFrameSize;
  config.jpeg_quality = gCurrentJpegQuality;
  config.fb_count = psramFound() ? 2 : 1;
  config.grab_mode = psramFound() ? CAMERA_GRAB_LATEST : CAMERA_GRAB_WHEN_EMPTY;
  config.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  return config;
}

static bool tryInitCameraConfig(camera_config_t &config, const char *tag) {
  Serial.printf(
    "Trying camera config [%s]: frame=%d quality=%d fb_count=%d fb_location=%d psram=%s\n",
    tag,
    static_cast<int>(config.frame_size),
    config.jpeg_quality,
    config.fb_count,
    config.fb_location,
    psramFound() ? "yes" : "no"
  );

  esp_err_t err = esp_camera_init(&config);
  if (err == ESP_OK) {
    Serial.printf("Camera init success with profile [%s]\n", tag);
    return true;
  }

  Serial.printf("Camera init failed with profile [%s]: 0x%x\n", tag, err);
  esp_camera_deinit();
  delay(200);
  return false;
}

static void markCameraActivity() {
  gLastCameraActivityMs = millis();
}

static void resetAutofocusState() {
  gAutoFocusAvailable = false;
  gAutoFocusContinuous = false;
}

static void configureSensorDefaults(sensor_t *sensor) {
  if (sensor == nullptr) {
    return;
  }

  if (sensor->id.PID == OV3660_PID) {
    sensor->set_vflip(sensor, 1);
    sensor->set_brightness(sensor, 1);
    sensor->set_saturation(sensor, -2);
  } else if (sensor->id.PID == OV5640_PID) {
    sensor->set_hmirror(sensor, 1);
    sensor->set_vflip(sensor, 0);
    sensor->set_brightness(sensor, 0);
    sensor->set_saturation(sensor, 0);
    sensor->set_sharpness(sensor, 2);
  }

  sensor->set_contrast(sensor, 1);
  sensor->set_quality(sensor, gCurrentJpegQuality);
  sensor->set_framesize(sensor, gCurrentFrameSize);
}

static void setupOv5640Autofocus(sensor_t *sensor) {
  resetAutofocusState();
  if (sensor == nullptr) {
    return;
  }

  if (!gOv5640Af.start(sensor)) {
    Serial.println("OV5640 AF: sensor is not AF-capable or PID mismatch.");
    return;
  }

  const uint8_t initResult = gOv5640Af.focusInit();
  if (initResult != 0) {
    Serial.printf("OV5640 AF: focusInit failed (%u)\n", initResult);
    return;
  }

  const uint8_t autoModeResult = gOv5640Af.autoFocusMode();
  if (autoModeResult != 0) {
    Serial.printf("OV5640 AF: autoFocusMode failed (%u)\n", autoModeResult);
    return;
  }

  gAutoFocusAvailable = true;
  gAutoFocusContinuous = true;
  Serial.println("OV5640 AF: continuous autofocus enabled.");
}

static void waitForFocusIfNeeded(uint32_t timeoutMs = kAutoFocusWaitMs) {
  if (!gAutoFocusAvailable || !gAutoFocusContinuous) {
    return;
  }

  const uint32_t start = millis();
  while (millis() - start < timeoutMs) {
    const uint8_t fwStatus = gOv5640Af.getFWStatus();
    if (fwStatus == FW_STATUS_S_FOCUSED || fwStatus == FW_STATUS_S_IDLE) {
      return;
    }
    delay(20);
  }
}

static bool initCamera() {
  camera_config_t config = buildCameraConfig();
  bool ok = tryInitCameraConfig(config, "default");

  if (!ok) {
    config = buildCameraConfig();
    config.fb_count = 1;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    ok = tryInitCameraConfig(config, "single_fb");
  }

  if (!ok) {
    config = buildCameraConfig();
    config.frame_size = FRAMESIZE_SVGA;
    config.jpeg_quality = 14;
    config.fb_count = 1;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    config.fb_location = CAMERA_FB_IN_DRAM;
    ok = tryInitCameraConfig(config, "svga_dram");
  }

  if (!ok) {
    config = buildCameraConfig();
    config.frame_size = FRAMESIZE_VGA;
    config.jpeg_quality = 16;
    config.fb_count = 1;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    config.fb_location = CAMERA_FB_IN_DRAM;
    ok = tryInitCameraConfig(config, "vga_dram");
  }

  if (!ok) {
    Serial.println("Camera init failed in all fallback profiles.");
    return false;
  }

  gCurrentFrameSize = config.frame_size;
  gCurrentJpegQuality = config.jpeg_quality;

  sensor_t *sensor = esp_camera_sensor_get();
  configureSensorDefaults(sensor);
  setupOv5640Autofocus(sensor);
  markCameraActivity();
  return true;
}

static void deinitCamera() {
  if (!gCameraReady) {
    return;
  }

  esp_camera_deinit();
  gCameraReady = false;
  resetAutofocusState();
  updateStatus("camera_sleep");
  Serial.println("Camera deinitialized for cooldown.");
}

static bool ensureCameraReady() {
  if (gCameraReady) {
    markCameraActivity();
    return true;
  }

  updateStatus("camera_wakeup");
  Serial.println("Camera wake requested.");
  gCameraReady = initCamera();
  if (!gCameraReady) {
    updateStatus("camera_init_failed");
    Serial.println("Camera wake failed.");
    return false;
  }

  updateStatus("ready");
  return true;
}

static void clearCachedFrameLocked() {
  if (gCachedFrame.data != nullptr) {
    free(gCachedFrame.data);
    gCachedFrame.data = nullptr;
  }
  gCachedFrame.length = 0;
  gCachedFrame.width = 0;
  gCachedFrame.height = 0;
  gCachedFrame.frameId = 0;
  gCachedFrame.capturedAtMs = 0;
  gCachedFrame.ready = false;
}

static bool applyCameraConfig(framesize_t frameSize, int jpegQuality) {
  const int safeQuality = constrain(jpegQuality, 10, 30);
  gCurrentFrameSize = frameSize;
  gCurrentJpegQuality = safeQuality;

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(1000)) == pdTRUE) {
    clearCachedFrameLocked();
    xSemaphoreGive(gFrameMutex);
  }

  if (!gCameraReady) {
    updateStatus(String("config_staged_") + frameSizeToString(gCurrentFrameSize) + "_q" + String(gCurrentJpegQuality));
    return true;
  }

  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor == nullptr) {
    updateStatus("config_sensor_missing");
    return false;
  }

  if (sensor->set_framesize(sensor, frameSize) != 0) {
    updateStatus("config_framesize_failed");
    return false;
  }
  if (sensor->set_quality(sensor, safeQuality) != 0) {
    updateStatus("config_quality_failed");
    return false;
  }

  configureSensorDefaults(sensor);
  setupOv5640Autofocus(sensor);
  markCameraActivity();
  updateStatus(String("config_applied_") + frameSizeToString(gCurrentFrameSize) + "_q" + String(gCurrentJpegQuality));
  return true;
}

static bool cacheLatestFrame() {
  if (!ensureCameraReady()) {
    return false;
  }

  waitForFocusIfNeeded();
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb == nullptr) {
    updateStatus("capture_failed");
    return false;
  }

  if (fb->format != PIXFORMAT_JPEG) {
    esp_camera_fb_return(fb);
    updateStatus("non_jpeg_frame");
    return false;
  }

  uint8_t *copy = static_cast<uint8_t *>(ps_malloc(fb->len));
  if (copy == nullptr) {
    copy = static_cast<uint8_t *>(malloc(fb->len));
  }
  if (copy == nullptr) {
    esp_camera_fb_return(fb);
    updateStatus("no_memory_for_frame");
    return false;
  }

  memcpy(copy, fb->buf, fb->len);

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(2000)) != pdTRUE) {
    free(copy);
    esp_camera_fb_return(fb);
    updateStatus("frame_lock_timeout");
    return false;
  }

  clearCachedFrameLocked();
  gCachedFrame.data = copy;
  gCachedFrame.length = fb->len;
  gCachedFrame.width = fb->width;
  gCachedFrame.height = fb->height;
  gCachedFrame.frameId = gNextFrameId++;
  gCachedFrame.capturedAtMs = millis();
  gCachedFrame.ready = true;
  xSemaphoreGive(gFrameMutex);

  esp_camera_fb_return(fb);
  markCameraActivity();
  updateStatus("image_ready");
  return true;
}

static bool ensureFrameReady() {
  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(1000)) != pdTRUE) {
    updateStatus("frame_lock_timeout");
    return false;
  }
  bool ready = gCachedFrame.ready;
  xSemaphoreGive(gFrameMutex);
  if (ready) {
    return true;
  }
  return cacheLatestFrame();
}

static bool saveCachedFrameToSd(uint8_t index) {
  if (!ensureSdCardReady()) {
    return false;
  }
  if (!ensureFrameReady()) {
    return false;
  }
  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
    updateStatus("frame_lock_timeout");
    return false;
  }

  String paths[3] = {
    timedFramePath(index),
    timedFramePathNoSlash(index),
    timedFramePathMounted(index)
  };

  size_t written = 0;
  bool opened = false;
  for (uint8_t pathIndex = 0; pathIndex < 3; ++pathIndex) {
    String path = paths[pathIndex];
    if (SD_MMC.exists(path.c_str())) {
      SD_MMC.remove(path.c_str());
    }
    File file = SD_MMC.open(path.c_str(), FILE_WRITE);
    if (!file) {
      continue;
    }
    opened = true;
    written = file.write(gCachedFrame.data, gCachedFrame.length);
    file.close();
    if (written == gCachedFrame.length) {
      break;
    }
    SD_MMC.remove(path.c_str());
  }
  xSemaphoreGive(gFrameMutex);

  if (!opened) {
    updateStatus("sd_open_failed");
    return false;
  }

  if (written != gCachedFrame.length) {
    updateStatus("sd_write_failed");
    return false;
  }

  return true;
}

static bool uploadTimedFrameFromSd(uint8_t index) {
  if (!ensureSdCardReady()) {
    return false;
  }

  String paths[3] = {
    timedFramePath(index),
    timedFramePathNoSlash(index),
    timedFramePathMounted(index)
  };
  File file;
  for (uint8_t pathIndex = 0; pathIndex < 3; ++pathIndex) {
    file = SD_MMC.open(paths[pathIndex].c_str(), FILE_READ);
    if (file) {
      break;
    }
  }
  if (!file) {
    updateStatus("timed_file_missing");
    return false;
  }

  const size_t length = file.size();
  if (length == 0) {
    file.close();
    updateStatus("timed_file_empty");
    return false;
  }

  uint8_t *buffer = static_cast<uint8_t *>(ps_malloc(length));
  if (buffer == nullptr) {
    buffer = static_cast<uint8_t *>(malloc(length));
  }
  if (buffer == nullptr) {
    file.close();
    updateStatus("timed_no_memory");
    return false;
  }

  const size_t readBytes = file.read(buffer, length);
  file.close();
  if (readBytes != length) {
    free(buffer);
    updateStatus("timed_read_failed");
    return false;
  }

  uint16_t width = 0;
  uint16_t height = 0;
  frameSizeToDimensions(gCurrentFrameSize, &width, &height);
  const bool ok = sendJpegBytesToPhoneWifi(
    buffer,
    length,
    width,
    height,
    gTimedSessionId * 100 + index
  );
  free(buffer);
  return ok;
}

static size_t currentChunkSize() {
  size_t chunk = 20;
  if (gBleServer != nullptr && gBleConnected) {
    uint16_t connId = gBleServer->getConnId();
    uint16_t mtu = gBleServer->getPeerMTU(connId);
    if (mtu > 3) {
      chunk = mtu - 3;
    }
  }
  if (chunk > kProtocolChunkSize) {
    chunk = kProtocolChunkSize;
  }
  if (chunk < 20) {
    chunk = 20;
  }
  return chunk;
}

static bool notifyFrameToPhone() {
  if (!gBleConnected) {
    updateStatus("phone_not_connected");
    return false;
  }
  if (!ensureFrameReady()) {
    return false;
  }

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
    updateStatus("frame_lock_timeout");
    return false;
  }

  const size_t chunkSize = currentChunkSize();
  FrameMeta meta = {
    .frameId = gCachedFrame.frameId,
    .totalSize = static_cast<uint32_t>(gCachedFrame.length),
    .chunkSize = static_cast<uint16_t>(chunkSize),
    .width = gCachedFrame.width,
    .height = gCachedFrame.height,
  };

  gMetaCharacteristic->setValue(reinterpret_cast<uint8_t *>(&meta), sizeof(meta));
  gMetaCharacteristic->notify();
  updateStatus("sending_chunks");
  delay(25);

  size_t offset = 0;
  while (offset < gCachedFrame.length) {
    size_t remain = gCachedFrame.length - offset;
    size_t slice = remain > chunkSize ? chunkSize : remain;
    gChunkCharacteristic->setValue(gCachedFrame.data + offset, slice);
    gChunkCharacteristic->notify();
    offset += slice;
    delay(kChunkDelayMs);
  }

  xSemaphoreGive(gFrameMutex);
  markCameraActivity();
  updateStatus("frame_sent");
  return true;
}

static bool sendFrameToPhoneWifi() {
  if (!isStaReady()) {
    return false;
  }
  if (!ensureFrameReady()) {
    return false;
  }

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(5000)) != pdTRUE) {
    updateStatus("frame_lock_timeout");
    return false;
  }

  bool ok = sendJpegBytesToPhoneWifi(
    gCachedFrame.data,
    gCachedFrame.length,
    gCachedFrame.width,
    gCachedFrame.height,
    gCachedFrame.frameId
  );
  xSemaphoreGive(gFrameMutex);
  return ok;
}

static void processTimedCaptureSchedule() {
  const uint32_t now = millis();

  if (gTimedModeAwaitingAck && now - gTimedAckRequestedAtMs >= kTimedCaptureAckTimeoutMs) {
    updateStatus("timed_capture_ack_timeout");
    resetTimedCaptureState(true);
    return;
  }

  if (!gTimedModeActive || gTimedUploadPending) {
    return;
  }
  if (gCaptureRequested || gSendRequested || gAnalyzeRequested) {
    return;
  }
  const uint8_t shotIndex = gTimedCapturedCount + 1;
  if (!gTimedPreCaptureSignalSent && now < gTimedNextCaptureAtMs) {
    const uint32_t remainMs = gTimedNextCaptureAtMs - now;
    if (remainMs <= 3000) {
      updateStatus(String("timed_capture_") + String(shotIndex) + "_soon");
      gTimedPreCaptureSignalSent = true;
    }
  }
  if (now < gTimedNextCaptureAtMs) {
    return;
  }

  updateStatus(String("timed_capture_") + String(shotIndex) + "_start");
  if (!cacheLatestFrame()) {
    gTimedNextCaptureAtMs = now + 2000;
    gTimedPreCaptureSignalSent = false;
    updateStatus("timed_capture_retry");
    return;
  }
  if (!saveCachedFrameToSd(shotIndex)) {
    updateStatus("timed_sd_save_failed");
    resetTimedCaptureState(false);
    return;
  }

  gTimedCapturedCount = shotIndex;
  updateStatus(String("timed_saved_") + String(gTimedCapturedCount) + "_of_" + String(kTimedCaptureTargetCount));
  deinitCamera();

  if (gTimedCapturedCount >= kTimedCaptureTargetCount) {
    gTimedModeActive = false;
    gTimedUploadPending = true;
    gTimedNextUploadAttemptAtMs = 0;
    updateStatus("timed_capture_complete");
    return;
  }

  gTimedNextCaptureAtMs = now + kTimedCaptureIntervalMs;
  gTimedPreCaptureSignalSent = false;
}

static void processTimedUploadQueue() {
  if (!gTimedUploadPending) {
    return;
  }
  const uint32_t now = millis();
  if (now < gTimedNextUploadAttemptAtMs) {
    return;
  }
  if (!isStaReady()) {
    updateStatus("timed_upload_waiting_wifi");
    gTimedNextUploadAttemptAtMs = now + kTimedUploadRetryDelayMs;
    return;
  }

  for (uint8_t index = 1; index <= gTimedCapturedCount; ++index) {
    updateStatus(String("timed_upload_") + String(index) + "_of_" + String(gTimedCapturedCount));
    if (!uploadTimedFrameFromSd(index)) {
      updateStatus("timed_upload_failed");
      gTimedNextUploadAttemptAtMs = millis() + kTimedUploadRetryDelayMs;
      return;
    }
  }

  gTimedUploadPending = false;
  updateStatus("timed_upload_complete");
  if (!requestAnalyzeOnPhone()) {
    queueAnalyze();
  } else {
    updateStatus("analyze_request_sent");
  }
  resetTimedCaptureState(true);
}

static esp_err_t statusHandler(httpd_req_t *req) {
  String json = "{";
  json += "\"board\":\"" + String(kBoardName) + "\",";
  json += "\"ble\":\"" + String(gBleConnected ? "connected" : "waiting") + "\",";
  json += "\"sta_connected\":" + String(isStaReady() ? "true" : "false") + ",";
  json += "\"phone_server\":" + String(gPhoneServerRegistered ? "true" : "false") + ",";
  json += "\"camera_on\":" + String(gCameraReady ? "true" : "false") + ",";
  json += "\"autofocus\":" + String(gAutoFocusAvailable ? "true" : "false") + ",";
  json += "\"sd_ready\":" + String(gSdReady ? "true" : "false") + ",";
  json += "\"timed_waiting_ack\":" + String(gTimedModeAwaitingAck ? "true" : "false") + ",";
  json += "\"timed_active\":" + String(gTimedModeActive ? "true" : "false") + ",";
  json += "\"timed_upload_pending\":" + String(gTimedUploadPending ? "true" : "false") + ",";
  json += "\"timed_captured_count\":" + String(gTimedCapturedCount) + ",";
  json += "\"state\":\"" + gStatusText + "\",";
  json += "\"ap_ip\":\"" + ipToString(WiFi.softAPIP()) + "\",";
  json += "\"sta_ip\":\"" + ipToString(WiFi.localIP()) + "\",";
  json += "\"capture_pin\":" + String(static_cast<int>(kCaptureButtonPin)) + ",";
  json += "\"analyze_pin\":" + String(static_cast<int>(kAnalyzeButtonPin)) + ",";
  json += "\"frame_size\":\"" + String(frameSizeToString(gCurrentFrameSize)) + "\",";
  json += "\"jpeg_quality\":" + String(gCurrentJpegQuality) + ",";

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(1000)) == pdTRUE) {
    json += "\"cached_bytes\":" + String(static_cast<unsigned long>(gCachedFrame.length)) + ",";
    json += "\"frame_id\":" + String(gCachedFrame.frameId) + ",";
    json += "\"width\":" + String(gCachedFrame.width) + ",";
    json += "\"height\":" + String(gCachedFrame.height);
    xSemaphoreGive(gFrameMutex);
  } else {
    json += "\"cached_bytes\":0,\"frame_id\":0,\"width\":0,\"height\":0";
  }

  json += "}";
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  return httpd_resp_send(req, json.c_str(), json.length());
}

static esp_err_t indexHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  return httpd_resp_send(req, kIndexHtml, strlen(kIndexHtml));
}

static esp_err_t lastJpegHandler(httpd_req_t *req) {
  if (!ensureFrameReady()) {
    return httpd_resp_send_500(req);
  }

  if (xSemaphoreTake(gFrameMutex, pdMS_TO_TICKS(3000)) != pdTRUE) {
    return httpd_resp_send_500(req);
  }

  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t result = httpd_resp_send(req, reinterpret_cast<const char *>(gCachedFrame.data), gCachedFrame.length);
  xSemaphoreGive(gFrameMutex);
  return result;
}

static esp_err_t captureHandler(httpd_req_t *req) {
  if (!ensureCameraReady()) {
    httpd_resp_set_status(req, "503 Service Unavailable");
    return httpd_resp_send(req, "camera_not_ready", HTTPD_RESP_USE_STRLEN);
  }

  waitForFocusIfNeeded();
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb == nullptr) {
    return httpd_resp_send_500(req);
  }

  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t result = httpd_resp_send(req, reinterpret_cast<const char *>(fb->buf), fb->len);
  esp_camera_fb_return(fb);
  markCameraActivity();
  return result;
}

static esp_err_t actionHandler(httpd_req_t *req) {
  char query[128] = {0};
  char action[16] = {0};
  char frameSize[16] = {0};
  char quality[16] = {0};

  if (httpd_req_get_url_query_str(req, query, sizeof(query)) == ESP_OK) {
    httpd_query_key_value(query, "type", action, sizeof(action));
    httpd_query_key_value(query, "frameSize", frameSize, sizeof(frameSize));
    httpd_query_key_value(query, "quality", quality, sizeof(quality));
  }

  String type(action);
  type.toUpperCase();
  if (type == "CAPTURE") {
    queueCapture();
  } else if (type == "SEND") {
    queueSend();
  } else if (type == "ANALYZE") {
    queueAnalyze();
  } else if (type == "TIMED_START" || type == "TIMEDSTART") {
    if (!startTimedCaptureDirect()) {
      httpd_resp_set_status(req, "409 Conflict");
      return httpd_resp_send(req, "{\"ok\":false}", HTTPD_RESP_USE_STRLEN);
    }
  } else if (type == "TIMED_ACK") {
    onTimedCaptureArmed();
  } else if (type == "CONFIG") {
    framesize_t parsedFrameSize = gCurrentFrameSize;
    const bool frameSizeOk = parseFrameSize(String(frameSize), &parsedFrameSize);
    const int parsedQuality = atoi(quality);
    if (!frameSizeOk || parsedQuality <= 0) {
      httpd_resp_set_status(req, "400 Bad Request");
      return httpd_resp_send(req, "{\"ok\":false}", HTTPD_RESP_USE_STRLEN);
    }
    applyCameraConfig(parsedFrameSize, parsedQuality);
  }

  httpd_resp_set_type(req, "application/json");
  return httpd_resp_send(req, "{\"ok\":true}", HTTPD_RESP_USE_STRLEN);
}

static esp_err_t streamHandler(httpd_req_t *req) {
  if (!ensureCameraReady()) {
    httpd_resp_set_status(req, "503 Service Unavailable");
    return httpd_resp_send(req, "camera_not_ready", HTTPD_RESP_USE_STRLEN);
  }

  httpd_resp_set_type(req, kStreamContentType);
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (fb == nullptr) {
      return ESP_FAIL;
    }

    if (fb->format != PIXFORMAT_JPEG) {
      esp_camera_fb_return(fb);
      return ESP_FAIL;
    }

    if (httpd_resp_send_chunk(req, kStreamBoundary, strlen(kStreamBoundary)) != ESP_OK) {
      esp_camera_fb_return(fb);
      break;
    }

    char header[64];
    size_t headerLength = snprintf(header, sizeof(header), kStreamPart, fb->len);
    if (httpd_resp_send_chunk(req, header, headerLength) != ESP_OK) {
      esp_camera_fb_return(fb);
      break;
    }

    if (httpd_resp_send_chunk(req, reinterpret_cast<const char *>(fb->buf), fb->len) != ESP_OK) {
      esp_camera_fb_return(fb);
      break;
    }

    esp_camera_fb_return(fb);
    markCameraActivity();
    delay(30);
  }

  return ESP_OK;
}

static void startPreviewServer() {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  config.server_port = 80;
  config.ctrl_port = 32768;
  config.max_uri_handlers = 8;

  httpd_uri_t indexUri = {.uri = "/", .method = HTTP_GET, .handler = indexHandler, .user_ctx = nullptr};
  httpd_uri_t statusUri = {.uri = "/status", .method = HTTP_GET, .handler = statusHandler, .user_ctx = nullptr};
  httpd_uri_t captureUri = {.uri = "/capture", .method = HTTP_GET, .handler = captureHandler, .user_ctx = nullptr};
  httpd_uri_t lastUri = {.uri = "/last.jpg", .method = HTTP_GET, .handler = lastJpegHandler, .user_ctx = nullptr};
  httpd_uri_t actionUri = {.uri = "/action", .method = HTTP_GET, .handler = actionHandler, .user_ctx = nullptr};

  if (httpd_start(&gHttpServer, &config) == ESP_OK) {
    httpd_register_uri_handler(gHttpServer, &indexUri);
    httpd_register_uri_handler(gHttpServer, &statusUri);
    httpd_register_uri_handler(gHttpServer, &captureUri);
    httpd_register_uri_handler(gHttpServer, &lastUri);
    httpd_register_uri_handler(gHttpServer, &actionUri);
  }

  config.server_port = 81;
  config.ctrl_port = 32769;
  httpd_uri_t streamUri = {.uri = "/stream", .method = HTTP_GET, .handler = streamHandler, .user_ctx = nullptr};
  if (httpd_start(&gStreamServer, &config) == ESP_OK) {
    httpd_register_uri_handler(gStreamServer, &streamUri);
  }
}

class CameraServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    (void)server;
    gBleConnected = true;
    updateStatus("phone_connected");
  }

  void onDisconnect(BLEServer *server) override {
    gBleConnected = false;
    updateStatus("phone_disconnected");
    BLEDevice::startAdvertising();
  }
};

class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String rawCommand = characteristic->getValue().c_str();
    String command = rawCommand;
    command.trim();
    command.toUpperCase();

    if (command == kCommandPing) {
      updateStatus("pong");
    } else if (command == kCommandCapture) {
      queueCapture();
    } else if (command == kCommandAnalyze) {
      queueAnalyze();
    } else if (command == kCommandFetchLast) {
      queueSend();
    } else if (command == kCommandTimedStart) {
      if (!startTimedCaptureDirect()) {
        updateStatus("timed_capture_request_failed");
      }
    } else if (command == kCommandTimedAck) {
      onTimedCaptureArmed();
    } else if (command.startsWith(kCommandSetConfig)) {
      int firstSep = rawCommand.indexOf('|');
      int secondSep = rawCommand.indexOf('|', firstSep + 1);
      if (firstSep < 0 || secondSep < 0) {
        updateStatus("config_bad_format");
        return;
      }

      String frameSizeText = rawCommand.substring(firstSep + 1, secondSep);
      String qualityText = rawCommand.substring(secondSep + 1);
      framesize_t parsedFrameSize = gCurrentFrameSize;
      if (!parseFrameSize(frameSizeText, &parsedFrameSize)) {
        updateStatus("config_bad_framesize");
        return;
      }

      const int parsedQuality = qualityText.toInt();
      if (parsedQuality <= 0) {
        updateStatus("config_bad_quality");
        return;
      }

      applyCameraConfig(parsedFrameSize, parsedQuality);
    } else {
      updateStatus("unknown_command");
    }
  }
};

static void initBle() {
  BLEDevice::init(kBleDeviceName);
  BLEDevice::setMTU(517);

  gBleServer = BLEDevice::createServer();
  gBleServer->setCallbacks(new CameraServerCallbacks());

  BLEService *service = gBleServer->createService(kCameraServiceUuid);
  gControlCharacteristic = service->createCharacteristic(
    kCameraControlUuid,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  gStatusCharacteristic = service->createCharacteristic(
    kCameraStatusUuid,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  gMetaCharacteristic = service->createCharacteristic(
    kCameraMetaUuid,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  gChunkCharacteristic = service->createCharacteristic(
    kCameraChunkUuid,
    BLECharacteristic::PROPERTY_NOTIFY
  );

  gControlCharacteristic->setCallbacks(new ControlCallbacks());
  gStatusCharacteristic->addDescriptor(new BLE2902());
  gMetaCharacteristic->addDescriptor(new BLE2902());
  gChunkCharacteristic->addDescriptor(new BLE2902());
  gStatusCharacteristic->setValue("booting");

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(kCameraServiceUuid);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
}

static void initWifi() {
  WiFi.persistent(false);
  WiFi.disconnect(true, true);
  delay(200);
  WiFi.mode(WIFI_AP_STA);
  WiFi.setSleep(false);
  delay(200);
  gApReady = WiFi.softAP(kApSsid, kApPassword, 1, 0, 4);
  Serial.printf("SoftAP start: %s\n", gApReady ? "ok" : "failed");
  if (strlen(kStaSsid) > 0) {
    startStaConnection(false);
  }
  Serial.print("SoftAP IP: ");
  Serial.println(WiFi.softAPIP());
}

static void initButtons() {
  pinMode(kCaptureButtonPin, INPUT_PULLUP);
  pinMode(kAnalyzeButtonPin, INPUT_PULLUP);
}

static void pollButtons() {
  uint32_t now = millis();

  bool captureState = digitalRead(kCaptureButtonPin);
  if (captureState != gLastCaptureButtonState) {
    gLastCaptureButtonTick = now;
    gLastCaptureButtonState = captureState;
  }
  if (!gCaptureButtonLatched && captureState == LOW && now - gLastCaptureButtonTick >= kButtonDebounceMs) {
    gCaptureButtonLatched = true;
    gCaptureLongPressHandled = false;
    gCaptureButtonPressedAtMs = now;
  } else if (gCaptureButtonLatched && captureState == LOW &&
             !gCaptureLongPressHandled &&
             now - gCaptureButtonPressedAtMs >= kTimedCaptureLongPressMs) {
    gCaptureLongPressHandled = true;
    if (startTimedCaptureMode()) {
      const bool deliveredToPhone = requestTimedCaptureOnPhone();
      if (deliveredToPhone) {
        gPhoneServerRegistered = true;
        gLastRegisterSuccessMs = millis();
      } else if (gBleConnected) {
        updateStatus("timed_capture_requested_ble");
      } else {
        updateStatus("timed_capture_request_failed");
        resetTimedCaptureState(true);
      }
    }
  } else if (gCaptureButtonLatched && captureState == HIGH && now - gLastCaptureButtonTick >= kButtonDebounceMs) {
    if (!gCaptureLongPressHandled) {
      updateStatus("capture_button_pressed");
      queueCaptureAndSend();
    }
    gCaptureButtonLatched = false;
    gCaptureLongPressHandled = false;
  }

  bool analyzeState = digitalRead(kAnalyzeButtonPin);
  if (analyzeState != gLastAnalyzeButtonState) {
    gLastAnalyzeButtonTick = now;
    gLastAnalyzeButtonState = analyzeState;
  }
  if (!gAnalyzeButtonLatched && analyzeState == LOW && now - gLastAnalyzeButtonTick >= kButtonDebounceMs) {
    gAnalyzeButtonLatched = true;
    updateStatus("analyze_button_pressed");
    queueAnalyze();
  } else if (gAnalyzeButtonLatched && analyzeState == HIGH && now - gLastAnalyzeButtonTick >= kButtonDebounceMs) {
    gAnalyzeButtonLatched = false;
  }
}

static void maintainWifiLink() {
  const uint32_t now = millis();
  const wl_status_t staStatus = WiFi.status();
  const bool connected = isStaReady();
  const bool userActionPending =
    gCaptureRequested || gSendRequested || gAnalyzeRequested ||
    gCaptureButtonLatched || gAnalyzeButtonLatched ||
    gTimedModeAwaitingAck || gTimedModeActive || gTimedUploadPending;

  if (!connected && (!gApReady || WiFi.softAPIP()[0] == 0)) {
    gApReady = WiFi.softAP(kApSsid, kApPassword, 1, 0, 4);
    if (gApReady) {
      Serial.print("SoftAP recovered: ");
      Serial.println(WiFi.softAPIP());
    }
  }

    if (connected != gStaConnected) {
      gStaConnected = connected;
      if (connected) {
        gStaConnectInProgress = false;
        gPhoneServerRegistered = false;
        gLastRegisterSuccessMs = 0;
        if (gApReady) {
          WiFi.softAPdisconnect(true);
          gApReady = false;
          Serial.println("SoftAP paused while STA main path is active.");
        }
        updateStatus("sta_connected");
        Serial.print("STA IP: ");
        Serial.println(WiFi.localIP());
      } else {
        gPhoneServerRegistered = false;
        gLastRegisterSuccessMs = 0;
        updateStatus("sta_disconnected");
      }
    }

    if (!connected) {
      if (!gApReady) {
        gApReady = WiFi.softAP(kApSsid, kApPassword, 1, 0, 4);
        if (gApReady) {
          Serial.print("SoftAP restored: ");
          Serial.println(WiFi.softAPIP());
        }
      }
      if (strlen(kStaSsid) > 0) {
        if (!gStaConnectInProgress &&
            (staStatus == WL_DISCONNECTED || staStatus == WL_NO_SSID_AVAIL || staStatus == WL_CONNECT_FAILED) &&
            now - gLastStaReconnectAttemptMs >= kStaReconnectIntervalMs) {
          startStaConnection(false);
      } else if (gStaConnectInProgress && now - gStaConnectStartedMs >= kStaConnectTimeoutMs) {
        Serial.println("STA connect timeout, retrying...");
        startStaConnection(true);
      }
    }
    return;
  }

  if (userActionPending || gCameraReady || now - gLastCameraActivityMs < 2000) {
    return;
  }

    if (!gPhoneServerRegistered || now - gLastRegisterAttemptMs >= kRegisterIntervalMs) {
      gLastRegisterAttemptMs = now;
      gPhoneServerRegistered = registerToPhoneServer();
      if (gPhoneServerRegistered) {
        gLastRegisterSuccessMs = millis();
        updateStatus("phone_server_ready");
      }
    }
  }

static void processPendingActions() {
  if (gCaptureRequested) {
    gCaptureRequested = false;
    if (ensureCameraReady()) {
      updateStatus("capturing");
      cacheLatestFrame();
    }
  }

  if (gSendRequested) {
    gSendRequested = false;
    if (ensureFrameReady()) {
      updateStatus("prepare_send");
      if (!sendFrameToPhoneWifi()) {
        notifyFrameToPhone();
      }
    }
  }

  if (gAnalyzeRequested) {
    gAnalyzeRequested = false;
    bool handled = false;

    if (gPhoneServerRegistered || isStaReady()) {
      updateStatus("sending_analyze_request");
      handled = requestAnalyzeOnPhone();
      if (handled) {
        gPhoneServerRegistered = true;
        updateStatus("analyze_request_sent");
      }
    }

    if (!handled && gBleConnected) {
      updateStatus("analyze_requested");
      handled = true;
    }

    if (!handled) {
      updateStatus("analyze_request_failed");
    }
  }
}

static void shutdownCameraIfIdle() {
  if (!gCameraReady) {
    return;
  }
  if (gCaptureRequested || gSendRequested || gAnalyzeRequested) {
    return;
  }

  const uint32_t now = millis();
  if (now - gLastCameraActivityMs < kCameraIdleShutdownMs) {
    return;
  }

  deinitCamera();
}

void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(true);
  Serial.println();
  Serial.println("BandBridgeCam booting...");
  Serial.printf("Board: %s\n", kBoardName);

  gFrameMutex = xSemaphoreCreateMutex();
  initButtons();
  initBle();
  initWifi();
  startPreviewServer();
  updateStatus("camera_sleep");
}

void loop() {
  pollButtons();
  processPendingActions();
  processTimedCaptureSchedule();
  processTimedUploadQueue();
  maintainWifiLink();
  shutdownCameraIfIdle();
  delay(15);
}
