"""Stream WAV clips into the bridge exactly like the S3 does (mu-law packets over HTTP POST /audio), with silence between clips.
Usage: python3 feed_audio.py [--url http://127.0.0.1:8000] clip.wav pause:5 clip2.wav ...
Useful as an offline stand-in for the wireless mics."""
import struct, sys, time, urllib.request, wave
import numpy as np
from scipy.signal import resample_poly

PKT_SAMPLES, HDR = 238, struct.Struct("<BBHII")

def mulaw(x):
    x = x.astype(np.int32); s = x < 0
    mag = np.minimum(np.abs(x), 32635) + 0x84
    exp = np.clip(np.floor(np.log2(mag)).astype(np.int32) - 7, 0, 7)
    man = (mag >> (exp + 3)) & 0x0F
    return ((~((s.astype(np.int32) << 7) | (exp << 4) | man)) & 0xFF).astype(np.uint8)

def load(path):
    w = wave.open(path); n = w.getnchannels()
    x = np.frombuffer(w.readframes(w.getnframes()), np.int16).reshape(-1, n)[:, 0].astype(np.float32)
    return resample_poly(x, 16000, w.getframerate()).astype(np.int16)

def main():
    args = sys.argv[1:]; url = "http://127.0.0.1:8000"
    if args[:1] == ["--url"]: url, args = args[1], args[2:]
    pcm = []
    for a in args:
        pcm.append(np.zeros(int(float(a[6:]) * 16000), np.int16) if a.startswith("pause:") else load(a))
        pcm.append(np.zeros(16000 // 2, np.int16))
    x = np.concatenate(pcm); x = np.pad(x, (0, (-len(x)) % PKT_SAMPLES))
    pk = [mulaw(x[i:i + PKT_SAMPLES]).tobytes() for i in range(0, len(x), PKT_SAMPLES)]
    seq, t0 = (int(time.time()) % 40_000_000) * 100, time.time(); dt = PKT_SAMPLES / 16000
    for b in range(0, len(pk), 8):
        body = b"".join(HDR.pack(1, 0, PKT_SAMPLES, seq + b + i, int((time.time() - t0) * 1000) & 0xFFFFFFFF) + p for i, p in enumerate(pk[b:b + 8]))
        urllib.request.urlopen(urllib.request.Request(url + "/audio", body, {"X-Device": "feed"}), timeout=5).read()
        wait = t0 + (b + 8) * dt - time.time()
        if wait > 0: time.sleep(wait)
    print("fed", round(len(x) / 16000, 1), "s")

main()
