// ESP32 devkit: ESP-NOW (ch 1) audio receiver + BLE scanner -> framed packets over USB serial @460800.
// Frame: A5 5A <len> <payload...> <crc8(payload)>.  payload[0]: 1 audio, 2 BLE text line, 3 stats
#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <NimBLEDevice.h>
#include <HTTPClient.h>

#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASS "YOUR_WIFI_PASSWORD"
#define BLE_URL "http://10.42.0.1:8000/ble"

#define NOW_CHANNEL 1
#define MAXP 250
#define RING 64

static uint8_t ring[RING][MAXP];
static uint8_t ringLen[RING];
static volatile uint8_t head = 0, tail = 0;
static volatile uint32_t rx = 0, dropped = 0;
static SemaphoreHandle_t serMutex;

static uint8_t crc8(const uint8_t *d, int n) {
  uint8_t c = 0;
  while (n--) { c ^= *d++; for (int i = 0; i < 8; i++) c = (c & 0x80) ? (c << 1) ^ 0x07 : c << 1; }
  return c;
}

static void sendFrame(const uint8_t *p, uint8_t len) {
  xSemaphoreTake(serMutex, portMAX_DELAY);
  uint8_t hdr[3] = {0xA5, 0x5A, len};
  Serial.write(hdr, 3);
  Serial.write(p, len);
  Serial.write(crc8(p, len));
  xSemaphoreGive(serMutex);
}

static void onRecv(const uint8_t *mac, const uint8_t *data, int len) {
  if (len <= 0 || len > MAXP) return;
  uint8_t n = (head + 1) % RING;
  rx++;
  if (n == tail) { dropped++; return; }
  memcpy(ring[head], data, len);
  ringLen[head] = len;
  head = n;
}

static void bleTask(void *arg) {
  for (int i = 0; i < 150 && WiFi.status() != WL_CONNECTED; i++) vTaskDelay(pdMS_TO_TICKS(100));  // let WiFi associate first
  NimBLEDevice::init("");
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setActiveScan(false);
  scan->setInterval(100);
  scan->setWindow(50);
  while (true) {
    NimBLEScanResults r = scan->start(2, false);
    String batch;
    for (int i = 0; i < r.getCount(); i++) {
      NimBLEAdvertisedDevice d = r.getDevice(i);
      char line[200];
      int n = snprintf(line + 1, sizeof(line) - 1, "%s|%d|%s|", d.getAddress().toString().c_str(), d.getRSSI(), d.getName().c_str());
      if (d.haveManufacturerData()) {
        auto m = d.getManufacturerData();
        for (size_t k = 0; k < m.length() && n < 150; k++) n += snprintf(line + 1 + n, sizeof(line) - 1 - n, "%02x", (uint8_t)m[k]);
      }
      n += snprintf(line + 1 + n, sizeof(line) - 1 - n, "|");
      if (d.haveServiceUUID()) n += snprintf(line + 1 + n, sizeof(line) - 1 - n, "%s", d.getServiceUUID().toString().c_str());
      line[0] = 2;
      batch += String(line + 1) + "\n";
      sendFrame((uint8_t *)line, n + 1 > MAXP ? MAXP : n + 1);
    }
    scan->clearResults();
    if (WiFi.status() == WL_CONNECTED && batch.length()) {
      HTTPClient h; h.setConnectTimeout(1500); h.setTimeout(1500);
      if (h.begin(BLE_URL)) { h.addHeader("X-Device", "devkit"); int code = h.POST(batch); h.end(); }
    }
    vTaskDelay(pdMS_TO_TICKS(50));
  }
}

void setup() {
  Serial.begin(460800);
  serMutex = xSemaphoreCreateMutex();
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS, NOW_CHANNEL);
  if (esp_now_init() == ESP_OK) esp_now_register_recv_cb(onRecv);   // optional now: audio arrives over HTTP
  xTaskCreatePinnedToCore(bleTask, "ble", 8192, NULL, 1, NULL, 0);
}

void loop() {
  static uint32_t lastStat = 0;
  while (tail != head) {
    sendFrame(ring[tail], ringLen[tail]);
    tail = (tail + 1) % RING;
  }
  if (millis() - lastStat > 1000) {
    lastStat = millis();
    uint8_t s[9];
    s[0] = 3;
    memcpy(s + 1, (const void *)&rx, 4);
    memcpy(s + 5, (const void *)&dropped, 4);
    sendFrame(s, 9);
  }
  delay(1);
}
