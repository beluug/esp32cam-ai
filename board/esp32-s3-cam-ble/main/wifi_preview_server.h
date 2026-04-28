#pragma once

#include <stdbool.h>
#include "esp_err.h"

typedef void (*wifi_preview_command_handler_t)(const char *command);

void wifi_preview_server_set_command_handler(wifi_preview_command_handler_t handler);
esp_err_t wifi_preview_server_start(void);
const char *wifi_preview_server_get_ap_ip(void);
const char *wifi_preview_server_get_sta_ip(void);
bool wifi_preview_server_sta_connected(void);
