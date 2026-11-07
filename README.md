# Atlas

**A wearable agent with a body, built on 0G.** Built at ETHGlobal Mumbai for the 0G *Physical AI: Agents with a Body* track.

Atlas listens through a clip-on mic, remembers what was said, nudges you about what you promised, and moves real hardware. Every decision runs on 0G, and every decision is recorded on 0G so it can be verified. If 0G is unreachable, the device stops instead of guessing.

Say "Hey Atlas", then:

- *"Summarize the last conversation."* / *"What did we decide about the storage quota last week?"* / *"Give me the full transcript."*
- *"Remind me tomorrow to ship the README."* / *"Remind me if I leave without my headphones."* / *"Delete all reminders."*
- *"Close the door, then buzz, then open it."* / *"Open the door when my headphones go out of range."*
- *"Atlas, SOS."* (Morse code on the LED and the buzzer)

Presentation site for the judges: [`site/`](site/) (run `cd site && python3 -m http.server 5180`).

## How it works

```
wireless mic ─ USB receiver ─ ESP32-S3 ──Wi-Fi──┐
ESP32 (Bluetooth LE scan: headphones, bag tag) ─┤
                                                ├─► laptop bridge ──► 0G Whisper ─► 0G decision model ─► actions
Android app (mic, orb, reminders, chats) ◄─Tailscale─► bridge                                     │
ESP8266 desk gauge: servo (door), LED, buzzer ◄── polls the bridge ◄─────────────────────────────┘
                                                                  every decision ─► 0G Storage + 0G Chain
```

| Part | What it does |
|---|---|
| `firmware/s3_mic` | ESP32-S3 reads the USB receiver as an audio device and POSTs audio to the bridge. |
| `firmware/esp32_brain` | ESP32 scans Bluetooth LE for the headphones and the bag tag. |
| `firmware/esp8266_gauge` | ESP8266 with a servo (door), LED and buzzer. Polls the bridge every 350 ms, plays jingles and Morse SOS. |
| `backend/` | Python bridge: voice activity detection, noise gate, 0G calls, wake word, reminders, nudges, memory (SQLite), hardware sequencer. |
| `backend/og/` | Node scripts for 0G Storage and the 0G Chain contract. |
| `android/` | Kotlin and Jetpack Compose app with its own mic and live streaming words. |
| `site/` | The presentation website. |

## 0G usage

- **Compute (the decision loop).** `backend/stt.py` sends speech to Whisper large-v3, with a second 0G model as fallback. `backend/brain.py` sends the text to qwen3.5-flash, which returns an intent and an ordered list of hardware actions. Every call's request id is kept.
- **Storage (the episode log).** `backend/episodes.py` saves one canonical JSON episode per decision (transcript, speech and decision request ids, actions, outcome) and uploads it to 0G Storage. The root hash is kept. Whole conversations can also be sealed, verified and exported (`backend/seal.py`).
- **Chain (anchor).** `EpisodeAnchor` on 0G Galileo emits each episode's sha256 and storage root.
  - Contract: [`0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E`](https://chainscan-galileo.0g.ai/address/0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E)
  - Source: [`backend/og/contracts/EpisodeAnchor.sol`](backend/og/contracts/EpisodeAnchor.sol), deploy script: [`backend/og/deploy.mjs`](backend/og/deploy.mjs)
- **Safe-stop.** If speech-to-text or the decision call is unreachable, `hardware.safe_stop()` releases the servo, turns the LED and buzzer off and chirps once. Nothing is guessed. The failure is logged as a `safe_stop` episode.

Verify any episode: `GET /api/episodes` lists them, and `GET /api/episode/verify?id=N` downloads it from 0G Storage and compares the hash.

## Run it

You need a 0G API key from [pc.0g.ai](https://pc.0g.ai) and a throwaway wallet funded with Galileo testnet tokens from [faucet.0g.ai](https://faucet.0g.ai).

```bash
# backend
cd backend
cp .env.example .env              # fill in OG_API_KEY and PRIVATE_KEY
pip install numpy pyserial requests
(cd og && npm install)            # 0G Storage SDK, ethers
python3 bridge.py                 # serves on :8000

```

Set `YOUR_WIFI_SSID` and `YOUR_WIFI_PASSWORD` in each firmware's `main` source, then flash. The boards and the laptop must be on the same 2.4 GHz Wi-Fi network.

Flash a board with PlatformIO, for example `cd firmware/esp8266_gauge && pio run -t upload`. The ports are set in each `platformio.ini`.

Android: open `android/` in Android Studio, or run `./gradlew installDebug`. Set the bridge address in the app's settings (it defaults to a Tailscale address).

The bridge works without keys but logs speech-to-text as degraded.

Deploy your own anchor contract with `node backend/og/deploy.mjs`, then set `ANCHOR_ADDRESS` in `.env`.

### Try it without hardware

```bash
python3 demo/feed_audio.py demo/audio/d1_session.wav pause:4 demo/audio/d2_talk.wav pause:22 demo/audio/d3_remind.wav
python3 demo/fake_beacon.py -50 -95 25     # simulate the headphones walking away
curl localhost:8000/api/state
```

See [`demo/DEMO.md`](demo/DEMO.md) for the full demo script.

## Hardware notes

- The ESP32 dev kit has a 26 MHz crystal and needs `board_build.f_cpu = 80000000L` for sane serial output.
- ESP32-S3 USB audio uses Espressif's `usb_host_uac`. A hand-written isochronous reader returned empty packets.
- The wireless receiver outputs one mono channel, so left and right mics are mixed.
- The buzzer is on the ESP8266's D3 (a boot pin), so it can click at power-up.

## Team

Garvit Sharma (@toastyboi1307), solo.

## AI usage

Built with Claude Code: the bridge, firmware, Android app, contract and site were written with AI assistance and tested on the real hardware. At runtime, 0G Whisper and a 0G language model run the product itself.

## License

MIT, see [LICENSE](LICENSE).
