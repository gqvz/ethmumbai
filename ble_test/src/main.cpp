#include <Arduino.h>
#include <WiFi.h>
#include <BLEDevice.h>
#define WIFI_SSID "ETHGlobal"
#define WIFI_PASS "www.ethglobal.com"
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
BLEScan* scan;
void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  BLEDevice::init("");
  scan = BLEDevice::getScan();
  scan->setActiveScan(true);
  scan->setInterval(100);
  scan->setWindow(99);
}
void loop() {
  Serial.printf("\n[WIFI] status=%d ip=%s rssi=%d\n", WiFi.status(), WiFi.localIP().toString().c_str(), WiFi.RSSI());
  Serial.println("[BLE] scanning 5s...");
  BLEScanResults r = scan->start(5, false);
  Serial.printf("[BLE] %d devices\n", r.getCount());
  for (int i = 0; i < r.getCount(); i++) {
    BLEAdvertisedDevice d = r.getDevice(i);
    Serial.printf("[BLE] %s rssi=%d name='%s'", d.getAddress().toString().c_str(), d.getRSSI(), d.getName().c_str());
    if (d.haveManufacturerData()) {
      auto m = d.getManufacturerData();
      Serial.print(" mfg=");
      for (size_t k = 0; k < m.length(); k++) Serial.printf("%02x", (uint8_t)m[k]);
    }
    if (d.haveServiceUUID()) Serial.printf(" uuid=%s", d.getServiceUUID().toString().c_str());
    Serial.println();
  }
  scan->clearResults();
  delay(1000);
}
