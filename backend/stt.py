"""Speech-to-text via 0G Private Computer: whisper-large-v3, falling back to an omni chat model if whisper is down."""
import base64, io, time, wave, requests
import numpy as np
import config

def to_wav_bytes(pcm: np.ndarray, sr: int = config.SR) -> bytes:
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.astype(np.int16).tobytes())
    return b.getvalue()

def normalize(pcm: np.ndarray, target_rms: float = 3000.0) -> np.ndarray:
    x = pcm.astype(np.float32)
    r = np.sqrt(np.mean(x ** 2)) + 1e-6
    return np.clip(x * min(target_rms / r, 30.0), -32000, 32000).astype(np.int16)

_whisper_down_until = 0.0

def seg_stats(pcm: np.ndarray):
    x = pcm.astype(np.float32); n = len(x) // 1600
    if n < 5: return 0.0, 0.0
    r = np.sqrt((x[:n * 1600].reshape(n, 1600) ** 2).mean(1))
    return float(np.percentile(r, 90)), float((r > 300).mean())

def is_speech(pcm: np.ndarray) -> bool:
    """Gate: reject room noise / very quiet segments before they ever reach STT (the model hallucinates on them)."""
    x = pcm.astype(np.float32); n = len(x) // 1600
    if n < 5: return False
    r = np.sqrt((x[:n * 1600].reshape(n, 1600) ** 2).mean(1))
    return float(np.percentile(r, 90)) >= config.SEG_MIN_P90 and float((r > 300).mean()) >= config.SEG_MIN_ACTIVE

def highpass(pcm: np.ndarray, sr: int = config.SR, fc: float = 110.0) -> np.ndarray:
    from scipy.signal import butter, sosfilt
    return sosfilt(butter(4, fc, "highpass", fs=sr, output="sos"), pcm.astype(np.float32)).astype(np.int16)

def collapse_loops(text: str) -> str:
    """Whisper sometimes loops one phrase hundreds of times; keep a single copy of any repeated run."""
    import re
    sents = re.findall(r"[^.!?]+[.!?]?", text); out = []
    for s in sents:
        k = s.strip().lower()
        if k and out and out[-1].strip().lower() == k: continue
        out.append(s)
    t = "".join(out).strip()
    w = t.split()
    for n in range(1, 9):                                    # repeated word n-grams ("sorry sorry sorry")
        i = 0; res = []
        while i < len(w):
            res.append(w[i]); i += 1
            if len(res) >= 2 * n and [x.lower() for x in res[-n:]] == [x.lower() for x in res[-2 * n:-n]] and n >= 1 and len(res) >= 3 * n and [x.lower() for x in res[-n:]] == [x.lower() for x in res[-3 * n:-2 * n]]:
                del res[-n:]
        w = res
    return " ".join(w)

def _omni(wav: bytes):
    uri = "data:audio/wav;base64," + base64.b64encode(wav).decode()
    r = config.post(
        f"{config.OG_BASE}/chat/completions", headers={"Authorization": f"Bearer {config.OG_KEY}"},
        json={"model": config.OG_STT_FALLBACK, "max_tokens": 300, "messages": [{"role": "user", "content": [
            {"type": "input_audio", "input_audio": {"data": uri, "format": "wav"}},
            {"type": "text", "text": "Transcribe this English speech exactly. Output only the words spoken, nothing else. If there is no speech, output nothing."}]}]},
        timeout=60,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip(), {"model": config.OG_STT_FALLBACK}

def transcribe(pcm: np.ndarray):
    t, m = _transcribe(pcm); return collapse_loops(t), m

def _transcribe(pcm: np.ndarray):
    """Returns (text, meta). Raises on network/API failure; caller must handle (no fake output)."""
    if not config.OG_KEY:
        raise RuntimeError("OG_API_KEY not set")
    wav = to_wav_bytes(normalize(highpass(pcm)))
    global _whisper_down_until
    if config.OG_STT_FALLBACK and time.time() < _whisper_down_until:     # circuit breaker: whisper failed recently
        return _omni(wav)
    try:
        return _whisper(wav)
    except requests.RequestException as e:
        if not config.OG_STT_FALLBACK:
            raise
        _whisper_down_until = time.time() + 30
        print(f"[stt] whisper failed ({e}); using {config.OG_STT_FALLBACK} for 30 s", flush=True)
        return _omni(wav)

def _whisper(wav: bytes):
    r = config.post(
        f"{config.OG_BASE}/audio/transcriptions",
        headers={"Authorization": f"Bearer {config.OG_KEY}", "X-0G-Provider-Trust-Mode": "private"},
        files={"file": ("seg.wav", wav, "audio/wav")},
        data={"model": config.OG_STT_MODEL, "language": config.STT_LANG, "prompt": f"Hey {config.AGENT_NAME}, summarize the last conversation. Remind me to ... " + config.STT_VOCAB},
        timeout=15,
    )
    r.raise_for_status()
    j = r.json()
    return j.get("text", "").strip(), {"request_id": r.headers.get("x-request-id") or r.headers.get("request-id"), "raw_keys": list(j)}
