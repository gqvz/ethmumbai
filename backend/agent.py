"""Voice agent: transcript store, wake phrase, 0G-parsed commands, summaries, search, reminders (time + beacon).

Entry points used by bridge.py: handle_text(), on_beacon(), tick(), state(), run_command().
Nothing is faked: if 0G fails, a 'degraded' notification is stored instead of an invented answer.
"""
import config, datetime, difflib, json, re, sqlite3, subprocess, threading, time
import requests
import brain, config

DB = config.DATA / "agent.db"
log = lambda ev, _=False: None   # set by bridge (log_event)
pres = None                          # set by bridge (Presence)
_lock = threading.RLock()

_N = config.AGENT_NAME.lower()
WAKE = {"hey" + _N, "okay" + _N, "ok" + _N, "hi" + _N, "a" + _N, _N, "hey", "hay" + _N, "hey" + _N + "s"}
JUNK = {"you", "oh", "ah", "um", "uh", "hmm", "yeah", "okay", "ok", "bye", "thanks", "thank you", "thanks for watching",
        "thank you.", "no, thanks.", "thank you for watching", "thank you for watching!", "thanks for watching!", "i'm sorry", "i'm sorry.", "please", "please.", "you.", "hello", "hello.", "bien"}

def _migrate():
    with sqlite3.connect(DB, timeout=10) as c:
        cols = [r[1] for r in c.execute("PRAGMA table_info(reminders)")]
        if cols and "actions" not in cols: c.execute("ALTER TABLE reminders ADD COLUMN actions TEXT")

def _conn():
    c = sqlite3.connect(DB, timeout=10); c.row_factory = sqlite3.Row
    return c

def init():
    config.DATA.mkdir(exist_ok=True)
    with _lock, _conn() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS transcripts(id INTEGER PRIMARY KEY, ts REAL, text TEXT, file TEXT, kind TEXT, conv INTEGER);
        CREATE TABLE IF NOT EXISTS convos(id INTEGER PRIMARY KEY, start REAL, end REAL, summary TEXT);
        CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY, created REAL, kind TEXT, due REAL, label TEXT, message TEXT, fired INTEGER DEFAULT 0, actions TEXT);
        CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY, ts REAL, text TEXT, source TEXT);
        CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE IF NOT EXISTS seals(id INTEGER PRIMARY KEY, conv INTEGER, sha256 TEXT, root TEXT, tx TEXT, ts REAL, status TEXT, error TEXT);
        CREATE TABLE IF NOT EXISTS nudges(id INTEGER PRIMARY KEY, created REAL, conv INTEGER, text TEXT, due REAL, fired INTEGER DEFAULT 0);
        """)
    _migrate()

def _kv(k, v=None, delete=False):
    with _lock, _conn() as c:
        if delete: c.execute("DELETE FROM kv WHERE k=?", (k,)); return None
        if v is not None: c.execute("INSERT OR REPLACE INTO kv VALUES(?,?)", (k, str(v))); return v
        r = c.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
        return r["v"] if r else None

# ---------- text gating ----------
def _tokens(text):
    return [(m.group().lower(), m.end()) for m in re.finditer(r"[A-Za-z0-9]+", text)]

def split_wake(text: str):
    """(is_wake, rest). Fuzzy-matches 'hey <name>' (Whisper hears 'A.R.O.', 'hey rum', ...) in the first few tokens."""
    toks = _tokens(text)
    best, top = 0, 0.0
    for k in (1, 2, 3):
        if len(toks) < k: break
        ph = "".join(t for t, _ in toks[:k])
        if ph == "hey": continue
        sc = max(difflib.SequenceMatcher(None, ph, w).ratio() for w in WAKE - {"hey"})
        if k == 2 and toks[0][0] in ("hey", "hi", "okay", "ok") and toks[1][0].startswith(_N[:2]): sc = max(sc, 0.9)
        if sc >= 0.78 and sc > top + 0.001: best, top = k, sc
    if not best: return False, text
    return True, text[toks[best - 1][1]:].lstrip(" ,.:;-!?")

HALLUC = re.compile(r"thank(s| you)( so much| very much)?( for (watching|listening|your time|joining))?|"
                    r"(please )?(like and )?subscribe( to (my|the) channel)?|see you (in the )?next (time|video)|"
                    r"bye( bye)?|subtitles? (by|from)|amara\\.org|transcribed by|captions? by|you|i'?m sorry|sorry|please|okay|ok|yeah|hello|hi", re.I)

def is_junk(text: str) -> bool:
    t = text.strip().lower()
    plain = re.sub(r"[^\w\s']", "", t).strip()
    if not plain or plain in JUNK or t in JUNK: return True
    if HALLUC.fullmatch(plain) or re.fullmatch(r"(?:(?:" + HALLUC.pattern + r")\s*)+", plain, re.I): return True       # stock phrases the model invents on noise, alone or repeated
    if re.fullmatch(r"[\*\[\(♪].*[\*\]\)♪]", t): return True            # *Ding*, [Music]
    if len(re.findall(r"[a-z]", t)) < 0.6 * max(1, len(re.sub(r"[\s\W\d]", "", t))): return True   # non-Latin when STT_LANG=en
    if len(t.split()) < 3: return True
    if re.sub(r"[^a-z0-9]", "", t) == re.sub(r"[^a-z0-9]", "", config.STT_VOCAB.lower()): return True   # model echoed the vocabulary hint
    if len(t.split()) <= 8 and sum(v.strip().lower().replace("0g", "zero g") in t.replace("0g", "zero g") for v in config.STT_VOCAB.split(",")) >= 3: return True
    sents = [s.strip() for s in re.split(r"[.!?,]", t) if s.strip()]
    if len(sents) >= 2 and len(set(sents)) == 1 and len(t.split()) < 10: return True            # looped hallucination
    return False

def is_junk_phone(text: str) -> bool:
    """Button-press speech is always intended; only drop empties and echoed vocabulary."""
    t = text.strip().lower()
    return not re.search(r"[a-z]{2}", t) or re.sub(r"[^a-z0-9]", "", t) == re.sub(r"[^a-z0-9]", "", config.STT_VOCAB.lower())

# ---------- store ----------
def add_transcript(text, file, ts, kind="speech"):
    with _lock, _conn() as c:
        conv = None
        if kind == "speech":
            r = c.execute("SELECT id, end FROM convos ORDER BY id DESC LIMIT 1").fetchone()
            if r and ts - r["end"] <= config.CONVO_GAP_S:
                conv = r["id"]; c.execute("UPDATE convos SET end=?, summary=NULL WHERE id=?", (ts, conv))
            else:
                conv = c.execute("INSERT INTO convos(start,end) VALUES(?,?)", (ts, ts)).lastrowid
        c.execute("INSERT INTO transcripts(ts,text,file,kind,conv) VALUES(?,?,?,?,?)", (ts, text, file, kind, conv))
        return conv

def convo_text(conv_id):
    with _lock, _conn() as c:
        rows = c.execute("SELECT ts,text FROM transcripts WHERE conv=? ORDER BY ts", (conv_id,)).fetchall()
    return "\n".join(f"[{datetime.datetime.fromtimestamp(r['ts']):%H:%M:%S}] {r['text']}" for r in rows)

_cap = threading.local()

def notify(text, source="agent"):
    if getattr(_cap, 'out', None) is not None: _cap.out.append(text[:300])
    with _lock, _conn() as c:
        c.execute("INSERT INTO notifications(ts,text,source) VALUES(?,?,?)", (time.time(), text, source))
    log({"type": "notify", "source": source, "text": text})
    try: subprocess.Popen(["notify-send", "-a", config.AGENT_NAME.lower() + " agent", config.AGENT_NAME, text], stderr=subprocess.DEVNULL)
    except Exception: pass
    if config.NTFY_TOPIC:
        try: requests.post(f"https://ntfy.sh/{config.NTFY_TOPIC}", data=text.encode(), timeout=5)
        except Exception: pass

# ---------- actions ----------
def summarize_convo(conv_id, announce=True):
    txt = convo_text(conv_id)
    if not txt: notify("Nothing to summarize yet.", "summary"); return None
    try:
        s, rid = brain.summarize(txt)
    except Exception as e:
        notify(f"Could not summarize (0G unreachable: {str(e)[:80]}).", "degraded"); return None
    with _lock, _conn() as c: c.execute("UPDATE convos SET summary=? WHERE id=?", (s, conv_id))
    log({"type": "summary", "conv": conv_id, "summary": s, "request_id": rid}, True)
    if announce: notify("Summary: " + s, "summary")
    try:
        import seal; seal.seal(conv_id)                      # verifiable memory: hash now, anchor on 0G Storage in the background
    except Exception as e: print(f"[seal] {e}", flush=True)
    threading.Thread(target=extract_nudges, args=(conv_id,), daemon=True).start()
    return s

def extract_nudges(conv_id):
    """Proactive nudges: pull out things you said you would do and remind you once, later, without being asked."""
    txt = convo_text(conv_id)
    if not txt: return
    now = datetime.datetime.now()
    try: items = brain.commitments(txt, now.strftime("%Y-%m-%d %H:%M (%A)"))[0].get("items") or []
    except Exception as e: print(f"[nudge] extract failed: {e}", flush=True); return
    with _lock, _conn() as c:
        end = c.execute("SELECT end FROM convos WHERE id=?", (conv_id,)).fetchone()
        have = {r["text"].lower() for r in c.execute("SELECT text FROM nudges WHERE conv=?", (conv_id,))}
        for it in items[:3]:
            t = (it.get("text") or "").strip()
            if not t or t.lower() in have: continue
            try: due = datetime.datetime.fromisoformat(it["due"]).timestamp() if it.get("due") else None
            except Exception: due = None
            due = due or ((end["end"] if end else time.time()) + config.NUDGE_DELAY_S)
            c.execute("INSERT INTO nudges(created,conv,text,due) VALUES(?,?,?,?)", (time.time(), conv_id, t, due))
            log({"type": "nudge_planned", "text": t, "due": due}, False)

def _last_conv(exclude_active=False):
    """Most recent conversation with real content (skips stray one-liners)."""
    with _lock, _conn() as c:
        for r in c.execute("SELECT * FROM convos ORDER BY id DESC LIMIT 12").fetchall():
            n = sum(len(t["text"].split()) for t in c.execute("SELECT text FROM transcripts WHERE conv=? AND kind='speech'", (r["id"],)))
            if n >= 12: return r
        return c.execute("SELECT * FROM convos ORDER BY id DESC LIMIT 1").fetchone()

def transcript_text(conv_id=None, d_from=None, d_to=None, everything=False):
    """Full verbatim transcript: one conversation, a date range, or everything."""
    with _lock, _conn() as c:
        if d_from or d_to or everything:
            lo = datetime.datetime.fromisoformat(d_from).timestamp() if d_from else 0
            hi = datetime.datetime.fromisoformat(d_to).timestamp() + 86400 if d_to else time.time() + 1
            rows = c.execute("SELECT ts,text FROM transcripts WHERE kind='speech' AND ts BETWEEN ? AND ? ORDER BY ts", (lo, hi)).fetchall()
        else:
            if conv_id is None:
                r = c.execute("SELECT id FROM convos ORDER BY id DESC LIMIT 1").fetchone(); conv_id = r["id"] if r else None
            rows = c.execute("SELECT ts,text FROM transcripts WHERE kind='speech' AND conv=? ORDER BY ts", (conv_id,)).fetchall() if conv_id else []
    return "\n".join(f"[{datetime.datetime.fromtimestamp(r['ts']):%a %d %b %H:%M:%S}] {r['text']}" for r in rows)

def _search(query, d_from, d_to):
    lo = datetime.datetime.fromisoformat(d_from).timestamp() if d_from else time.time() - 30 * 86400
    hi = datetime.datetime.fromisoformat(d_to).timestamp() + 86400 if d_to else time.time() + 1
    with _lock, _conn() as c:
        rows = c.execute("SELECT ts,text FROM transcripts WHERE kind='speech' AND ts BETWEEN ? AND ? ORDER BY ts", (lo, hi)).fetchall()
        sums = c.execute("SELECT start,summary FROM convos WHERE summary IS NOT NULL AND start BETWEEN ? AND ?", (lo, hi)).fetchall()
    words = {w for w in re.findall(r"[a-z0-9]+", query.lower()) if len(w) > 3}
    scored = sorted(rows, key=lambda r: (-sum(w in r["text"].lower() for w in words), -r["ts"]))[:40]
    scored.sort(key=lambda r: r["ts"])
    out = [f"[{datetime.datetime.fromtimestamp(r['ts']):%Y-%m-%d %H:%M}] {r['text']}" for r in scored]
    out += [f"[summary {datetime.datetime.fromtimestamp(s['start']):%Y-%m-%d}] {s['summary']}" for s in sums[-10:]]
    return "\n".join(out)

LAST_AUDIO = 0.0

def _add_reminder(item, intent, text, beacons, acts):
    """Store a deferred step: fires on a time or a beacon event and then runs its hardware actions + notification."""
    msg = item.get("message") or text
    if item.get("about_last_conversation"):
        c = _last_conv()
        if c:
            s = c["summary"] or summarize_convo(c["id"], announce=False)
            if s:
                try: msg, _ = brain.write_reminder(msg, s)
                except Exception: msg = f"{msg} (re: {s[:120]})"
    if intent == "remind_time":
        try: due = datetime.datetime.fromisoformat(item["due"]).timestamp()
        except Exception: notify("I couldn't work out when to remind you.", "reminder"); return False
        kind, label = "time", None
    else:
        due, kind = None, "beacon_" + (item.get("beacon_trigger") or "lost")
        label = item.get("beacon_label") or (beacons[0] if beacons else None)
    with _lock, _conn() as c:
        c.execute("INSERT INTO reminders(created,kind,due,label,message,actions) VALUES(?,?,?,?,?,?)", (time.time(), kind, due, label, msg, json.dumps(acts)))
    when = f"at {datetime.datetime.fromtimestamp(due):%a %d %b %H:%M}" if due else f"when {label} goes {'out of range' if kind.endswith('lost') else 'back in range'}"
    extra = (" and will " + ", ".join(f"{x.get('do')} {x.get('value')}".strip() for x in acts)) if acts else ""
    notify(f"Reminder set {when}: {msg}{extra}", "reminder")
    log({"type": "reminder_set", "kind": kind, "due": due, "label": label, "message": msg}, True)
    return True

def run_command(text: str, source="voice", quiet_none=False, stt=None):
    """One decision episode: transcript -> 0G decision -> hardware/notify, logged to 0G Storage. If 0G is unreachable: safe-stop, no guess."""
    _cap.out = []; _cap.info = None
    try: _run_command(text, source, quiet_none)
    finally:
        info, out = _cap.info, _cap.out; _cap.out = None
        if info and info.get("intent") not in (None, "none"):
            try:
                import episodes
                episodes.record(text, source, stt, info.get("rid"), info.get("cmd"), info["intent"], info.get("acts"), out,
                                "safe_stop" if info.get("degraded") else "ok", info.get("degraded"))
            except Exception as e: print(f"[episodes] {e}", flush=True)

def _run_command(text: str, source="voice", quiet_none=False):
    try:
        import hardware; hardware.ack()                              # one buzz: transcribed, now working on it
    except Exception: pass
    now = datetime.datetime.now()
    beacons = list(config.WATCH.values())
    try:
        cmd, rid = brain.command(text, now.strftime("%Y-%m-%d %H:%M (%A)"), beacons)
    except Exception as e:
        try: import hardware; hardware.safe_stop()
        except Exception: pass
        _cap.info = {"intent": "safe_stop", "degraded": f"0g_unreachable: {str(e)[:120]}", "acts": [{"do": "safe_stop"}]}
        notify(f"Heard a command but 0G is unreachable ({str(e)[:80]}); safe-stopped, nothing done.", "degraded"); return
    intent = cmd.get("intent", "none")
    acts = [x for x in (cmd.get("actions") or []) if isinstance(x, dict)]
    if intent == "servo" and not acts: acts = [{"do": "servo", "value": cmd.get("query") or text}]
    if intent == "servo": intent = "hardware"
    for it in cmd.get("also") or []:                       # composite command: extra deferred steps ("... and open it again when X")
        if isinstance(it, dict) and it.get("intent") in ("remind_time", "remind_beacon"):
            _add_reminder(it, it["intent"], text, beacons, [x for x in (it.get("actions") or []) if isinstance(x, dict)])
    if acts and intent not in ("remind_time", "remind_beacon", "hardware"):      # any other command can also drive the hardware
        import hardware; hardware.run(acts)
    _cap.info = {"intent": intent, "acts": acts, "cmd": cmd, "rid": rid}
    log({"type": "command", "text": text, "intent": intent, "cmd": cmd, "request_id": rid, "source": source}, False)
    if intent == "summarize_last":
        c = _last_conv()
        if c: summarize_convo(c["id"])
        else: notify("No conversation recorded yet.", "summary")
    elif intent == "session_start":
        _kv("session_since", time.time())
        notify("Okay - I'll summarize it when the conversation ends.", "session")
    elif intent == "status":
        parts = []
        for k, b in (pres.state() if pres else {}).items():
            if b.get("present"):
                d = f", about {b['distance_m']} m away" if b.get("distance_m") is not None else ""
                parts.append(f"{k}: in range{d} ({b.get('rssi')} dBm, via {b.get('source')})")
            else: parts.append(f"{k}: not in range")
        try:
            m, ts = json.loads(_kv("hw") or "{}").get("servo", ["-", 0])
            parts.append("door: " + ("open" if m == "a90" else "closed" if m == "a0" else f"at {m[1:]} degrees" if m.startswith("a") and m[1:].isdigit()
                                     else "sweeping" if m == "sweep" and time.time() - ts < 8 else "not set (servo is showing the gauge)"))
        except Exception: pass
        parts.append("mics: streaming" if time.time() - LAST_AUDIO < 5 else "mics: no audio right now")
        with _lock, _conn() as c: n = c.execute("SELECT COUNT(*) FROM reminders WHERE fired=0").fetchone()[0]
        parts.append(f"{n} reminder{'s' if n != 1 else ''} pending")
        if _kv("session_since"): parts.append("recording a conversation to summarize")
        notify(". ".join(parts) + ".", "status")
    elif intent == "search":
        snip = _search(cmd.get("query") or text, cmd.get("date_from"), cmd.get("date_to"))
        if not snip: notify("I couldn't find anything about that.", "search"); return
        try: ans, _ = brain.answer(text, snip); notify(ans, "search")
        except Exception as e: notify(f"Search failed (0G: {str(e)[:80]}).", "degraded")
    elif intent == "transcript":
        t = transcript_text(d_from=cmd.get("date_from"), d_to=cmd.get("date_to"), everything=(cmd.get("message") or "").lower() == "all")
        notify("Transcript:\n" + t if t else "No transcript recorded for that.", "transcript")
    elif intent == "hardware":
        import hardware
        done = hardware.run(acts)
        notify(("Done: " + ", ".join(done) + ".") if done else "I couldn't tell which hardware to use.", "hardware")
    elif intent == "export":
        import seal
        c = _last_conv()
        if not c: notify("There's no conversation to export yet.", "export"); return
        if not c["summary"]: summarize_convo(c["id"], announce=False)
        notify("Saving that conversation to 0G Storage…", "export")
        s = seal.seal(c["id"], wait=True)
        if s and s["status"] == "sealed": notify(f"Saved to 0G Storage. Root {s['root'][:14]}… Proof: {seal.EXPLORER}{s['tx']}", "export")
        elif s and s["status"] == "uploading": notify(f"Sealed locally (sha256 {s['sha256'][:12]}…); the 0G upload is still running.", "export")
        else: notify(f"Couldn't upload to 0G ({(s or {}).get('error') or 'unknown error'}). The hash {s['sha256'][:12] if s else ''}… is kept.", "export")
    elif intent == "cancel_reminder":
        STOP = {"reminder", "reminders", "the", "about", "that", "remind", "delete", "remove", "cancel", "clear", "all", "every", "everything", "each",
                "any", "my", "me", "please", "can", "you", "to", "of", "for", "one", "last", "latest", "first", "set", "stop", "get", "rid", "and"}
        allwords = bool(re.search(r"\b(all|every|everything|each|entire|whole)\b", text.lower())) or (cmd.get("message") or "").lower() == "all"
        with _lock, _conn() as c:
            rows = c.execute("SELECT * FROM reminders ORDER BY fired, id DESC").fetchall()
            pend = [r for r in rows if not r["fired"]]
            words = [w for w in re.findall(r"[a-z0-9]{3,}", (text + " " + (cmd.get("query") or "")).lower()) if w not in STOP]
            sc = lambda r: sum(w in ((r["message"] or "") + " " + (r["label"] or "") + " " + (r["kind"] or "")).lower() for w in words)
            if allwords and not words: hit = rows
            elif words:
                best = max(pend or rows, key=sc, default=None); hit = [best] if best is not None and sc(best) else []
                if allwords: hit = [r for r in rows if sc(r)]
            else: hit = pend[:1]
            for r in hit: c.execute("DELETE FROM reminders WHERE id=?", (r["id"],))
        if hit: notify(f"Removed {len(hit)} reminder{'s' if len(hit) != 1 else ''}: " + "; ".join(r["message"] for r in hit), "reminder")
        else: notify("You have no reminders to remove." if not rows else "I couldn't find a matching reminder to remove.", "reminder")
    elif intent in ("remind_time", "remind_beacon"):
        _add_reminder(cmd, intent, text, beacons, acts)
    elif quiet_none:
        add_transcript("Hey " + text, "voice", time.time())          # "hey ..." that wasn't a command: just conversation
    else:
        notify(f"Didn't catch a command in: \"{text[:80]}\"", "command")

# ---------- pipeline hooks ----------
SOS_RE = re.compile(r"^\W*(s\W*o\W*s|mayday|sauce|sos please|send (an? )?sos|emergency)\W*$", re.I)

def _quick(rest: str, stt=None) -> bool:
    """One-word commands that must never wait for a second utterance or an LLM round trip."""
    if SOS_RE.match(rest.strip()):
        import hardware
        hardware.ack(); done = hardware.run([{"do": "sos"}]); msg = "SOS: " + ", ".join(done) + "."; notify(msg, "hardware")
        try:
            import episodes; episodes.record(rest, "voice", stt, None, {}, "sos", [{"do": "sos"}], [msg])
        except Exception as e: print(f"[episodes] {e}", flush=True)
        return True
    return False

def handle_text(text: str, file: str, ts: float = None, stt=None):
    ts = ts or time.time()
    wake, rest = split_wake(text)
    pending = float(_kv("wake_until") or 0) > ts
    if wake:
        _kv("wake_until", delete=True)
        if _quick(rest, stt): add_transcript(text, file, ts, "command"); return
        if len(rest.split()) >= 2: run_command(rest, stt=stt)
        else: _kv("wake_until", ts + 10); log({"type": "wake", "file": file}, False)
        add_transcript(text, file, ts, "command"); return
    toks = _tokens(text)
    if not wake and toks and toks[0][0] in ("hey", "hay", "hi", "okay", "ok") and len(toks) >= 2:      # loose wake: whisper often mangles "room"
        rest = text[toks[0][1]:].lstrip(" ,.:;-!?")
        if _quick(rest, stt): _kv("wake_until", delete=True); add_transcript(text, file, ts, "command"); return
        if len(rest.split()) >= 3 and not is_junk(text):
            _kv("wake_until", delete=True); add_transcript(text, file, ts, "command"); run_command(rest, quiet_none=True, stt=stt); return
        if len(rest.split()) < 3: _kv("wake_until", ts + 10); log({"type": "wake", "file": file, "loose": True}, False); return
    if pending and _quick(text, stt): _kv("wake_until", delete=True); add_transcript(text, file, ts, "command"); return
    if pending and len(text.split()) >= 2:
        _kv("wake_until", delete=True); add_transcript(text, file, ts, "command"); run_command(text, stt=stt); return
    if is_junk(text):
        log({"type": "ignored", "file": file, "text": text}, False); return
    add_transcript(text, file, ts)
    log({"type": "transcript", "file": file, "text": text})

def _fire(r, text):
    """A reminder fires: run its hardware actions (door, buzzer, LED), then notify."""
    import hardware
    try: acts = json.loads(r["actions"] or "[]")
    except Exception: acts = []
    done = hardware.run(acts) if acts else []
    notify(text + (f"  [{', '.join(done)}]" if done else ""), "reminder")

def on_beacon(ev):
    """beacon_lost / beacon_found event from presence.py."""
    kind = ev["type"]
    with _lock, _conn() as c:
        rows = c.execute("SELECT * FROM reminders WHERE fired=0 AND kind=? AND (label=? OR label IS NULL)", (kind, ev["label"])).fetchall()
        for r in rows: c.execute("UPDATE reminders SET fired=1 WHERE id=?", (r["id"],))
    for r in rows: _fire(r, f"Reminder: {r['message']}  [{ev['label']} {kind.split('_')[1]}]")
    if kind == "beacon_lost" and not rows:
        log({"type": "beacon_note", "label": ev["label"], "note": "lost, no reminder armed"}, False)

def tick():
    now = time.time()
    with _lock, _conn() as c:
        due = c.execute("SELECT * FROM reminders WHERE fired=0 AND kind='time' AND due<=?", (now,)).fetchall()
        for r in due: c.execute("UPDATE reminders SET fired=1 WHERE id=?", (r["id"],))
    for r in due: _fire(r, f"Reminder: {r['message']}")
    with _lock, _conn() as c:
        nd = c.execute("SELECT * FROM nudges WHERE fired=0 AND due<=? ORDER BY due LIMIT 1", (now,)).fetchone()
        if nd and now - float(_kv("last_nudge") or 0) >= 120:                 # at most one nudge every two minutes
            c.execute("UPDATE nudges SET fired=1 WHERE id=?", (nd["id"],))
        else: nd = None
    if nd:
        _kv("last_nudge", now); notify(f"Nudge: you said you'd {nd['text'][0].lower() + nd['text'][1:]}.", "nudge")
    since = _kv("session_since")
    if since:
        c = _last_conv()
        if c and c["end"] >= float(since) and now - c["end"] > config.CONVO_GAP_S:
            _kv("session_since", delete=True)
            threading.Thread(target=summarize_convo, args=(c["id"],), daemon=True).start()

LIVE = {"text": "", "ts": 0.0}
def set_live(text):
    LIVE["text"] = text or ""; LIVE["ts"] = time.time()
def wake_pending():
    return float(_kv("wake_until") or 0) > time.time()
def clear_all():
    """Delete every conversation, transcript, notification and reminder."""
    with _lock, _conn() as c:
        for t in ("transcripts", "convos", "notifications", "reminders", "seals", "nudges"): c.execute(f"DELETE FROM {t}")
        c.execute("DELETE FROM kv WHERE k IN ('session_since','wake_until')")
    set_live("")

def state():
    with _lock, _conn() as c:
        tr = [dict(r) for r in c.execute("SELECT ts,text,kind,conv FROM transcripts ORDER BY id DESC LIMIT 60")]
        cv = [dict(r) for r in c.execute("SELECT * FROM convos ORDER BY id DESC LIMIT 15")]
        rm = [dict(r) for r in c.execute("SELECT * FROM reminders ORDER BY id DESC LIMIT 20")]
        nt = [dict(r) for r in c.execute("SELECT * FROM notifications ORDER BY id DESC LIMIT 30")]
        try: ep = [dict(r) for r in c.execute("SELECT id,ts,text,intent,status,root,anchor_tx,upload FROM episodes ORDER BY id DESC LIMIT 12")]
        except sqlite3.OperationalError: ep = []
        for v in cv:
            r = c.execute("SELECT sha256,root,tx,status FROM seals WHERE conv=? ORDER BY id DESC LIMIT 1", (v["id"],)).fetchone()
            v["seal"] = dict(r) if r else None
    return {"now": time.time(), "transcripts": tr, "convos": cv, "reminders": rm, "notifications": nt, "episodes": ep,
            "beacons": pres.state() if pres else {}, "session": bool(_kv("session_since")),
            "live": {"text": LIVE["text"], "ts": LIVE["ts"]} if LIVE["text"] and time.time() - LIVE["ts"] < 10 else None}
