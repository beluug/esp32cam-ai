#include "image_chunker.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "ble_camera_protocol.h"
#include "ble_camera_service.h"

esp_err_t image_chunker_send_jpeg(
    const uint8_t *jpeg,
    size_t jpeg_len,
    uint16_t width,
    uint16_t height,
    uint32_t frame_id
) {
    if (jpeg == NULL || jpeg_len == 0) {
        return ESP_ERR_INVALID_ARG;
    }

    frame_meta_t meta = {
        .frame_id = frame_id,
        .total_size = (uint32_t)jpeg_len,
        .chunk_size = CAMERA_DEFAULT_CHUNK,
        .width = width,
        .height = height,
    };

    ble_camera_service_publish_meta(&meta);
    ble_camera_service_update_status("sending_chunks");

    size_t offset = 0;
    while (offset < jpeg_len) {
        const size_t remain = jpeg_len - offset;
        const size_t chunk_len = remain > CAMERA_DEFAULT_CHUNK ? CAMERA_DEFAULT_CHUNK : remain;
        esp_err_t err = ble_camera_service_notify_chunk(jpeg + offset, chunk_len);
        if (err != ESP_OK) {
            return err;
        }
        offset += chunk_len;
        vTaskDelay(pdMS_TO_TICKS(8));
    }

    ble_camera_service_update_status("frame_sent");
    return ESP_OK;
}
