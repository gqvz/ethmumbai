#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "usb/usb_host.h"
#include "esp_rom_sys.h"
#define ets_printf esp_rom_printf

#define EP_IN 0x82
#define PKTS 8
#define NXFER 4

static usb_host_client_handle_t client;
static usb_device_handle_t dev;
static volatile bool got_dev = false;
static uint8_t addr;
static int nch = 1, sub = 2, mps = 96;
static volatile uint64_t ss[8];
static volatile uint32_t cnt, pkts, bad, bytes;
static volatile int peak[8];

static volatile int dbg;
static void xfer_cb(usb_transfer_t *t) {
  if (dbg < 3 && t->status == USB_TRANSFER_STATUS_COMPLETED) {
    dbg++;
    int nz = 0; for (int i = 0; i < t->num_bytes; i++) nz += t->data_buffer[i] != 0;
    ets_printf("[DBG] st=%d actual=%d nonzero=%d p0 st=%d act=%d\n", t->status, t->actual_num_bytes, nz, t->isoc_packet_desc[0].status, t->isoc_packet_desc[0].actual_num_bytes);
  }
  if (t->status == USB_TRANSFER_STATUS_COMPLETED || t->status == USB_TRANSFER_STATUS_NO_DEVICE) {
    if (t->status == USB_TRANSFER_STATUS_NO_DEVICE) return;
    uint8_t *p = t->data_buffer;
    for (int i = 0; i < t->num_isoc_packets; i++) {
      usb_isoc_packet_desc_t *d = &t->isoc_packet_desc[i];
      pkts++; bytes += d->actual_num_bytes;
      if (d->status != USB_TRANSFER_STATUS_COMPLETED) { bad++; p += mps; continue; }
      int frames = mps / (nch * sub);  /* actual_num_bytes reads 0 on S3; data is in buffer */
      int16_t *s = (int16_t *)p;
      for (int f = 0; f < frames; f++)
        for (int c = 0; c < nch; c++) {
          int v = s[f * nch + c];
          ss[c] += (int64_t)v * v;
          if (abs(v) > peak[c]) peak[c] = abs(v);
        }
      cnt += frames;
      p += mps;
    }
  }
  usb_host_transfer_submit(t);
}

static SemaphoreHandle_t ctrl_sem;
static void ctrl_cb(usb_transfer_t *t) { xSemaphoreGive(ctrl_sem); }
static int ctrl(uint8_t type, uint8_t req, uint16_t val, uint16_t idx, uint8_t *data, int len) {
  usb_transfer_t *t;
  usb_host_transfer_alloc(sizeof(usb_setup_packet_t) + len, 0, &t);
  usb_setup_packet_t *sp = (usb_setup_packet_t *)t->data_buffer;
  sp->bmRequestType = type; sp->bRequest = req; sp->wValue = val; sp->wIndex = idx; sp->wLength = len;
  if (!(type & 0x80)) memcpy(t->data_buffer + sizeof(*sp), data, len);
  t->num_bytes = sizeof(*sp) + len; t->device_handle = dev; t->bEndpointAddress = 0;
  t->callback = ctrl_cb; t->context = NULL;
  usb_host_transfer_submit_control(client, t);
  xSemaphoreTake(ctrl_sem, pdMS_TO_TICKS(1000));
  int st = t->status;
  if (type & 0x80) memcpy(data, t->data_buffer + sizeof(*sp), len);
  usb_host_transfer_free(t);
  return st;
}
static void client_cb(const usb_host_client_event_msg_t *msg, void *arg) {
  if (msg->event == USB_HOST_CLIENT_EVENT_NEW_DEV) { addr = msg->new_dev.address; got_dev = true; }
  else printf("[USB] device removed\n");
}

static void client_task(void *a) { while (1) usb_host_client_handle_events(client, portMAX_DELAY); }
static void host_task(void *a) { while (1) { uint32_t f; usb_host_lib_handle_events(portMAX_DELAY, &f); } }

void app_main(void) {
  usb_host_config_t cfg = { .skip_phy_setup = false, .intr_flags = ESP_INTR_FLAG_LEVEL1 };
  ESP_ERROR_CHECK(usb_host_install(&cfg));
  xTaskCreate(host_task, "host", 4096, NULL, 2, NULL);
  usb_host_client_config_t cc = { .is_synchronous = false, .max_num_event_msg = 5,
    .async = { .client_event_callback = client_cb, .callback_arg = NULL } };
  ESP_ERROR_CHECK(usb_host_client_register(&cc, &client));
  while (!got_dev) usb_host_client_handle_events(client, pdMS_TO_TICKS(100));
  ESP_ERROR_CHECK(usb_host_device_open(client, addr, &dev));
  const usb_config_desc_t *cd;
  usb_host_get_active_config_descriptor(dev, &cd);
  const uint8_t *b = (const uint8_t *)cd;
  printf("[USB] raw config desc:");
  for (int i = 0; i < cd->wTotalLength; i++) printf("%s%02x", i % 16 ? " " : "\n", b[i]);
  printf("\n");
  for (int i = 0; i + 3 < cd->wTotalLength; i += b[i] ? b[i] : 1) {
    if (b[i] >= 8 && b[i + 1] == 0x24 && b[i + 2] == 2 && b[i + 3] == 1) {
      nch = b[i + 4]; sub = b[i + 5];
      int rate = b[i + 8] | (b[i + 9] << 8) | (b[i + 10] << 16);
      printf("[USB] format: %d ch, %d-bit (subframe %d), %d Hz\n", nch, b[i + 6], sub, rate);
    }
  }
  if (sub != 2 || nch > 8) printf("[USB] WARN unsupported sample format\n");
  ctrl_sem = xSemaphoreCreateBinary();
  xTaskCreate(client_task, "client", 4096, NULL, 3, NULL);
  uint8_t m = 0xff;
  int st = ctrl(0xA1, 0x01, 0x0100, 0x0500, &m, 1);
  printf("[USB] GET mute st=%d val=%d\n", st, m);
  m = 0; st = ctrl(0x21, 0x01, 0x0100, 0x0500, &m, 1);
  printf("[USB] SET mute=0 st=%d\n", st);
  m = 0xff; st = ctrl(0xA1, 0x01, 0x0100, 0x0500, &m, 1);
  printf("[USB] GET mute st=%d val=%d\n", st, m);
  ESP_ERROR_CHECK(usb_host_interface_claim(client, dev, 1, 1));
  uint8_t fr[3] = {0x80, 0xbb, 0x00};
  st = ctrl(0x22, 0x01, 0x0100, EP_IN, fr, 3);
  printf("[USB] SET rate 48000 st=%d\n", st);
  fr[0] = fr[1] = fr[2] = 0; st = ctrl(0xA2, 0x81, 0x0100, EP_IN, fr, 3);
  printf("[USB] GET rate st=%d val=%d\n", st, fr[0] | fr[1] << 8 | fr[2] << 16);
  for (int n = 0; n < NXFER; n++) {
    usb_transfer_t *t;
    ESP_ERROR_CHECK(usb_host_transfer_alloc(PKTS * mps, PKTS, &t));
    t->device_handle = dev; t->bEndpointAddress = EP_IN; t->callback = xfer_cb;
    t->num_bytes = PKTS * mps;
    for (int i = 0; i < PKTS; i++) t->isoc_packet_desc[i].num_bytes = mps;
    ESP_ERROR_CHECK(usb_host_transfer_submit(t));
  }
  printf("[USB] streaming\n");
  while (1) {
    vTaskDelay(pdMS_TO_TICKS(250));
    uint32_t c = cnt; if (!c) { printf("[MIC] no samples (pkts=%u bad=%u bytes=%u)\n", (unsigned)pkts, (unsigned)bad, (unsigned)bytes); continue; }
    printf("[MIC] frames=%u bad=%u", (unsigned)c, (unsigned)bad);
    for (int ch = 0; ch < nch; ch++) {
      printf("  ch%d rms=%5d peak=%5d", ch, (int)__builtin_sqrt((double)ss[ch] / c), peak[ch]);
      ss[ch] = 0; peak[ch] = 0;
    }
    printf("\n"); cnt = 0; bad = 0;
  }
}
