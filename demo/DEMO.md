# Room: 3-minute sponsor demo

**One line:** a wearable agent. Two clip-on mics stream to an ESP32-S3, 0G transcribes and decides, a servo/LED/buzzer desk gauge acts, and a beacon on your bag makes it remind you before you walk off without it.

## Before you start (2 min)
1. `cd backend && python3 bridge.py` (add `CONVO_GAP_S=15` so a conversation ends after 15 s of quiet; default is 90 s).
2. Put the laptop and the boards on the same Wi-Fi network (2.4 GHz).
3. Phone: Atlas, Settings, address `http://100.74.67.52:8000` (the laptop's Tailscale address; both are on Tailscale, so it works from any network and the phone stays on venue Wi-Fi).Keep the phone awake: `adb shell svc power stayon usb`. The status top right should read `online`.
4. Beacon on and in the bag. Laptop Bluetooth watches it too, so no BLE board is required.
5. If 0G's whisper endpoint errors, the bridge switches to `qwen3.5-omni-flash` on the same 0G router for 2 minutes (proper nouns come out rougher).
6. Fallback if the room is loud, or no hardware: play the recorded lines, `paplay demo/audio/<name>.wav` (names below), or stream them into the bridge exactly like the mics do: `python3 demo/feed_audio.py demo/audio/d1_session.wav pause:4 demo/audio/d2_talk.wav pause:22 demo/audio/d3_remind.wav`. For the beacon walk-away without a beacon: `python3 demo/fake_beacon.py -50 -95 25`.

## The flow

| # | You say (after "Hey Atlas") | What happens | Point at |
|---|---|---|---|
| 1 | "I'm going into a conversation. Summarize it after." (`d1_session`) | Orb turns to recording state; a session starts | Phone: orb surge, status line |
| 2 | Talk normally for ~20 s (`d2_talk`) | Speech is transcribed and stored; no command, so it stays quiet | Chats tab filling in |
| 3 | Stop talking for 15 s | The conversation closes and a summary appears on its own, plus a phone notification | Notification, Chats tab |
| 4 | "Remind me next week to follow up with the 0G team." (`d3_remind`) | Time reminder created | Reminders tab |
| 5 | "Remind me if I leave this room that I should take my bag." (`d4_bag`) | Beacon reminder armed on the bag | Reminders: "when FSC_BP103 goes out of range" |
| 6 | "What was that thing about the storage quota?" (`d5_search`) | Searches past conversations and answers | Orb line, Activity |
| 7 | **Walk out with the bag away** (or switch the beacon off) | Beacon goes quiet or weak, `beacon_lost` fires, the reminder lands on the phone | Phone notification: "take my bag" |

Overview (swipe up) shows the live distance estimate and signal for the beacon.

## What each sponsor sees
- **0G**: speech to text (`whisper-large-v3`) and every decision (intent parsing, summaries, search answers, reminder wording, `qwen3.5-flash`) go through the 0G router, nothing local. Conversation records also upload to 0G Storage on Galileo.
- **Physical AI track**: every decision is an *episode* (transcript, 0G STT request id, 0G decision request id, actions, outcome) hashed and written to 0G Storage; the app and `GET /api/episodes` show root hash + tx, `GET /api/episode/verify?id=N` re-downloads and checks it. If 0G is unreachable the device **safe-stops** (servo released, LED/buzzer off, one error chirp, nothing guessed). Try it: unplug the laptop's internet, say "Hey Atlas, close the door".
- Hardware voice commands to demo: "Atlas, SOS" (Morse on LED + buzzer), "close the door, then buzz, then open it".
- **Without 0G**: no speech-to-text, no decisions and no verifiable record: Atlas stops acting by design.

## If something misbehaves
- Orb stays grey and the pill says `offline`: the phone cannot reach the laptop, or the bridge is down.
- Commands ignored: say "Hey Atlas" then pause half a second; the matcher also accepts near misses.
- No beacon: check `curl localhost:8000/api/state`; the beacon should show `source: laptop` or `esp`.
