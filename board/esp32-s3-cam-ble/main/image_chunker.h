#pragma once

#include <stddef.h>
#include <stdint.h>
#include "esp_err.h"

typedef struct {
    uint32_t frame_id;
    uint32_t total_size;
    uint16_t chunk_size;
    uint16_t width;
    uint16_t height;
} frame_meta_t;

esp_err_t image_chunker_send_jpeg(
    const uint8_t *jpeg,
    size_t jpeg_len,
    uint16_t width,
    uint16_t height,
    uint32_t frame_id
);
