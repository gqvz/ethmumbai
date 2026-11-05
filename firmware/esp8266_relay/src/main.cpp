// ESP8266: ESP-NOW (ch 1) receiver -> framed packets over USB serial @460800 to the laptop bridge.
// Frame: A5 5A <len> <payload...> <crc8(payload)>.  payload[0] = type (1 audio, 3 stats)
#include <ESP8266WiFi.h>
extern "C" {
#include <espnow.h>
#include <user_interface.h>
}

#define NOW_CHANNEL 1
#define MAXP 250
#define RING 48

static uint8_t ring[RING][MAXP];
static uint8_t ringLen[RING];
static volatile uint8_t head = 0, tail = 0;
static volatile uint32_t rx = 0, dropped = 0;

static uint8_t crc8(const uint8_t *d, int n) {
  uint8_t c = 0;
  while (n--) { c ^= *d++; for (int i = 0; i < 8; i++) c = (c & 0x80) ? (c << 1) ^ 0x07 : c << 1; }
  return c;
}

static void onRecv(uint8_t *mac, uint8_t *data, uint8_t len) {
  if (len == 0 || len > MAXP) return;
  uint8_t n = (head + 1) % RING;
  rx++;
  if (n == tail) { dropped++; return; }
  memcpy(ring[head], data, len);
  ringLen[head] = len;
  head = n;
}

static void sendFrame(const uint8_t *p, uint8_t len) {
  uint8_t hdr[3] = {0xA5, 0x5A, len};
  Serial.write(hdr, 3);
  Serial.write(p, len);
  Serial.write(crc8(p, len));
}

void setup() {
  Serial.begin(460800);
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  wifi_set_channel(NOW_CHANNEL);
  if (esp_now_init() != 0) { ESP.restart(); }
  esp_now_set_self_role(ESP_NOW_ROLE_COMBO);
  esp_now_register_recv_cb(onRecv);
}

void loop() {
  static uint32_t lastStat = 0;
  while (tail != head) {
    sendFrame(ring[tail], ringLen[tail]);
    tail = (tail + 1) % RING;
    yield();
  }
  if (millis() - lastStat > 1000) {
    lastStat = millis();
    uint8_t s[9];
    s[0] = 3;
    memcpy(s + 1, (const void *)&rx, 4);
    memcpy(s + 5, (const void *)&dropped, 4);
    sendFrame(s, 9);
  }
}
