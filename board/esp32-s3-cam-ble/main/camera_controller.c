#include "camera_controller.h"

#include <string.h>
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "camera_board_pins.h"

static const char *TAG = "camera_controller";

static SemaphoreHandle_t s_camera_mutex;
static SemaphoreHandle_t s_cache_mutex;
static uint8_t *s_cached_data;
static size_t s_cached_len;
static uint16_t s_cached_width;
static uint16_t s_cached_height;
static uint32_t s_cached_frame_id;
static int64_t s_cached_at_ms;
static uint32_t s_next_frame_id = 1;
static bool s_ready;

static uint8_t *alloc_frame_buffer(size_t len) {
    uint8_t *buffer = (uint8_t *)heap_caps_malloc(len, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (buffer == NULL) {
        buffer = (uint8_t *)heap_caps_malloc(len, MALLOC_CAP_8BIT);
    }
    return buffer;
}

static void free_cached_frame_locked(void) {
    if (s_cached_data != NULL) {
        heap_caps_free(s_cached_data);
        s_cached_data = NULL;
    }
    s_cached_len = 0;
    s_cached_width = 0;
    s_cached_height = 0;
    s_cached_frame_id = 0;
    s_cached_at_ms = 0;
}

static void apply_sensor_defaults(void) {
    sensor_t *sensor = esp_camera_sensor_get();
    if (sensor == NULL) {
        return;
    }

    sensor->set_vflip(sensor, 1);
    sensor->set_brightness(sensor, 1);
    sensor->set_saturation(sensor, 0);
    sensor->set_contrast(sensor, 1);
    sensor->set_sharpness(sensor, 2);
    sensor->set_whitebal(sensor, 1);
    sensor->set_gain_ctrl(sensor, 1);
    sensor->set_exposure_ctrl(sensor, 1);
    sensor->set_hmirror(sensor, 0);
    sensor->set_quality(sensor, CAMERA_DEFAULT_JPEG_QUALITY);
    sensor->set_framesize(sensor, CAMERA_DEFAULT_FRAME_SIZE);
}

esp_err_t camera_controller_init(void) {
    if (s_ready) {
        return ESP_OK;
    }

    s_camera_mutex = xSemaphoreCreateMutex();
    s_cache_mutex = xSemaphoreCreateMutex();
    if (s_camera_mutex == NULL || s_cache_mutex == NULL) {
        ESP_LOGE(TAG, "failed to create camera mutexes");
        return ESP_ERR_NO_MEM;
    }

    camera_config_t config = build_camera_config();
    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_camera_init failed: %s", esp_err_to_name(err));
        return err;
    }

    apply_sensor_defaults();
    s_ready = true;
    ESP_LOGI(TAG, "camera initialized for %s", BOARD_NAME);
    return ESP_OK;
}

esp_err_t camera_controller_acquire_frame(camera_fb_t **out_fb) {
    if (!s_ready) {
        return ESP_ERR_INVALID_STATE;
    }
    if (out_fb == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (xSemaphoreTake(s_camera_mutex, pdMS_TO_TICKS(3000)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    camera_fb_t *fb = esp_camera_fb_get();
    if (fb == NULL) {
        xSemaphoreGive(s_camera_mutex);
        return ESP_FAIL;
    }

    *out_fb = fb;
    return ESP_OK;
}

void camera_controller_release_frame(camera_fb_t *fb) {
    if (fb != NULL) {
        esp_camera_fb_return(fb);
    }
    if (s_camera_mutex != NULL) {
        xSemaphoreGive(s_camera_mutex);
    }
}

esp_err_t camera_controller_capture_and_cache(uint32_t *out_frame_id) {
    camera_fb_t *fb = NULL;
    esp_err_t err = camera_controller_acquire_frame(&fb);
    if (err != ESP_OK) {
        return err;
    }

    uint8_t *copy = alloc_frame_buffer(fb->len);
    if (copy == NULL) {
        camera_controller_release_frame(fb);
        return ESP_ERR_NO_MEM;
    }

    memcpy(copy, fb->buf, fb->len);
    const size_t len = fb->len;
    const uint16_t width = fb->width;
    const uint16_t height = fb->height;
    camera_controller_release_frame(fb);

    if (xSemaphoreTake(s_cache_mutex, pdMS_TO_TICKS(1000)) != pdTRUE) {
        heap_caps_free(copy);
        return ESP_ERR_TIMEOUT;
    }

    free_cached_frame_locked();
    s_cached_data = copy;
    s_cached_len = len;
    s_cached_width = width;
    s_cached_height = height;
    s_cached_frame_id = s_next_frame_id++;
    s_cached_at_ms = esp_timer_get_time() / 1000;

    if (out_frame_id != NULL) {
        *out_frame_id = s_cached_frame_id;
    }

    xSemaphoreGive(s_cache_mutex);
    return ESP_OK;
}

bool camera_controller_has_cached_frame(void) {
    bool valid = false;
    if (s_cache_mutex != NULL && xSemaphoreTake(s_cache_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
        valid = (s_cached_data != NULL && s_cached_len > 0);
        xSemaphoreGive(s_cache_mutex);
    }
    return valid;
}

esp_err_t camera_controller_get_cached_info(cached_frame_info_t *out_info) {
    if (out_info == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    memset(out_info, 0, sizeof(*out_info));

    if (xSemaphoreTake(s_cache_mutex, pdMS_TO_TICKS(1000)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    out_info->valid = (s_cached_data != NULL && s_cached_len > 0);
    out_info->frame_id = s_cached_frame_id;
    out_info->jpeg_size = s_cached_len;
    out_info->width = s_cached_width;
    out_info->height = s_cached_height;
    out_info->captured_at_ms = s_cached_at_ms;

    xSemaphoreGive(s_cache_mutex);
    return ESP_OK;
}

esp_err_t camera_controller_copy_cached_frame(
    uint8_t **out_data,
    size_t *out_len,
    uint16_t *out_width,
    uint16_t *out_height,
    uint32_t *out_frame_id
) {
    if (out_data == NULL || out_len == NULL || out_width == NULL || out_height == NULL || out_frame_id == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    *out_data = NULL;
    *out_len = 0;
    *out_width = 0;
    *out_height = 0;
    *out_frame_id = 0;

    if (xSemaphoreTake(s_cache_mutex, pdMS_TO_TICKS(1000)) != pdTRUE) {
        return ESP_ERR_TIMEOUT;
    }

    if (s_cached_data == NULL || s_cached_len == 0) {
        xSemaphoreGive(s_cache_mutex);
        return ESP_ERR_NOT_FOUND;
    }

    uint8_t *copy = alloc_frame_buffer(s_cached_len);
    if (copy == NULL) {
        xSemaphoreGive(s_cache_mutex);
        return ESP_ERR_NO_MEM;
    }

    memcpy(copy, s_cached_data, s_cached_len);
    *out_data = copy;
    *out_len = s_cached_len;
    *out_width = s_cached_width;
    *out_height = s_cached_height;
    *out_frame_id = s_cached_frame_id;

    xSemaphoreGive(s_cache_mutex);
    return ESP_OK;
}
