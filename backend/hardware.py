"""Hardware actions any command or reminder can carry: servo (door/latch/needle), buzzer, LED. The ESP8266 desk gauge polls /api/gauge and obeys."""
import json, re, time
import agent

def servo_mode(v):
    v = str(v).lower().strip()
    m = re.search(r"\b(\d{1,3})\b", v)
    if m: return f"a{min(int(m.group(1)), 180)}", f"servo to {min(int(m.group(1)), 180)} degrees"
    if re.search(r"gauge|release|reset|needle|auto", v): return "-", "servo back to gauge mode"
    if re.search(r"sweep|test|wave|wiggle|move", v) and not re.search(r"open|clos|lock", v): return "sweep", "servo test"
    if re.search(r"unlock|open", v): return "a90", "door opened"
    if re.search(r"clos|lock|shut", v): return "a0", "door closed"
    return "a90", "door opened"

def buzz_mode(v):
    v = str(v).lower()
    if re.search(r"alarm|loud|long|siren", v): return "alarm", "alarm"
    if re.search(r"jingle|tune|song|melody|chime", v): return "jingle", "jingle"
    if re.search(r"off|stop|quiet", v): return "-", "buzzer off"
    return "beep", "beep"

def led_mode(v):
    v = str(v).lower()
    if re.search(r"off|stop", v): return "-", "LED off"
    if re.search(r"alert|alarm|blink|red|warn", v): return "alert", "LED alert"
    return "flash", "LED flashed"

import threading
DUR = {"servo": 1.8, "buzz": 1.3, "led": 1.4, "sos": 12.5}      # how long a step takes on the device before the next one may start
_lock = threading.Lock()

def _set(kind, mode):
    with _lock:
        hw = json.loads(agent._kv("hw") or "{}")
        hw[kind] = [mode, round(time.time(), 1)]
        agent._kv("hw", json.dumps(hw))

def _steps(actions):
    """Normalise actions into ordered (kind, mode, label, seconds) steps, expanding repeats."""
    out = []
    for a in actions or []:
        if not isinstance(a, dict): continue
        kind, val = str(a.get("do", "")).lower(), a.get("value", "")
        if kind in ("servo", "door"): kind = "servo"; m, d = servo_mode(val)
        elif kind in ("buzz", "buzzer", "beep", "sound"): kind = "buzz"; m, d = buzz_mode(val)
        elif kind in ("led", "light"): kind = "led"; m, d = led_mode(val)
        elif kind in ("sos", "morse"): kind = "sos"; m, d = "go", "SOS in morse"
        else: continue
        try: n = max(1, min(int(a.get("repeat") or 1), 6))
        except Exception: n = 1
        wait = float(a.get("wait") or 0) if str(a.get("wait") or "0").replace(".", "").isdigit() else 0
        for k in range(n):
            out.append((kind, m, d + (f" ({k + 1}/{n})" if n > 1 else ""), DUR[kind] + (wait if k == n - 1 else 0.0)))
    return out

def run(actions):
    """Execute hardware actions IN ORDER ("close the door, then buzz, then open it, then buzz twice"). The first step happens now;
    the rest are played by a background thread, each given time to finish on the device. Returns short descriptions of the plan."""
    steps = _steps(actions)
    if not steps: return []
    def play():
        for kind, mode, _d, secs in steps:
            _set(kind, mode); time.sleep(secs)
    threading.Thread(target=play, daemon=True).start()
    return [d for _k, _m, d, _s in steps]

def ack():
    """One short buzz: 'I heard you and I'm working on it' (sent as soon as the speech is transcribed, before the AI step)."""
    _set("ack", "go")

def feed():
    """The tail of the /api/gauge line: servo mode+stamp, buzz pattern+stamp, led pattern+stamp."""
    hw = json.loads(agent._kv("hw") or "{}"); out = []
    for k in ("servo", "buzz", "led", "sos"):
        m, ts = hw.get(k, ["-", 0])
        if k != "servo" and time.time() - ts > 15: m = "-"           # one-shot patterns expire; the door stays where it was put
        if k == "servo" and m == "sweep" and time.time() - ts > 8: m = "-"
        out += [m, str(int(ts * 10) % 100000000)]
    m, ts = hw.get("ack", ["-", 0])
    out += ["go" if time.time() - ts < 5 else "-", str(int(ts * 10) % 100000000)]
    return " ".join(out)

def safe_stop():
    """0G unreachable: stop moving, never guess. Servo released to gauge mode, LED/buzzer off, then one low 'error' chirp (the alarm tune is never used)."""
    _set("servo", "-"); _set("led", "-"); _set("sos", "-"); _set("buzz", "beep")
