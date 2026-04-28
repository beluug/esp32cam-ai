#include "ble_camera_service.h"

#include <inttypes.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include "esp_bt.h"
#include "esp_log.h"
#include "esp_nimble_hci.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "os/os_mbuf.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"
#include "ble_camera_protocol.h"

static const char *TAG = "ble_camera_service";

static volatile bool s_capture_requested;
static volatile bool s_send_last_requested;
static volatile bool s_connected;
static uint8_t s_own_addr_type;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_status_val_handle;
static uint16_t s_meta_val_handle;
static uint16_t s_chunk_val_handle;
static char s_status_text[64] = "booting";
static char s_meta_text[160] = "{}";

static const ble_uuid128_t g_service_uuid =
    BLE_UUID128_INIT(0x00, 0x00, 0xA1, 0xC9, 0x7B, 0x5E, 0x72, 0x9F, 0x44, 0x4D, 0x2A, 0x4B, 0x00, 0x10, 0x8A, 0x7D);
static const ble_uuid128_t g_control_uuid =
    BLE_UUID128_INIT(0x00, 0x00, 0xA1, 0xC9, 0x7B, 0x5E, 0x72, 0x9F, 0x44, 0x4D, 0x2A, 0x4B, 0x01, 0x10, 0x8A, 0x7D);
static const ble_uuid128_t g_status_uuid =
    BLE_UUID128_INIT(0x00, 0x00, 0xA1, 0xC9, 0x7B, 0x5E, 0x72, 0x9F, 0x44, 0x4D, 0x2A, 0x4B, 0x02, 0x10, 0x8A, 0x7D);
static const ble_uuid128_t g_meta_uuid =
    BLE_UUID128_INIT(0x00, 0x00, 0xA1, 0xC9, 0x7B, 0x5E, 0x72, 0x9F, 0x44, 0x4D, 0x2A, 0x4B, 0x03, 0x10, 0x8A, 0x7D);
static const ble_uuid128_t g_chunk_uuid =
    BLE_UUID128_INIT(0x00, 0x00, 0xA1, 0xC9, 0x7B, 0x5E, 0x72, 0x9F, 0x44, 0x4D, 0x2A, 0x4B, 0x04, 0x10, 0x8A, 0x7D);

static int ble_camera_access_cb(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg);
static int ble_camera_gap_event(struct ble_gap_event *event, void *arg);
static void ble_camera_advertise(void);

static const struct ble_gatt_svc_def g_services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &g_service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &g_control_uuid.u,
                .access_cb = ble_camera_access_cb,
                .arg = (void *)1,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &g_status_uuid.u,
                .access_cb = ble_camera_access_cb,
                .arg = (void *)2,
                .val_handle = &s_status_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
            },
            {
                .uuid = &g_meta_uuid.u,
                .access_cb = ble_camera_access_cb,
                .arg = (void *)3,
                .val_handle = &s_meta_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
            },
            {
                .uuid = &g_chunk_uuid.u,
                .access_cb = ble_camera_access_cb,
                .arg = (void *)4,
                .val_handle = &s_chunk_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
            },
            {0}
        }
    },
    {0}
};

static void ble_camera_host_task(void *param) {
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static void ble_camera_on_sync(void) {
    int rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "ble_hs_id_infer_auto failed: %d", rc);
        return;
    }
    ble_camera_advertise();
}

static void ble_camera_advertise(void) {
    struct ble_gap_adv_params adv_params = {0};
    struct ble_hs_adv_fields fields = {0};

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)"BandBridgeCam";
    fields.name_len = strlen((const char *)fields.name);
    fields.name_is_complete = 1;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;
    fields.uuids128 = (ble_uuid128_t *)&g_service_uuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    ble_gap_adv_set_fields(&fields);
    ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &adv_params, ble_camera_gap_event, NULL);
}

static int ble_camera_gap_event(struct ble_gap_event *event, void *arg) {
    (void)arg;

    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status == 0) {
                s_connected = true;
                s_conn_handle = event->connect.conn_handle;
                ble_camera_service_update_status("ble_connected");
            } else {
                ble_camera_advertise();
            }
            return 0;

        case BLE_GAP_EVENT_DISCONNECT:
            s_connected = false;
            s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
            snprintf(s_status_text, sizeof(s_status_text), "ble_disconnected");
            ble_camera_advertise();
            return 0;

        case BLE_GAP_EVENT_SUBSCRIBE:
            ESP_LOGI(TAG, "subscribe attr=%u notify=%u", event->subscribe.attr_handle, event->subscribe.cur_notify);
            return 0;

        case BLE_GAP_EVENT_MTU:
            ESP_LOGI(TAG, "mtu=%u", event->mtu.value);
            return 0;

        case BLE_GAP_EVENT_ADV_COMPLETE:
            ble_camera_advertise();
            return 0;

        default:
            return 0;
    }
}

static int ble_camera_access_cb(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg) {
    (void)conn_handle;
    (void)attr_handle;

    const intptr_t id = (intptr_t)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR && id == 1) {
        char command[32] = {0};
        const uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        const uint16_t copy_len = len < sizeof(command) - 1 ? len : sizeof(command) - 1;
        os_mbuf_copydata(ctxt->om, 0, copy_len, command);

        if (strcmp(command, CAMERA_CMD_CAPTURE) == 0) {
            s_capture_requested = true;
            ble_camera_service_update_status("capture_requested");
        } else if (strcmp(command, CAMERA_CMD_FETCH_LAST) == 0) {
            s_send_last_requested = true;
            ble_camera_service_update_status("send_requested");
        } else if (strcmp(command, CAMERA_CMD_PING) == 0) {
            ble_camera_service_update_status("pong");
        } else {
            ble_camera_service_update_status("unknown_command");
        }
        return 0;
    }

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        const char *payload = "";
        if (id == 2) {
            payload = s_status_text;
        } else if (id == 3) {
            payload = s_meta_text;
        }
        os_mbuf_append(ctxt->om, payload, strlen(payload));
        return 0;
    }

    return BLE_ATT_ERR_UNLIKELY;
}

esp_err_t ble_camera_service_init(void) {
    esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
    ESP_ERROR_CHECK(esp_nimble_hci_and_controller_init());
    nimble_port_init();

    ble_hs_cfg.sync_cb = ble_camera_on_sync;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("BandBridgeCam");

    int rc = ble_gatts_count_cfg(g_services);
    if (rc != 0) {
        return ESP_FAIL;
    }

    rc = ble_gatts_add_svcs(g_services);
    if (rc != 0) {
        return ESP_FAIL;
    }

    ble_store_config_init();
    nimble_port_freertos_init(ble_camera_host_task);
    snprintf(s_status_text, sizeof(s_status_text), "ble_ready");
    return ESP_OK;
}

void ble_camera_service_update_status(const char *status_text) {
    if (status_text == NULL) {
        return;
    }

    snprintf(s_status_text, sizeof(s_status_text), "%s", status_text);
    if (s_connected && s_conn_handle != BLE_HS_CONN_HANDLE_NONE && s_status_val_handle != 0) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(s_status_text, strlen(s_status_text));
        ble_gatts_notify_custom(s_conn_handle, s_status_val_handle, om);
    }
}

void ble_camera_service_publish_meta(const frame_meta_t *meta) {
    if (meta == NULL) {
        return;
    }

    snprintf(
        s_meta_text,
        sizeof(s_meta_text),
        "{\"frameId\":%" PRIu32 ",\"totalSize\":%" PRIu32 ",\"chunkSize\":%u,\"width\":%u,\"height\":%u}",
        meta->frame_id,
        meta->total_size,
        meta->chunk_size,
        meta->width,
        meta->height
    );

    if (s_connected && s_conn_handle != BLE_HS_CONN_HANDLE_NONE && s_meta_val_handle != 0) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(s_meta_text, strlen(s_meta_text));
        ble_gatts_notify_custom(s_conn_handle, s_meta_val_handle, om);
    }
}

esp_err_t ble_camera_service_notify_chunk(const uint8_t *data, size_t len) {
    if (!s_connected || s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_chunk_val_handle == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    if (data == NULL || len == 0) {
        return ESP_ERR_INVALID_ARG;
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
    if (om == NULL) {
        return ESP_ERR_NO_MEM;
    }

    int rc = ble_gatts_notify_custom(s_conn_handle, s_chunk_val_handle, om);
    return rc == 0 ? ESP_OK : ESP_FAIL;
}

bool ble_camera_service_should_capture(void) {
    const bool result = s_capture_requested;
    s_capture_requested = false;
    return result;
}

bool ble_camera_service_should_send_last(void) {
    const bool result = s_send_last_requested;
    s_send_last_requested = false;
    return result;
}

bool ble_camera_service_is_connected(void) {
    return s_connected;
}

const char *ble_camera_service_get_status_text(void) {
    return s_status_text;
}
