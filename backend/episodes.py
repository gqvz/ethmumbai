"""Physical-AI episode log: every decision Atlas makes (speech -> 0G STT request id -> 0G decision request id -> hardware actions)
is written as one canonical JSON episode, hashed, and uploaded to 0G Storage. Safe-stops (0G unreachable) are logged too, never faked."""
import json, os, tempfile, threading, time
import agent, seal, config
_chain = threading.Lock()      # one wallet: storage upload + anchor txs must not race on the nonce

def init():
    with agent._lock, agent._conn() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS episodes(id INTEGER PRIMARY KEY, ts REAL, source TEXT, text TEXT, intent TEXT, status TEXT,
                     stt_rid TEXT, dec_rid TEXT, sha256 TEXT, root TEXT, tx TEXT, upload TEXT, error TEXT, body TEXT, anchor_tx TEXT)""")
        cols = [r[1] for r in c.execute("PRAGMA table_info(episodes)")]
        if "anchor_tx" not in cols: c.execute("ALTER TABLE episodes ADD COLUMN anchor_tx TEXT")

def record(text, source, stt, dec_rid, cmd, intent, actions, outcome, status="ok", reason=None):
    ts = time.time()
    ep = {"v": 1, "kind": "decision_episode", "agent": config.AGENT_NAME, "ts": round(ts, 2), "source": source, "transcript": text,
          "stt": {"model": (stt or {}).get("model") or config.OG_STT_MODEL, "request_id": (stt or {}).get("request_id")},
          "decision": {"model": config.OG_CHAT_MODEL, "request_id": dec_rid, "intent": intent, "actions": actions or [],
                       "also": (cmd or {}).get("also") or []},
          "outcome": outcome, "status": status}
    if reason: ep["reason"] = reason
    body = seal.canonical(ep); h = seal.sha(body)
    with agent._lock, agent._conn() as c:
        eid = c.execute("INSERT INTO episodes(ts,source,text,intent,status,stt_rid,dec_rid,sha256,upload,body) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        (ts, source, text, intent, status, ep["stt"]["request_id"], dec_rid, h, "uploading", body.decode())).lastrowid
    threading.Thread(target=_upload, args=(eid,), daemon=True).start()
    return eid

def _upload(eid):
    with agent._lock, agent._conn() as c:
        r = c.execute("SELECT body FROM episodes WHERE id=?", (eid,)).fetchone()
    if not r: return
    path = None
    try:
        with tempfile.NamedTemporaryFile("wb", suffix=".json", delete=False) as f: f.write(r["body"].encode()); path = f.name
        with _chain:
            out = seal._node("upload", path)
            if "error" not in out and config.ANCHOR_ADDRESS:
                a = seal._node("anchor", str(eid), seal.sha(r["body"].encode()), out["rootHash"], timeout=120)
                if "anchorTx" in a: out["anchorTx"] = a["anchorTx"]
    except Exception as e: out = {"error": str(e)}
    finally:
        if path:
            try: os.unlink(path)
            except Exception: pass
    with agent._lock, agent._conn() as c:
        if "error" in out: c.execute("UPDATE episodes SET upload='failed', error=? WHERE id=?", (out["error"][:200], eid))
        else: c.execute("UPDATE episodes SET upload='sealed', root=?, tx=?, anchor_tx=?, error=NULL WHERE id=?", (out["rootHash"], out["txHash"], out.get("anchorTx"), eid))
    agent.log({"type": "episode", "id": eid, **{k: out.get(k) for k in ("rootHash", "txHash", "anchorTx", "error")}}, False)

def retry():
    """Called from the bridge tick: re-upload at most one failed episode per call (storage may have been unreachable)."""
    with agent._lock, agent._conn() as c:
        r = c.execute("SELECT id FROM episodes WHERE upload='failed' AND ts>? ORDER BY id LIMIT 1", (time.time() - 3600,)).fetchone()
        if r: c.execute("UPDATE episodes SET upload='uploading' WHERE id=?", (r["id"],))
    if r: threading.Thread(target=_upload, args=(r["id"],), daemon=True).start()

def listing(limit=30):
    with agent._lock, agent._conn() as c:
        rows = c.execute("SELECT id,ts,source,text,intent,status,stt_rid,dec_rid,sha256,root,tx,anchor_tx,upload,error FROM episodes ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return [{**dict(r), "explorer": (seal.EXPLORER + r["tx"]) if r["tx"] else None, "anchor_explorer": (seal.EXPLORER + r["anchor_tx"]) if r["anchor_tx"] else None} for r in rows]

def verify(eid):
    with agent._lock, agent._conn() as c:
        r = c.execute("SELECT * FROM episodes WHERE id=?", (eid,)).fetchone()
    if not r: return {"found": False}
    out = {"found": True, "id": eid, "sha256": r["sha256"], "root": r["root"], "tx": r["tx"], "local_match": seal.sha(r["body"].encode()) == r["sha256"], "remote_match": None}
    if r["root"]:
        tmp = tempfile.mktemp(suffix=".json")
        d = seal._node("download", r["root"], tmp, timeout=120)
        if "error" in d: out["remote_error"] = d["error"][:160]
        else:
            out["remote_match"] = seal.sha(open(tmp, "rb").read()) == r["sha256"]
            try: os.unlink(tmp)
            except Exception: pass
    out["verified"] = bool(out["remote_match"]) if out["remote_match"] is not None else out["local_match"]
    return out
