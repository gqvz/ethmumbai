"""Main laptop service: serial -> merged 16 kHz audio -> VAD segments -> STT -> brain -> events.
Run: python3 bridge.py         (reads .env for keys; works without keys, STT/brain then log 'degraded')
"""
import json, queue, threading, time, wave, datetime, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import numpy as np, serial
import config, stt, brain, agent, ble_laptop
from frames import *
from presence import Presence

config.DATA.mkdir(exist_ok=True)
for d in ("audio", "archive"):
    (config.DATA / d).mkdir(exist_ok=True)
EVENTS = config.DATA / "events.jsonl"
q = queue.Queue()
stop = threading.Event()

def log_event(ev: dict, _unused: bool = False):
    ev = {"ts": datetime.datetime.now().isoformat(timespec="seconds"), **ev}
    line = json.dumps(ev)
    with open(EVENTS, "a") as f: f.write(line + "\n")
    print(line, flush=True)

def reader(name, port):
    warned = False
    while not stop.is_set():
        try:
            s = serial.Serial(port, config.BAUD, timeout=0.2)
            s.dtr = False; s.rts = True; time.sleep(.1); s.rts = False
            p = FrameParser(); warned = False
            print(f"[{name}] connected", flush=True)
            while not stop.is_set():
                for pl in p.feed(s.read(4096)):
                    q.put((name, pl))
        except (serial.SerialException, OSError) as e:
            if not warned: print(f"[{name}] serial error: {e}; retrying every 3s", flush=True); warned = True
            time.sleep(3)

PKT = AUDIO_HDR.size + 238

VOICE = {}   # sid -> {"pcm": bytearray, "text": str, "busy": int, "done": int, "t": float}

def _voice_partial(sid):
    """Several partial transcriptions run in a pipeline (not one at a time), so text keeps updating despite STT latency."""
    v = VOICE[sid]
    try:
        n = len(v["pcm"]); snap = np.frombuffer(bytes(v["pcm"][-config.SR * 2 * 25:]), dtype="<i2")
        text, _ = stt.transcribe(snap)
        if n > v["done"]: v["text"] = text.strip(); v["done"] = n
    except Exception as e:
        print(f"[voice] partial failed: {e}", flush=True)
    v["busy"] -= 1

def voice_chunk(sid, body, final=False):
    v = VOICE.setdefault(sid, {"pcm": bytearray(), "text": "", "busy": 0, "done": 0, "t": 0.0})
    v["pcm"] += body[:len(body) // 2 * 2]
    if not final and v["busy"] < 3 and time.time() - v["t"] >= 0.9 and len(v["pcm"]) >= config.SR * 2 * 0.8:
        v["busy"] += 1; v["t"] = time.time(); threading.Thread(target=_voice_partial, args=(sid,), daemon=True).start()
    if not final: return {"text": v["text"], "ok": True}
    VOICE.pop(sid, None)
    n = len(v["pcm"])
    if len(v["pcm"]) < config.SR // 2: return {"text": "", "ok": True}
    if v["text"] and n - v["done"] <= config.SR * 2 * 0.6:
        text = v["text"]                                            # partial already covers the audio: no extra round trip
    else:
        try: text = stt.transcribe(np.frombuffer(bytes(v["pcm"]), dtype="<i2"))[0].strip()
        except Exception as e: return {"text": "", "ok": False, "error": str(e)[:120]}
    log_event({"type": "stt", "file": "phone", "dur_s": round(n / 2 / config.SR, 1), "text": text})
    if text and not agent.is_junk_phone(text):
        threading.Thread(target=agent.run_command, args=(text, "phone"), daemon=True).start()
    return {"text": text, "ok": True}

class Ingest(BaseHTTPRequestHandler):
    """Plain-HTTP ingest for the ESPs: POST /audio (concatenated audio packets), POST /ble (addr|rssi|name|mfg|uuid lines)."""
    protocol_version = "HTTP/1.1"
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        src = self.headers.get("X-Device", "http")
        if self.path.startswith("/api/seal"):
            from urllib.parse import urlparse, parse_qs
            import seal
            cid = int(parse_qs(urlparse(self.path).query).get("conv", ["0"])[0])
            def job():
                try: seal.seal(cid)
                except Exception as e: print(f"[seal] {e}", flush=True)
            threading.Thread(target=job, daemon=True).start()
            b = json.dumps({"ok": True}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        if self.path.startswith("/api/voice/") or self.path == "/api/clear":
            from urllib.parse import urlparse, parse_qs
            u = urlparse(self.path); qs = parse_qs(u.query)
            if u.path == "/api/clear": agent.clear_all(); out = {"ok": True}
            else: out = voice_chunk(qs.get("sid", ["x"])[0], body, final=u.path.endswith("/end"))
            b = json.dumps(out).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers()
            self.wfile.write(b); return
        if self.path == "/api/voice":      # phone mic: raw 16 kHz mono PCM16 -> 0G STT -> command
            out = {"text": "", "ok": True}
            try:
                pcm = np.frombuffer(body[:len(body) // 2 * 2], dtype="<i2")
                if len(pcm) >= config.SR // 4:
                    text, meta = stt.transcribe(pcm)
                    text = text.strip(); out["text"] = text
                    log_event({"type": "stt", "file": "phone", "dur_s": round(len(pcm) / config.SR, 1), "text": text, "stt_meta": meta})
                    if text and not agent.is_junk_phone(text):
                        threading.Thread(target=agent.run_command, args=(text, "phone", False, meta), daemon=True).start()
            except Exception as e:
                out = {"text": "", "ok": False, "error": str(e)[:120]}
            b = json.dumps(out).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers()
            self.wfile.write(b); return
        if self.path == "/audio":
            for i in range(0, len(body) - PKT + 1, PKT): q.put((src, body[i:i + PKT]))
        elif self.path == "/api/command":
            t = json.loads(body or b"{}").get("text", "").strip()
            if t: threading.Thread(target=agent.run_command, args=(t, "dashboard"), daemon=True).start()
        elif self.path == "/ble":
            for ln in body.decode(errors="replace").splitlines():
                if ln: q.put((src, bytes([BLE]) + ln.encode()))
        self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers()
    def do_GET(self):
        if self.path.startswith("/api/verify") or self.path.startswith("/export"):
            from urllib.parse import urlparse, parse_qs
            import seal
            u = urlparse(self.path); cid = int(parse_qs(u.query).get("conv", ["0"])[0])
            if u.path == "/export": body, ctype = seal.export_md(cid).encode(), "text/markdown; charset=utf-8"
            else: body, ctype = json.dumps(seal.verify(cid)).encode(), "application/json"
        elif self.path.startswith("/transcript"):      # /transcript?conv=ID | ?all=1 | (default: last conversation) -> plain text
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            t = agent.transcript_text(conv_id=int(qs["conv"][0]) if "conv" in qs else None, everything="all" in qs)
            body, ctype = (t or "No transcript yet.").encode(), "text/plain; charset=utf-8"
        elif self.path.startswith("/api/gauge"):     # tiny plain-text feed for the ESP8266 desk gauge
            st = agent.state(); first = next(iter(st["beacons"].values()), {})
            nid = st["notifications"][0]["id"] if st["notifications"] else 0
            d = first.get("distance_m"); d = -1 if d is None else d
            kind = (st["notifications"][0]["source"] or "none").replace(" ", "_") if st["notifications"] else "none"
            import hardware
            body, ctype = f"{int(bool(first.get('present') or (first.get('age_s') is not None and first['age_s'] < 90)))} {d} {int(bool(st['live']))} {nid} {kind} {hardware.feed()}".encode(), "text/plain"
        elif self.path.startswith("/api/episodes") or self.path.startswith("/api/episode/verify"):
            import episodes
            from urllib.parse import urlparse, parse_qs
            u = urlparse(self.path)
            out = episodes.verify(int(parse_qs(u.query).get("id", ["0"])[0])) if u.path.endswith("verify") else episodes.listing()
            body, ctype = json.dumps(out).encode(), "application/json"
        elif self.path.startswith("/api/state"):
            body, ctype = json.dumps(agent.state()).encode(), "application/json"
        else:
            body, ctype = (config.ROOT / "web" / "index.html").read_bytes(), "text/html; charset=utf-8"
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass

def write_wav(path, pcm):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(config.SR); w.writeframes(pcm.astype(np.int16).tobytes())

class Segmenter:
    """Energy VAD on 100 ms frames with adaptive noise floor."""
    def __init__(self):
        self.noise = 60.0
        self.buf = []          # frames of current segment (incl. pre-roll)
        self.pre = []          # pre-roll frames
        self.active = False
        self.hang = 0.0
        self.fl = int(config.SR * config.VAD_FRAME_S)
        self.carry = np.zeros(0, np.int16)
        self.count = 0

    def push(self, pcm):
        out = []
        x = np.concatenate([self.carry, pcm]); n = len(x) // self.fl
        for i in range(n):
            fr = x[i * self.fl:(i + 1) * self.fl]
            rms = float(np.sqrt(np.mean(fr.astype(np.float32) ** 2)))
            thr = max(config.VAD_MIN_RMS, self.noise * config.VAD_RATIO)
            if rms > thr:
                if not self.active:
                    self.active = True; self.buf = list(self.pre)
                self.buf.append(fr); self.hang = 0.0
            else:
                if not self.active:
                    self.noise = 0.98 * self.noise + 0.02 * rms
                    self.pre = (self.pre + [fr])[-5:]
                else:
                    self.buf.append(fr); self.hang += config.VAD_FRAME_S
                    if self.hang >= config.VAD_HANG_S:
                        out.append(np.concatenate(self.buf)); self.active = False; self.buf = []
            if self.active and len(self.buf) * config.VAD_FRAME_S >= config.SEG_MAX_S:
                out.append(np.concatenate(self.buf)); self.buf = []; self.active = False
        self.carry = x[n * self.fl:]
        self.count += len(out)
        return [s for s in out if len(s) / config.SR >= config.SEG_MIN_S]

_last_stt_alert = [0.0]
def _stt_down(err):
    """0G STT unreachable: safe-stop (hardware released, one error chirp) and tell the user, at most once a minute. Nothing is guessed."""
    if time.time() - _last_stt_alert[0] < 60: return
    _last_stt_alert[0] = time.time()
    import hardware; hardware.safe_stop()
    agent.notify("0G speech-to-text is unreachable; safe-stopped, no commands will run until it is back.", "degraded")

def handle_segment(seg, pres: Presence):
    if not stt.is_speech(seg):
        p90, act = stt.seg_stats(seg)
        log_event({"type": "dropped", "reason": "quiet/noise", "dur_s": round(len(seg) / config.SR, 1), "p90": round(p90), "active": round(act, 2)}); return
    t = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = config.DATA / "audio" / f"{t}.wav"
    write_wav(path, seg)
    dur = round(len(seg) / config.SR, 1)
    try:
        text, meta = stt.transcribe(seg)
    except Exception as e:
        log_event({"type": "segment", "file": path.name, "dur_s": dur, "stt": "degraded", "error": str(e)})
        _stt_down(str(e)); return
    log_event({"type": "stt", "file": path.name, "dur_s": dur, "text": text, "stt_meta": meta}, False)
    if agent.split_wake(text)[0] or agent.wake_pending(): agent.set_live(text)
    agent.handle_text(text, path.name, stt=meta)

def main():
    agent.init(); agent.log = log_event
    import episodes; episodes.init()
    srv = ThreadingHTTPServer((config.HTTP_BIND, config.HTTP_PORT), Ingest); srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    for n, p in config.PORTS.items():
        threading.Thread(target=reader, args=(n, p), daemon=True).start()
    seen, seg, pres = set(), Segmenter(), Presence()
    agent.pres = pres
    if config.LAPTOP_BLE:
        threading.Thread(target=ble_laptop.run, args=(lambda a, r: pres.update({"addr": a, "rssi": r}, "laptop"), stop), daemon=True).start()
    live = {"busy": False, "t": 0.0}
    def live_job(snap, sid):
        try:
            if not stt.is_speech(snap): live['busy'] = False; return
            text = stt.transcribe(snap)[0].strip()
            print(f'[live] seg{sid}/{seg.count} {len(snap)/config.SR:.1f}s: {text}', flush=True)
            if text and sid >= seg.count - 1 and (agent.split_wake(text)[0] or agent.wake_pending()): agent.set_live(text)
        except Exception: pass
        live["busy"] = False
    arch, arch_t0 = [], time.time()
    last_stat = 0; got = 0; lost = 0; last_seq = None; pending = {}; stats = {}
    workers = queue.Queue()
    def worker():
        while True:
            s = workers.get(); handle_segment(s, pres)
    threading.Thread(target=worker, daemon=True).start()
    log_event({"type": "start", "stt": bool(config.OG_KEY), "http": f"{config.HTTP_BIND}:{config.HTTP_PORT}"})
    try:
        while True:
            try: name, pl = q.get(timeout=0.5)
            except queue.Empty: name = None
            if name:
                if pl[0] == AUDIO:
                    seqn, ts, d = parse_audio(pl)
                    if seqn in seen: continue
                    seen.add(seqn)
                    if len(seen) > 5000: seen = set(sorted(seen)[-2000:])
                    # fill gaps with silence to keep timeline correct (cap 1s)
                    if last_seq is not None and seqn > last_seq + 1:
                        gap = min(seqn - last_seq - 1, 67); lost += seqn - last_seq - 1
                        pcm0 = np.zeros(238 * gap, np.int16); arch.append(pcm0); workers_pcm = seg.push(pcm0)
                        for s in workers_pcm: workers.put(s)
                    if last_seq is None or seqn > last_seq:
                        last_seq = seqn
                    pcm = decode_mulaw(d); got += 1; agent.LAST_AUDIO = time.time()
                    arch.append(pcm)
                    for s in seg.push(pcm): workers.put(s)
                elif pl[0] == BLE:
                    pres.update(parse_ble(pl))
                elif pl[0] == STATS:
                    stats[name] = parse_stats(pl)
            if seg.active and not live["busy"] and time.time() - live["t"] >= 1.0 and len(seg.buf) * config.VAD_FRAME_S >= 1.0:
                live["busy"] = True; live["t"] = time.time()
                threading.Thread(target=live_job, args=(np.concatenate(seg.buf[-300:]), seg.count), daemon=True).start()
            for ev in pres.tick(): log_event(ev); agent.on_beacon(ev)
            agent.tick(); __import__("episodes").retry()
            if time.time() - arch_t0 >= config.ARCHIVE_CHUNK_S and arch:
                a = np.concatenate(arch); arch = []; arch_t0 = time.time()
                write_wav(config.DATA / "archive" / f"{datetime.datetime.now():%Y%m%d-%H%M%S}.wav", a)
            if time.time() - last_stat > 10:
                last_stat = time.time()
                log_event({"type": "stat", "pkts": got, "lost": lost, "esp": stats, "ble": pres.state()})
    except KeyboardInterrupt:
        stop.set()

if __name__ == "__main__":
    main()
