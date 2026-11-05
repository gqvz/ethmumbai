#include <ESP8266WiFi.h>
#define WIFI_SSID "ETHGlobal"
#define WIFI_PASS "www.ethglobal.com"
void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.printf("\n[WIFI] connecting to %s\n", WIFI_SSID);
}
void loop() {
  if (WiFi.status() == WL_CONNECTED)
    Serial.printf("[WIFI] connected ip=%s rssi=%d ch=%d\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(), WiFi.channel());
  else
    Serial.printf("[WIFI] status=%d\n", WiFi.status());
  delay(2000);
}
