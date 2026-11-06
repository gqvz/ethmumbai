#include <ESP8266WiFi.h>
void setup() {
  WiFi.persistent(false);
  WiFi.softAPdisconnect(true);
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}
void loop() { delay(1000); }
