#include <stdlib.h>
#include <string.h>
#include "driver/gpio.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "camera_board_pins.h"
#include "camera_controller.h"
#include "ble_camera_service.h"
#include "image_chunker.h"
#include "wifi_preview_server.h"

static const char *TAG = "app_main";

typedef enum {
    ACTION_CAPTURE = 1,
    ACTION_SEND_TO_PHONE = 2,
} camera_action_t;

static QueueHandle_t s_action_queue;

static void enqueue_action(camera_action_t action) {
    if (s_action_queue != NULL) {
        xQueueSend(s_action_queue, &action, 0);
    }
}

static void wifi_command_handler(const char *command) {
    if (command == NULL) {
        return;
    }
    if (strcmp(command, "capture") == 0) {
        enqueue_action(ACTION_CAPTURE);
    } else if (strcmp(command, "send") == 0) {
        enqueue_action(ACTION_SEND_TO_PHONE);
    }
}

static void button_task(void *arg) {
    (void)arg;
    bool capture_armed = true;
    bool analyze_armed = true;

    while (1) {
        const bool capture_pressed = gpio_get_level(BUTTON_CAPTURE_GPIO) == 0;
        const bool analyze_pressed = gpio_get_level(BUTTON_ANALYZE_GPIO) == 0;

        if (capture_pressed && capture_armed) {
            capture_armed = false;
            ble_camera_service_update_status("button_capture_pressed");
            enqueue_action(ACTION_CAPTURE);
        } else if (!capture_pressed) {
            capture_armed = true;
        }

        if (analyze_pressed && analyze_armed) {
            analyze_armed = false;
            ble_camera_service_update_status("button_analyze_pressed");
            enqueue_action(ACTION_SEND_TO_PHONE);
        } else if (!analyze_pressed) {
            analyze_armed = true;
        }

        vTaskDelay(pdMS_TO_TICKS(30));
    }
}

static esp_err_t init_buttons(void) {
    gpio_config_t config = {
        .pin_bit_mask = (1ULL << BUTTON_CAPTURE_GPIO) | (1ULL << BUTTON_ANALYZE_GPIO),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    return gpio_config(&config);
}

static esp_err_t send_cached_frame_to_phone(void) {
    if (!ble_camera_service_is_connected()) {
        ble_camera_service_update_status("ble_not_connected");
        return ESP_ERR_INVALID_STATE;
    }

    if (!camera_controller_has_cached_frame()) {
        uint32_t frame_id = 0;
        esp_err_t capture_err = camera_controller_capture_and_cache(&frame_id);
        if (capture_err != ESP_OK) {
            ble_camera_service_update_status("capture_failed");
            return capture_err;
        }
    }

    uint8_t *jpeg = NULL;
    size_t jpeg_len = 0;
    uint16_t width = 0;
    uint16_t height = 0;
    uint32_t frame_id = 0;
    esp_err_t err = camera_controller_copy_cached_frame(&jpeg, &jpeg_len, &width, &height, &frame_id);
    if (err != ESP_OK) {
        ble_camera_service_update_status("cache_missing");
        return err;
    }

    ble_camera_service_update_status("sending_frame");
    err = image_chunker_send_jpeg(jpeg, jpeg_len, width, height, frame_id);
    heap_caps_free(jpeg);
    return err;
}

static void process_action(camera_action_t action) {
    if (action == ACTION_CAPTURE) {
        ble_camera_service_update_status("capturing");
        uint32_t frame_id = 0;
        esp_err_t err = camera_controller_capture_and_cache(&frame_id);
        if (err == ESP_OK) {
            ble_camera_service_update_status("capture_ready");
            ESP_LOGI(TAG, "capture complete frame_id=%lu", (unsigned long)frame_id);
        } else {
            ble_camera_service_update_status("capture_failed");
            ESP_LOGE(TAG, "capture failed: %s", esp_err_to_name(err));
        }
        return;
    }

    if (action == ACTION_SEND_TO_PHONE) {
        esp_err_t err = send_cached_frame_to_phone();
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "send failed: %s", esp_err_to_name(err));
        }
    }
}

void app_main(void) {
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(camera_controller_init());
    ESP_ERROR_CHECK(init_buttons());
    ESP_ERROR_CHECK(ble_camera_service_init());

    s_action_queue = xQueueCreate(8, sizeof(camera_action_t));
    if (s_action_queue == NULL) {
        ESP_LOGE(TAG, "failed to create action queue");
        return;
    }

    wifi_preview_server_set_command_handler(wifi_command_handler);
    ESP_ERROR_CHECK(wifi_preview_server_start());

    xTaskCreate(button_task, "button_task", 4096, NULL, 5, NULL);

    ble_camera_service_update_status("camera_ready");
    ESP_LOGI(
        TAG,
        "board ready ap=%s capture_button=%d analyze_button=%d",
        wifi_preview_server_get_ap_ip(),
        BUTTON_CAPTURE_GPIO,
        BUTTON_ANALYZE_GPIO
    );

    while (1) {
        if (ble_camera_service_should_capture()) {
            enqueue_action(ACTION_CAPTURE);
        }
        if (ble_camera_service_should_send_last()) {
            enqueue_action(ACTION_SEND_TO_PHONE);
        }

        camera_action_t action;
        if (xQueueReceive(s_action_queue, &action, pdMS_TO_TICKS(20)) == pdTRUE) {
            process_action(action);
        }
    }
}
