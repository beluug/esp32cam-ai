#include "wifi_preview_server.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "esp_event.h"
#include "esp_heap_caps.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "lwip/inet.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "camera_board_pins.h"
#include "camera_controller.h"
#include "ble_camera_service.h"

static const char *TAG = "wifi_preview";

static httpd_handle_t s_httpd;
static httpd_handle_t s_stream_httpd;
static esp_netif_t *s_ap_netif;
static esp_netif_t *s_sta_netif;
static wifi_preview_command_handler_t s_command_handler;
static bool s_sta_connected;
static char s_ap_ip[16] = "0.0.0.0";
static char s_sta_ip[16] = "0.0.0.0";

static const char *INDEX_HTML =
    "<!doctype html><html><head><meta charset='utf-8'><title>BandBridgeCam</title>"
    "<style>body{font-family:sans-serif;background:#111;color:#eee;padding:16px}"
    "button{margin:4px 8px 12px 0;padding:10px 16px;border:0;border-radius:8px}"
    ".card{background:#1d1d1d;padding:16px;border-radius:12px;margin-bottom:16px}"
    "img{max-width:100%;border-radius:12px;background:#222}</style></head><body>"
    "<h1>BandBridgeCam</h1>"
    "<div class='card'><p>Wi-Fi preview is active. Use the buttons below to trigger capture or BLE send.</p>"
    "<button onclick=\"fetch('/trigger?action=capture')\">Capture</button>"
    "<button onclick=\"fetch('/trigger?action=send')\">Send To Phone</button>"
    "<button onclick=\"window.open('/stream','_blank')\">Open Stream</button></div>"
    "<div class='card'><h3>Latest snapshot</h3><img id='shot' src='/capture'></div>"
    "<div class='card'><h3>Status</h3><pre id='status'>loading...</pre></div>"
    "<script>"
    "const shot=document.getElementById('shot');"
    "const status=document.getElementById('status');"
    "setInterval(()=>{shot.src='/capture?ts='+Date.now();},1500);"
    "setInterval(async()=>{status.textContent=await (await fetch('/status')).text();},1200);"
    "</script></body></html>";

static void update_ip_string(char *dst, size_t dst_len, esp_ip4_addr_t addr) {
    snprintf(dst, dst_len, IPSTR, IP2STR(&addr));
}

static void wifi_event_handler(void *arg, esp_event_base_t event_base, int32_t event_id, void *event_data) {
    (void)arg;

    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        s_sta_connected = false;
        snprintf(s_sta_ip, sizeof(s_sta_ip), "0.0.0.0");
        if (strlen(WIFI_STA_SSID) > 0) {
            esp_wifi_connect();
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        const ip_event_got_ip_t *event = (const ip_event_got_ip_t *)event_data;
        s_sta_connected = true;
        update_ip_string(s_sta_ip, sizeof(s_sta_ip), event->ip_info.ip);
    }
}

static esp_err_t index_handler(httpd_req_t *req) {
    httpd_resp_set_type(req, "text/html");
    return httpd_resp_send(req, INDEX_HTML, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t status_handler(httpd_req_t *req) {
    cached_frame_info_t info;
    camera_controller_get_cached_info(&info);

    char json[512];
    snprintf(
        json,
        sizeof(json),
        "{\n"
        "  \"board\": \"%s\",\n"
        "  \"apIp\": \"%s\",\n"
        "  \"staIp\": \"%s\",\n"
        "  \"staConnected\": %s,\n"
        "  \"bleConnected\": %s,\n"
        "  \"status\": \"%s\",\n"
        "  \"captureButton\": %d,\n"
        "  \"analyzeButton\": %d,\n"
        "  \"cachedFrameValid\": %s,\n"
        "  \"cachedFrameId\": %lu,\n"
        "  \"cachedFrameSize\": %u,\n"
        "  \"cachedFrameWidth\": %u,\n"
        "  \"cachedFrameHeight\": %u\n"
        "}\n",
        BOARD_NAME,
        s_ap_ip,
        s_sta_ip,
        s_sta_connected ? "true" : "false",
        ble_camera_service_is_connected() ? "true" : "false",
        ble_camera_service_get_status_text(),
        BUTTON_CAPTURE_GPIO,
        BUTTON_ANALYZE_GPIO,
        info.valid ? "true" : "false",
        (unsigned long)info.frame_id,
        (unsigned int)info.jpeg_size,
        (unsigned int)info.width,
        (unsigned int)info.height
    );

    httpd_resp_set_type(req, "application/json");
    return httpd_resp_send(req, json, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t capture_handler(httpd_req_t *req) {
    camera_fb_t *fb = NULL;
    esp_err_t err = camera_controller_acquire_frame(&fb);
    if (err != ESP_OK || fb == NULL) {
        httpd_resp_send_500(req);
        return ESP_FAIL;
    }

    httpd_resp_set_type(req, "image/jpeg");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
    camera_controller_release_frame(fb);
    return res;
}

static esp_err_t cached_handler(httpd_req_t *req) {
    uint8_t *jpeg = NULL;
    size_t len = 0;
    uint16_t width = 0;
    uint16_t height = 0;
    uint32_t frame_id = 0;
    esp_err_t err = camera_controller_copy_cached_frame(&jpeg, &len, &width, &height, &frame_id);
    if (err != ESP_OK || jpeg == NULL) {
        httpd_resp_send_404(req);
        return ESP_FAIL;
    }

    httpd_resp_set_type(req, "image/jpeg");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    esp_err_t res = httpd_resp_send(req, (const char *)jpeg, len);
    heap_caps_free(jpeg);
    return res;
}

static esp_err_t trigger_handler(httpd_req_t *req) {
    char query[64] = {0};
    char action[16] = {0};

    if (httpd_req_get_url_query_len(req) > 0) {
        httpd_req_get_url_query_str(req, query, sizeof(query));
        httpd_query_key_value(query, "action", action, sizeof(action));
    }

    if (s_command_handler != NULL) {
        if (strcmp(action, "capture") == 0) {
            s_command_handler("capture");
        } else if (strcmp(action, "send") == 0) {
            s_command_handler("send");
        }
    }

    httpd_resp_set_type(req, "application/json");
    return httpd_resp_sendstr(req, "{\"ok\":true}");
}

static esp_err_t stream_handler(httpd_req_t *req) {
    static const char *stream_content_type = "multipart/x-mixed-replace;boundary=frame";
    static const char *stream_boundary = "\r\n--frame\r\n";
    static const char *stream_part = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

    httpd_resp_set_type(req, stream_content_type);
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");

    while (true) {
        camera_fb_t *fb = NULL;
        esp_err_t err = camera_controller_acquire_frame(&fb);
        if (err != ESP_OK || fb == NULL) {
            return ESP_FAIL;
        }

        char header[64];
        const int header_len = snprintf(header, sizeof(header), stream_part, (unsigned int)fb->len);
        if (httpd_resp_send_chunk(req, stream_boundary, strlen(stream_boundary)) != ESP_OK ||
            httpd_resp_send_chunk(req, header, header_len) != ESP_OK ||
            httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len) != ESP_OK) {
            camera_controller_release_frame(fb);
            break;
        }

        camera_controller_release_frame(fb);
        vTaskDelay(pdMS_TO_TICKS(120));
    }

    return ESP_OK;
}

static esp_err_t start_http_servers(void) {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.max_uri_handlers = 8;

    httpd_uri_t index_uri = {.uri = "/", .method = HTTP_GET, .handler = index_handler, .user_ctx = NULL};
    httpd_uri_t status_uri = {.uri = "/status", .method = HTTP_GET, .handler = status_handler, .user_ctx = NULL};
    httpd_uri_t capture_uri = {.uri = "/capture", .method = HTTP_GET, .handler = capture_handler, .user_ctx = NULL};
    httpd_uri_t cached_uri = {.uri = "/last.jpg", .method = HTTP_GET, .handler = cached_handler, .user_ctx = NULL};
    httpd_uri_t trigger_uri = {.uri = "/trigger", .method = HTTP_GET, .handler = trigger_handler, .user_ctx = NULL};
    httpd_uri_t stream_uri = {.uri = "/stream", .method = HTTP_GET, .handler = stream_handler, .user_ctx = NULL};

    if (httpd_start(&s_httpd, &config) != ESP_OK) {
        return ESP_FAIL;
    }
    httpd_register_uri_handler(s_httpd, &index_uri);
    httpd_register_uri_handler(s_httpd, &status_uri);
    httpd_register_uri_handler(s_httpd, &capture_uri);
    httpd_register_uri_handler(s_httpd, &cached_uri);
    httpd_register_uri_handler(s_httpd, &trigger_uri);

    config.server_port += 1;
    config.ctrl_port += 1;
    if (httpd_start(&s_stream_httpd, &config) == ESP_OK) {
        httpd_register_uri_handler(s_stream_httpd, &stream_uri);
    }

    return ESP_OK;
}

void wifi_preview_server_set_command_handler(wifi_preview_command_handler_t handler) {
    s_command_handler = handler;
}

esp_err_t wifi_preview_server_start(void) {
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    s_ap_netif = esp_netif_create_default_wifi_ap();
    s_sta_netif = esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &wifi_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &wifi_event_handler, NULL));

    wifi_config_t ap_config = {0};
    snprintf((char *)ap_config.ap.ssid, sizeof(ap_config.ap.ssid), "%s", WIFI_AP_SSID);
    snprintf((char *)ap_config.ap.password, sizeof(ap_config.ap.password), "%s", WIFI_AP_PASSWORD);
    ap_config.ap.ssid_len = strlen(WIFI_AP_SSID);
    ap_config.ap.max_connection = 4;
    ap_config.ap.authmode = strlen(WIFI_AP_PASSWORD) >= 8 ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;

    const bool has_sta = strlen(WIFI_STA_SSID) > 0;
    if (has_sta) {
        wifi_config_t sta_config = {0};
        snprintf((char *)sta_config.sta.ssid, sizeof(sta_config.sta.ssid), "%s", WIFI_STA_SSID);
        snprintf((char *)sta_config.sta.password, sizeof(sta_config.sta.password), "%s", WIFI_STA_PASSWORD);
        sta_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
        sta_config.sta.pmf_cfg.capable = true;
        sta_config.sta.pmf_cfg.required = false;

        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_APSTA));
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &sta_config));
    } else {
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap_config));
    }

    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));

    if (has_sta) {
        ESP_ERROR_CHECK(esp_wifi_connect());
    }

    esp_netif_ip_info_t ip_info;
    if (esp_netif_get_ip_info(s_ap_netif, &ip_info) == ESP_OK) {
        update_ip_string(s_ap_ip, sizeof(s_ap_ip), ip_info.ip);
    }

    ESP_LOGI(TAG, "softAP ready: ssid=%s ip=%s", WIFI_AP_SSID, s_ap_ip);
    return start_http_servers();
}

const char *wifi_preview_server_get_ap_ip(void) {
    return s_ap_ip;
}

const char *wifi_preview_server_get_sta_ip(void) {
    return s_sta_ip;
}

bool wifi_preview_server_sta_connected(void) {
    return s_sta_connected;
}
