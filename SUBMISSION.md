# Submission: Atlas, a wearable room agent (0G Physical AI: Agents with a Body)

**Short description:** Atlas is a voice-controlled room agent with a body: wireless mics + BLE proximity feed a laptop bridge, 0G transcribes and decides,
and an ESP8266 desk gauge (servo "door", LED, buzzer, Morse SOS) acts. Motion stays on the device; 0G makes each decision and keeps a verifiable record.
Phone app (Android, over Tailscale) shows the orb, reminders, chats and the on-0G decision log.

## Track checklist (Physical AI)
| Requirement | Where |
|---|---|
| 0G call inside the decision loop | `stt.py` (0G Whisper), `brain.py` (0G `qwen3.5-flash` -> ordered actions), `agent.run_command` |
| Episode log on 0G Storage, request id per decision | `episodes.py`: each episode holds the STT and decision request ids; `GET /api/episodes`, `GET /api/episode/verify?id=N` |
| Live hardware demo | Atlas device at the booth (voice: "Hey Atlas, SOS", "close the door, then buzz, then open it") |
| Safe-stop if 0G unreachable (no faked decision) | `hardware.safe_stop()`: servo released, LED/buzzer off, one chirp; logged as a `safe_stop` episode |
| Not "0G only for logs" | 0G does the speech recognition and the decisions; storage/chain only record them |

**What breaks if 0G is removed:** no speech-to-text, no decisions, so the device refuses to act (by design), and there is no verifiable record.

## 0G addresses
- 0G Chain (Galileo testnet) contract `EpisodeAnchor`: `0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E`
  - deploy tx `0xba1319648cead64c2ee9b81efdae0aab043d92795a5a2828482489bbf209cb74`, deployer `0xB6a5b15815029D67E1e7FB7594e69D593fADB512`
  - https://chainscan-galileo.0g.ai/address/0x3c0a37027c79dA7934c0dfa4b2f683270a6f7A8E
- Example anchor tx: `0xef4310366bd388ead05a566f32cfe9f35eeefa72d9574176af7bc4a2f4c7224e`
- 0G Compute: router API (`router-api.0g.ai`), models `whisper-large-v3`, `qwen3.5-omni-flash` (STT fallback), `qwen3.5-flash`. Request ids are stored in every episode.
- 0G Storage roots: stored per episode (`GET /api/episodes`); verify with `GET /api/episode/verify?id=N` (re-downloads and compares sha256).

## Run / verify
See README. `curl localhost:8000/api/episodes`, then `curl 'localhost:8000/api/episode/verify?id=1'`.

## How AI was used
Built with Claude Code (code, firmware and Android app written with AI assistance and tested on the real hardware). 0G models run the product itself.

## To fill in before submitting (not in the repo)
- Demo video link (under 3 min) and live demo link
- Team member names, Telegram and X handles
