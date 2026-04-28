#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"
#include "image_chunker.h"

esp_err_t ble_camera_service_init(void);
void ble_camera_service_update_status(const char *status_text);
void ble_camera_service_publish_meta(const frame_meta_t *meta);
esp_err_t ble_camera_service_notify_chunk(const uint8_t *data, size_t len);
bool ble_camera_service_should_capture(void);
bool ble_camera_service_should_send_last(void);
bool ble_camera_service_is_connected(void);
const char *ble_camera_service_get_status_text(void);
