#pragma once

#include "esp_camera.h"

#define BOARD_NAME                      "Freenove ESP32-S3 WROOM"

#define CAMERA_PIN_PWDN                 -1
#define CAMERA_PIN_RESET                -1
#define CAMERA_PIN_XCLK                 15
#define CAMERA_PIN_SIOD                 4
#define CAMERA_PIN_SIOC                 5

#define CAMERA_PIN_D7                   16
#define CAMERA_PIN_D6                   17
#define CAMERA_PIN_D5                   18
#define CAMERA_PIN_D4                   12
#define CAMERA_PIN_D3                   10
#define CAMERA_PIN_D2                   8
#define CAMERA_PIN_D1                   9
#define CAMERA_PIN_D0                   11
#define CAMERA_PIN_VSYNC                6
#define CAMERA_PIN_HREF                 7
#define CAMERA_PIN_PCLK                 13

#define BUTTON_CAPTURE_GPIO             14
#define BUTTON_ANALYZE_GPIO             21

#define WIFI_AP_SSID                    "BandBridgeCam"
#define WIFI_AP_PASSWORD                "12345678"
#define WIFI_STA_SSID                   ""
#define WIFI_STA_PASSWORD               ""

#define CAMERA_DEFAULT_FRAME_SIZE       FRAMESIZE_XGA
#define CAMERA_DEFAULT_JPEG_QUALITY     12

static inline camera_config_t build_camera_config(void) {
    camera_config_t config = {
        .pin_pwdn = CAMERA_PIN_PWDN,
        .pin_reset = CAMERA_PIN_RESET,
        .pin_xclk = CAMERA_PIN_XCLK,
        .pin_sccb_sda = CAMERA_PIN_SIOD,
        .pin_sccb_scl = CAMERA_PIN_SIOC,
        .pin_d7 = CAMERA_PIN_D7,
        .pin_d6 = CAMERA_PIN_D6,
        .pin_d5 = CAMERA_PIN_D5,
        .pin_d4 = CAMERA_PIN_D4,
        .pin_d3 = CAMERA_PIN_D3,
        .pin_d2 = CAMERA_PIN_D2,
        .pin_d1 = CAMERA_PIN_D1,
        .pin_d0 = CAMERA_PIN_D0,
        .pin_vsync = CAMERA_PIN_VSYNC,
        .pin_href = CAMERA_PIN_HREF,
        .pin_pclk = CAMERA_PIN_PCLK,
        .xclk_freq_hz = 20000000,
        .ledc_timer = LEDC_TIMER_0,
        .ledc_channel = LEDC_CHANNEL_0,
        .pixel_format = PIXFORMAT_JPEG,
        .frame_size = CAMERA_DEFAULT_FRAME_SIZE,
        .jpeg_quality = CAMERA_DEFAULT_JPEG_QUALITY,
        .fb_count = 2,
        .grab_mode = CAMERA_GRAB_LATEST,
        .fb_location = CAMERA_FB_IN_PSRAM
    };
    return config;
}
