// S3: USB wireless-mic receiver (UAC, mono 48k s16) -> 16 kHz mu-law -> ESP-NOW broadcast (ch 1)
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "usb/usb_host.h"
#include "usb/uac_host.h"
#include "nvs_flash.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_now.h"
#include "esp_timer.h"
#include "esp_http_client.h"

#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASS "YOUR_WIFI_PASSWORD"
#define INGEST_URL "http://10.42.0.1:8000/audio"
#define BATCH 8

#define NOW_CHANNEL 1
#define PKT_AUDIO 1
#define AUDIO_BYTES 238

typedef struct __attribute__((packed)) {
  uint8_t type;
  uint8_t flags;
  uint16_t len;
  uint32_t seq;
  uint32_t ts_ms;
  uint8_t data[AUDIO_BYTES];
} audio_pkt_t;

static QueueHandle_t q, postq;
static volatile bool wifi_up = false;
typedef struct { uint8_t addr, iface; } conn_t;
static const uint8_t BCAST[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};

static uint8_t mulaw(int16_t s) {
  const int BIAS = 0x84, CLIP = 32635;
  int sign = (s >> 8) & 0x80;
  int v = s < 0 ? -s : s;
  if (v > CLIP) v = CLIP;
  v += BIAS;
  int exp = 7;
  for (int m = 0x4000; (v & m) == 0 && exp > 0; m >>= 1) exp--;
  int man = (v >> (exp + 3)) & 0x0f;
  return ~(sign | (exp << 4) | man);
}

static void wifi_ev(void *arg, esp_event_base_t base, int32_t id, void *data) {
  if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) { wifi_up = false; esp_wifi_connect(); }
  else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) { wifi_up = true; printf("[S3] wifi up\n"); }
}

static void post_task(void *a) {
  esp_http_client_config_t hc = { .url = INGEST_URL, .method = HTTP_METHOD_POST, .timeout_ms = 2000, .keep_alive_enable = true };
  esp_http_client_handle_t cl = esp_http_client_init(&hc);
  esp_http_client_set_header(cl, "X-Device", "s3");
  static audio_pkt_t b[BATCH];
  uint32_t ok = 0, bad = 0;
  while (1) {
    int n = 0;
    if (xQueueReceive(postq, &b[0], portMAX_DELAY) != pdTRUE) continue;
    n = 1;
    while (n < BATCH && xQueueReceive(postq, &b[n], pdMS_TO_TICKS(20)) == pdTRUE) n++;
    if (!wifi_up) continue;
    esp_http_client_set_post_field(cl, (const char *)b, n * sizeof(audio_pkt_t));
    if (esp_http_client_perform(cl) == ESP_OK) ok++; else bad++;
    static int64_t lp; int64_t now = esp_timer_get_time();
    if (now - lp > 5000000) { lp = now; printf("[S3] post ok=%u bad=%u\n", (unsigned)ok, (unsigned)bad); }
  }
}

static void espnow_start(void) {
  nvs_flash_init();
  esp_netif_init();
  esp_event_loop_create_default();
  wifi_init_config_t c = WIFI_INIT_CONFIG_DEFAULT();
  esp_wifi_init(&c);
  esp_wifi_set_storage(WIFI_STORAGE_RAM);
  esp_netif_create_default_wifi_sta();
  esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_ev, NULL);
  esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_ev, NULL);
  wifi_config_t wc = {0};
  strcpy((char *)wc.sta.ssid, WIFI_SSID); strcpy((char *)wc.sta.password, WIFI_PASS);
  esp_wifi_set_mode(WIFI_MODE_STA);
  esp_wifi_set_config(WIFI_IF_STA, &wc);
  esp_wifi_start();
  esp_wifi_connect();
  ESP_ERROR_CHECK(esp_now_init());
  esp_now_peer_info_t p = {0};
  memcpy(p.peer_addr, BCAST, 6);
  p.channel = NOW_CHANNEL;
  p.ifidx = WIFI_IF_STA;
  p.encrypt = false;
  ESP_ERROR_CHECK(esp_now_add_peer(&p));
}

static void host_task(void *a) { while (1) { uint32_t f; usb_host_lib_handle_events(portMAX_DELAY, &f); } }
static void drv_cb(uint8_t addr, uint8_t iface, uac_host_driver_event_t ev, void *arg) {
  if (ev == UAC_HOST_DRIVER_EVENT_RX_CONNECTED) { conn_t c = { addr, iface }; xQueueSend(q, &c, 0); }
}
static void dev_cb(uac_host_device_handle_t h, uac_host_device_event_t ev, void *arg) {
  if (ev == UAC_HOST_DEVICE_EVENT_TRANSFER_ERROR || ev == UAC_HOST_DRIVER_EVENT_DISCONNECTED) printf("[UAC] dev event %d\n", ev);
}

void app_main(void) {
  espnow_start();
  q = xQueueCreate(2, sizeof(conn_t));
  postq = xQueueCreate(48, sizeof(audio_pkt_t));
  xTaskCreate(post_task, "post", 6144, NULL, 3, NULL);
  usb_host_config_t hc = { .skip_phy_setup = false, .intr_flags = ESP_INTR_FLAG_LEVEL1 };
  ESP_ERROR_CHECK(usb_host_install(&hc));
  xTaskCreate(host_task, "host", 4096, NULL, 2, NULL);
  uac_host_driver_config_t dc = { .create_background_task = true, .task_priority = 5, .stack_size = 4096,
    .core_id = 0, .callback = drv_cb, .callback_arg = NULL };
  ESP_ERROR_CHECK(uac_host_install(&dc));
  printf("[S3] waiting for mic\n");
  conn_t c; xQueueReceive(q, &c, portMAX_DELAY);
  uac_host_device_config_t cfg = { .addr = c.addr, .iface_num = c.iface, .buffer_size = 48000, .buffer_threshold = 960,
    .callback = dev_cb, .callback_arg = NULL };
  uac_host_device_handle_t h;
  ESP_ERROR_CHECK(uac_host_device_open(&cfg, &h));
  uac_host_stream_config_t sc = { .channels = 1, .bit_resolution = 16, .sample_freq = 48000, .flags = 0 };
  ESP_ERROR_CHECK(uac_host_device_start(h, &sc));
  printf("[S3] streaming, mac-less broadcast on ch %d\n", NOW_CHANNEL);

  static int16_t buf[960];
  static audio_pkt_t pkt;
  pkt.type = PKT_AUDIO; pkt.flags = 0;
  int fill = 0, phase = 0, acc = 0;
  uint32_t seq = 0, sent = 0, fail = 0;
  int64_t last = esp_timer_get_time();
  double ss = 0; int64_t nss = 0;
  while (1) {
    uint32_t n = 0;
    esp_err_t e = uac_host_device_read(h, (uint8_t *)buf, sizeof(buf), &n, pdMS_TO_TICKS(500));
    if (e != ESP_OK || n == 0) continue;
    int cnt = n / 2;
    for (int i = 0; i < cnt; i++) {
      acc += buf[i];
      ss += (double)buf[i] * buf[i]; nss++;
      if (++phase == 3) {  // 48k -> 16k box filter
        pkt.data[fill++] = mulaw((int16_t)(acc / 3));
        acc = 0; phase = 0;
        if (fill == AUDIO_BYTES) {
          pkt.len = AUDIO_BYTES;
          pkt.seq = seq++;
          pkt.ts_ms = (uint32_t)(esp_timer_get_time() / 1000);
          if (wifi_up) {  // Wi-Fi: HTTP; otherwise ESP-NOW broadcast fallback
            if (xQueueSend(postq, &pkt, 0) != pdTRUE) fail++; else sent++;
          } else {
            esp_err_t r = esp_now_send(BCAST, (uint8_t *)&pkt, sizeof(pkt));
            if (r != ESP_OK) { fail++; vTaskDelay(1); } else sent++;
          }
          fill = 0;
        }
      }
    }
    int64_t now = esp_timer_get_time();
    if (now - last > 2000000) {
      printf("[S3] sent=%u fail=%u rms=%d\n", (unsigned)sent, (unsigned)fail, nss ? (int)sqrt(ss / nss) : 0);
      ss = 0; nss = 0; last = now;
    }
  }
}
