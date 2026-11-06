"""Capture N seconds from one or more serial ports; save WAV + per-second stats."""
import sys, time, threading, wave, numpy as np, serial
from frames import *

def run(port, name, secs, out):
    s = serial.Serial(port, 460800, timeout=0.2)
    s.dtr = False; s.rts = True; time.sleep(.1); s.rts = False
    p = FrameParser()
    pcm, seqs, stats, ble = [], [], None, {}
    t0 = time.time()
    while time.time() - t0 < secs:
        for pl in p.feed(s.read(4096)):
            if pl[0] == AUDIO:
                seq, ts, d = parse_audio(pl); seqs.append(seq); pcm.append((seq, decode_mulaw(d)))
            elif pl[0] == STATS: stats = parse_stats(pl)
            elif pl[0] == BLE:
                b = parse_ble(pl); ble[b["addr"]] = b
    out[name] = dict(pcm=pcm, seqs=seqs, stats=stats, bad=p.bad, ble=ble)

if __name__ == "__main__":
    secs = int(sys.argv[1]); ports = dict(a.split("=") for a in sys.argv[2:])
    out, th = {}, []
    for n, pt in ports.items():
        t = threading.Thread(target=run, args=(pt, n, secs, out)); t.start(); th.append(t)
    for t in th: t.join()
    for n, r in out.items():
        seqs = r["seqs"]
        miss = (max(seqs) - min(seqs) + 1 - len(set(seqs))) if seqs else 0
        print(f"{n}: audio_pkts={len(seqs)} missing={miss} serial_bad_frames={r['bad']} esp_stats(rx,drop)={r['stats']} ble_devices={len(r['ble'])}")
        if seqs:
            d = dict(r["pcm"]); lo, hi = min(d), max(d)
            sig = np.concatenate([d.get(i, np.zeros(238, np.int16)) for i in range(lo, hi + 1)])
            with wave.open(f"/tmp/claude-1000/-home-gqvz-dev-ethmumbai/1a25d051-3d75-46ce-bc2f-593fff16dbef/scratchpad/{n}.wav", "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(sig.tobytes())
            sec = 16000
            print("  rms/s:", [int(np.sqrt(np.mean(sig[i:i+sec].astype(float)**2))) for i in range(0, len(sig) - sec + 1, sec)])
