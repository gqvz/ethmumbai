# Atlas: a wearable room agent on 0G (Physical AI)

Wake word "Hey Atlas". Wireless mics -> ESP32-S3 -> laptop bridge; BLE beacon proximity; an ESP8266 desk gauge (servo, LED, buzzer) acts on commands.
**0G is the brain, the device keeps motion local:** 0G Whisper transcribes, a 0G chat model decides, the bridge turns the decision into hardware actions.

```
wireless mic -> USB UAC receiver -> [ESP32-S3] --WiFi POST /audio--> laptop bridge --> 0G STT -> 0G decision -> actions
[ESP32 devkit BLE scan] --POST /ble--> bridge        phone app (Tailscale) <-> bridge        [ESP8266 gauge] <-- GET /api/gauge
```

## 0G usage
- **Compute (decision loop):** `stt.py` (whisper-large-v3, omni fallback), `brain.py` (qwen3.5-flash intent -> ordered hardware actions).
- **Storage (episode log):** `backend/episodes.py` writes one canonical-JSON episode per decision (transcript, STT request id, decision request id,
  actions, outcome, status) to 0G Storage via `backend/og/seal.mjs`; root hash + tx are kept. `GET /api/episodes`, `GET /api/episode/verify?id=N`
  (re-downloads from 0G and compares sha256). Whole conversations can be sealed/verified/exported too (`seal.py`).
- **Chain (0G Galileo):** `EpisodeAnchor` at `0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E` ([explorer](https://chainscan-galileo.0g.ai/address/0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E), source `backend/og/contracts/EpisodeAnchor.sol`, deploy `node backend/og/deploy.mjs`). Each episode's sha256 + 0G Storage root is emitted in an `Anchored` event after upload.
- **Safe-stop:** if 0G STT or the decision call is unreachable, `hardware.safe_stop()` releases the servo, turns LED/buzzer off and chirps once. Nothing is guessed. The attempt is logged as a `safe_stop` episode and uploaded when storage is reachable (`episodes.retry`).

## Layout
- `backend/bridge.py` ingest + HTTP API; `agent.py` wake/commands/reminders; `hardware.py` ordered servo/buzz/LED/SOS sequencer; `presence.py` BLE.
- `firmware/s3_mic`, `firmware/esp32_brain`, `firmware/esp8266_gauge` (PlatformIO); `android/` Compose app.

## Run
```
cd backend && cp .env.example .env   # OG_API_KEY, PRIVATE_KEY (0G testnet, faucet.0g.ai)
./../Wi-Fi.sh up                    # ESPs join the laptop Wi-Fi (venue Wi-Fi is 5 GHz only)
python3 bridge.py
```
Try: "Hey Atlas, SOS" / "close the door, then buzz, then open it" / "summarize the last conversation" / "open the door when my headphones go out of range".
Verify an episode: `curl 'localhost:8000/api/episodes'`, then `curl 'localhost:8000/api/episode/verify?id=1'`.

Hardware notes: ESP32 devkit 26 MHz crystal needs `board_build.f_cpu = 80000000L`; S3 USB audio uses Espressif `usb_host_uac`; the receiver outputs ONE mono channel.
AI usage: built with Claude Code.
