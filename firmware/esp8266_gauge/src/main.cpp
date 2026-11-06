// Atlas desk gauge: a servo needle shows how far the bag is, LEDs show what Atlas is doing.
// Polls http://10.42.0.1:8000/api/gauge  ->  "<present> <distance_m> <listening> <last_notification_id> <kind>"
//   (5th field: kind of the newest notification, e.g. reminder / nudge / summary)
//   present 0/1 (first tracked item, the bag), distance in metres (-1 if unknown), listening 0/1 (hearing "hey Atlas"), id of newest notification.
// Wiring (NodeMCU): servo signal -> D1, servo red -> VIN (5V), servo brown/black -> G ; external LED anode -> D2, cathode -> G ; buzzer + -> D3, buzzer - -> G.
// (D3 = GPIO0 is a boot pin: if the board will not start with the buzzer wired, unplug the D3 wire, power up, then plug it back in.)
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <Servo.h>

#define SSID "YOUR_WIFI_SSID"
#define PASS "YOUR_WIFI_PASSWORD"
#define URL  "http://10.42.0.1:8000/api/gauge"
const int PIN_SERVO = D1, PIN_LED = D2, PIN_BUZZ = D3, PIN_ONBOARD = LED_BUILTIN;   // onboard LED is active-low

// Jingles (passive buzzer). {frequency Hz, ms}; 0 Hz = rest.
struct Note { uint16_t f, ms; };
const Note J_BOOT[]   = {{523,90},{659,90},{784,90},{1047,160},{0,60},{1319,220}};                      // power-on: rising C major run
const Note J_REMIND[] = {{784,100},{988,100},{1175,100},{1568,260},{0,80},{1175,110},{1568,320}};        // reminder / nudge: bright arpeggio
const Note J_ALARM[]  = {{880,150},{660,150},{880,150},{660,150},{880,150},{660,150},{1047,400}};        // bag left behind: urgent siren
const Note J_CHIRP[]  = {{1319,60},{1760,90}};                                                           // command received: quick blip
const Note J_BEEP[]   = {{1250,160}};
const Note J_ACK[]    = {{1800,90}};
const Note J_OK[]     = {{659,80},{988,160}};
const Note* seq = nullptr; int seqLen = 0, seqIdx = 0; unsigned long noteEnd = 0;
void play(const Note* s, int n) { seq = s; seqLen = n; seqIdx = 0; noteEnd = 0; }
#define PLAY(a) play(a, sizeof(a) / sizeof(Note))
void pumpSound() {                          // non-blocking: call every loop
  if (!seq) return;
  unsigned long now = millis();
  if (now < noteEnd) return;
  if (seqIdx >= seqLen) { seq = nullptr; noTone(PIN_BUZZ); digitalWrite(PIN_BUZZ, LOW); return; }
  const Note& n = seq[seqIdx++];
  if (n.f) tone(PIN_BUZZ, n.f); else noTone(PIN_BUZZ);
  noteEnd = now + n.ms;
}

Servo needle;
int present = 0, listening = 0; float dist = -1; long lastId = -1, seenId = -1; char kind[16] = "", mode[8] = "-"; char stamp[12] = "0", lastStamp[12] = "0";
// SOS in Morse (unit = 200 ms: dot 1, dash 3, gaps 1 / 3 / 7), alternating on/off, played twice on LED + buzzer
const uint8_t SOS_U[] = {1,1,1,1,1,3, 3,1,3,1,3,3, 1,1,1,1,1,7, 1,1,1,1,1,3, 3,1,3,1,3,3, 1,1,1,1,1,1};
bool sosActive = false; unsigned long sosStart = 0; char sstamp[12] = "0", lastS[12] = "0";
char apat[8] = "-", astamp[12] = "0", lastA[12] = "0";
char bpat[8] = "-", bstamp[12] = "0", lastB[12] = "0", lpat[8] = "-", lstamp[12] = "0", lastL[12] = "0";
unsigned long ledOverrideUntil = 0; int ledOverride = 0;   // 1 flash, 2 alert
unsigned long beepUntil = 0, nextAlarm = 0; int beepPattern = 0;
bool wasPresent = true;
float angle = 0, target = 0;
unsigned long lastPoll = 0, flashUntil = 0;
bool servoOn = false;

void led(float v) {                         // v 0..1 on both LEDs
  analogWrite(PIN_LED, (int)(v * v * 1023));
  analogWrite(PIN_ONBOARD, 1023 - (int)(v * v * 1023));
}

void poll() {
  if (WiFi.status() != WL_CONNECTED) { static unsigned long lw = 0; if (millis() - lw > 3000) { lw = millis(); Serial.println("wifi down"); } return; }
  WiFiClient c; HTTPClient h; h.setTimeout(1500);
  if (!h.begin(c, URL)) return;
  if (h.GET() == 200) {
    String s = h.getString();
    int p = 0, l = 0; float d = -1; long id = -1; char k[16] = "", m[8] = "-", st[12] = "0", bp[8] = "-", bs[12] = "0", lp[8] = "-", ls[12] = "0", sp[8] = "-", ss[12] = "0", ap[8] = "-", as_[12] = "0";
    if (sscanf(s.c_str(), "%d %f %d %ld %15s %7s %11s %7s %11s %7s %11s %7s %11s %7s %11s", &p, &d, &l, &id, k, m, st, bp, bs, lp, ls, sp, ss, ap, as_) >= 4) {
      if (strcmp(as_, lastA) && strcmp(ap, "-")) { strcpy(lastA, as_); if (!sosActive) PLAY(J_ACK); }          // heard you, working on it
      if (strcmp(ss, lastS) && strcmp(sp, "-")) { strcpy(lastS, ss); sosActive = true; sosStart = millis(); seq = nullptr; noTone(PIN_BUZZ); Serial.println("SOS start"); }
      if (strcmp(bs, lastB) && strcmp(bp, "-")) {                                          // buzzer command from Atlas
        strcpy(lastB, bs); if (!strcmp(bp, "alarm")) PLAY(J_ALARM); else if (!strcmp(bp, "jingle")) PLAY(J_REMIND); else PLAY(J_BEEP);
      }
      if (strcmp(ls, lastL) && strcmp(lp, "-")) {                                          // LED command from Atlas
        strcpy(lastL, ls); ledOverride = strcmp(lp, "alert") ? 1 : 2; ledOverrideUntil = millis() + (ledOverride == 2 ? 5000 : 1500);
      }
      present = p; dist = d; listening = l; lastId = id; strncpy(kind, k, sizeof(kind) - 1);
      strncpy(mode, m, sizeof(mode) - 1); strncpy(stamp, st, sizeof(stamp) - 1);
      if (strcmp(stamp, lastStamp) && strcmp(mode, "-")) { strcpy(lastStamp, stamp); PLAY(J_CHIRP); }   // new servo command: chirp
      if (seenId < 0) seenId = id;
      else if (id > seenId) {                                               // new notification: flash, and beep for reminders and nudges
        seenId = id; flashUntil = millis() + 900;
        if (!strcmp(k, "reminder") || !strcmp(k, "nudge")) PLAY(J_REMIND); else if (!strcmp(k, "export") || !strcmp(k, "hardware")) PLAY(J_OK);
      }
    }
  }
  h.end();
}

void setup() {
  Serial.begin(115200); Serial.println("\nboot");
  pinMode(PIN_BUZZ, OUTPUT); digitalWrite(PIN_BUZZ, LOW);
  PLAY(J_BOOT);
  pinMode(PIN_LED, OUTPUT); pinMode(PIN_ONBOARD, OUTPUT);
  analogWriteRange(1023);
  needle.attach(PIN_SERVO, 500, 2400); needle.write(0); servoOn = true;
  WiFi.mode(WIFI_STA); WiFi.begin(SSID, PASS);
}

void loop() {
  unsigned long now = millis();
  if (now - lastPoll > 350 && !sosActive && !seq) { lastPoll = now; poll(); }      // a blocking HTTP call would smear Morse timing and jingle notes

  bool online = WiFi.status() == WL_CONNECTED;
  // Needle: 0 deg = right here, 180 deg = 10 m or gone.
  if (online && mode[0] == 'a') target = constrain(atoi(mode + 1), 0, 180);                  // "close the door" / "open the door" / "move to N degrees"
  else if (online && !strcmp(mode, "sweep")) target = 90 + 90 * sin(now / 350.0f);
  else if (!online) target = 90;
  else if (!present) target = 180;
  else target = dist < 0 ? 0 : constrain(dist / 10.0f * 180.0f, 0, 180);
  angle += (target - angle) * (mode[0] == '-' ? 0.12f : 0.2f);
  if (!present && online && mode[0] == '-') angle += sin(now / 90.0f) * 1.5f;                  // agitated wobble when the bag is gone
  needle.write((int)constrain(angle, 0, 180));

  // LEDs: breathing when idle, fast pulse while hearing, flash on a new notification, rapid blink when the bag is gone.
  float v;
  if (!online) v = (now / 500) % 2 ? 0.15f : 0.0f;
  else if (now < ledOverrideUntil) v = (now / (ledOverride == 2 ? 120 : 60)) % 2 ? 1.0f : 0.0f;
  else if (!present) v = (now / 120) % 2 ? 1.0f : 0.0f;
  else if (now < flashUntil) v = (now / 80) % 2 ? 1.0f : 0.1f;
  else if (listening) v = 0.5f + 0.5f * sin(now / 90.0f);
  else v = 0.12f + 0.12f * (0.5f + 0.5f * sin(now / 700.0f));
  if (sosActive) {          // (t is clamped: 'now' was read before the poll that started the SOS)                                                    // SOS overrides LED and buzzer until finished
    unsigned long t = (long)(now - sosStart) < 0 ? 0 : now - sosStart, acc = 0; bool on = false, done = true;
    for (unsigned i = 0; i < sizeof(SOS_U); i++) { acc += SOS_U[i] * 200UL; if (t < acc) { on = (i % 2 == 0); done = false; break; } }
    if (done) { Serial.println("SOS end"); sosActive = false; noTone(PIN_BUZZ); digitalWrite(PIN_BUZZ, LOW); }
    else { v = on ? 1.0f : 0.0f; static bool lastOn = false; if (on != lastOn) { lastOn = on; if (on) tone(PIN_BUZZ, 1000); else noTone(PIN_BUZZ); } }
  }
  led(v);

  // Buzzer: jingles for reminders/nudges, an urgent siren every 6 s while the bag is gone (max 5 times per loss).
  static int alarms = 0;
  if (online && present) { alarms = 0; wasPresent = true; }
  if (online && !present && wasPresent) { wasPresent = false; nextAlarm = now; alarms = 0; }
  if (online && !present && alarms < 5 && now >= nextAlarm && !seq) { PLAY(J_ALARM); nextAlarm = now + 6000; alarms++; }
  if (!sosActive) pumpSound();
  delay(10);
}
