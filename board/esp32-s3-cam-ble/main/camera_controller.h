#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "esp_camera.h"
#include "esp_err.h"

typedef struct {
    bool valid;
    uint32_t frame_id;
    size_t jpeg_size;
    uint16_t width;
    uint16_t height;
    int64_t captured_at_ms;
} cached_frame_info_t;

esp_err_t camera_controller_init(void);
esp_err_t camera_controller_capture_and_cache(uint32_t *out_frame_id);
esp_err_t camera_controller_acquire_frame(camera_fb_t **out_fb);
void camera_controller_release_frame(camera_fb_t *fb);
bool camera_controller_has_cached_frame(void);
esp_err_t camera_controller_get_cached_info(cached_frame_info_t *out_info);
esp_err_t camera_controller_copy_cached_frame(
    uint8_t **out_data,
    size_t *out_len,
    uint16_t *out_width,
    uint16_t *out_height,
    uint32_t *out_frame_id
);
