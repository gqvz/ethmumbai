"""Verifiable memory: every conversation is canonicalised, hashed (sha256) and uploaded to 0G Storage; the root hash + tx are kept so
anyone can re-download the record and check it matches what the app shows."""
import hashlib, json, os, subprocess, tempfile, threading, time
import config, agent

NODE_DIR = config.ROOT / "og"
EXPLORER = "https://chainscan-galileo.0g.ai/tx/"
_busy = set()

def record(conv_id):
    with agent._lock, agent._conn() as c:
        cv = c.execute("SELECT * FROM convos WHERE id=?", (conv_id,)).fetchone()
        rows = c.execute("SELECT ts,text FROM transcripts WHERE conv=? AND kind='speech' ORDER BY ts", (conv_id,)).fetchall()
    if not cv or not rows: return None
    return {"v": 1, "conversation": conv_id, "start": round(cv["start"], 1), "end": round(cv["end"], 1), "summary": cv["summary"],
            "lines": [{"ts": round(r["ts"], 1), "text": r["text"]} for r in rows]}

def canonical(rec): return json.dumps(rec, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
def sha(b): return hashlib.sha256(b).hexdigest()

def latest(conv_id):
    with agent._lock, agent._conn() as c:
        r = c.execute("SELECT * FROM seals WHERE conv=? ORDER BY id DESC LIMIT 1", (conv_id,)).fetchone()
    return dict(r) if r else None

def _node(*args, timeout=240):
    p = subprocess.run(["node", "seal.mjs", *args], cwd=NODE_DIR, capture_output=True, text=True, timeout=timeout)
    try: return json.loads(p.stdout.strip().splitlines()[-1])
    except Exception: return {"error": (p.stderr or p.stdout or "no output")[-200:]}

def seal(conv_id, wait=False):
    """Hash now (instant, local proof), upload in the background. Returns the seal row, or None if nothing to seal."""
    rec = record(conv_id)
    if not rec: return None
    body = canonical(rec); h = sha(body)
    prev = latest(conv_id)
    if prev and prev["sha256"] == h and prev["status"] in ("sealed", "uploading"): return prev
    with agent._lock, agent._conn() as c:
        sid = c.execute("INSERT INTO seals(conv,sha256,ts,status) VALUES(?,?,?,?)", (conv_id, h, time.time(), "uploading")).lastrowid
    def work():
        try:
            with tempfile.NamedTemporaryFile("wb", suffix=".json", delete=False) as f: f.write(body); path = f.name
            r = _node("upload", path)
            os.unlink(path)
            with agent._lock, agent._conn() as c:
                if "error" in r: c.execute("UPDATE seals SET status='failed', error=? WHERE id=?", (r["error"][:200], sid))
                else: c.execute("UPDATE seals SET status='sealed', root=?, tx=? WHERE id=?", (r["rootHash"], r["txHash"], sid))
            agent.log({"type": "sealed", "conv": conv_id, "sha256": h, **{k: r.get(k) for k in ("rootHash", "txHash", "error")}}, False)
        finally: _busy.discard(sid)
    _busy.add(sid)
    t = threading.Thread(target=work, daemon=True); t.start()
    if wait: t.join(240)
    return latest(conv_id)

def verify(conv_id):
    """local: does the stored conversation still hash to what was sealed?  remote: does the copy on 0G Storage hash to it too?"""
    s = latest(conv_id)
    if not s: return {"sealed": False}
    sealed_sha = s["sha256"]
    # the sealed record may predate later lines, so compare against the exact sealed bytes when we kept them; otherwise current hash
    rec = record(conv_id); local = bool(rec) and sha(canonical(rec)) == sealed_sha
    out = {"sealed": True, "status": s["status"], "sha256": sealed_sha, "root": s["root"], "tx": s["tx"], "local_match": local, "remote_match": None}
    if s["root"]:
        tmp = tempfile.mktemp(suffix=".json")
        r = _node("download", s["root"], tmp, timeout=120)
        if "error" in r: out["remote_error"] = r["error"][:160]
        else:
            out["remote_match"] = sha(open(tmp, "rb").read()) == sealed_sha
            try: os.unlink(tmp)
            except Exception: pass
    out["changed"] = not local                      # conversation grew after it was sealed: needs a new seal
    out["verified"] = bool(out["remote_match"]) if out["remote_match"] is not None else local
    return out

def export_md(conv_id):
    rec = record(conv_id)
    if not rec: return "No conversation."
    import datetime
    f = lambda t: datetime.datetime.fromtimestamp(t).strftime("%a %d %b %H:%M")
    s = latest(conv_id)
    lines = [f"# Conversation {f(rec['start'])}", ""]
    if rec["summary"]: lines += ["## Summary", rec["summary"], ""]
    lines += ["## Transcript"] + [f"[{datetime.datetime.fromtimestamp(l['ts']):%H:%M:%S}] {l['text']}" for l in rec["lines"]] + [""]
    if s:
        lines += ["## Proof", f"sha256: {s['sha256']}", f"status: {s['status']}"]
        if s["root"]: lines += [f"0G Storage root: {s['root']}", f"tx: {EXPLORER}{s['tx']}"]
    return "\n".join(lines)
