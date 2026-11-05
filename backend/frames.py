"""Frame parser for the ESP serial protocol: A5 5A <len> <payload> <crc8>."""
import struct

def crc8(data: bytes) -> int:
    c = 0
    for b in data:
        c ^= b
        for _ in range(8):
            c = ((c << 1) ^ 0x07) & 0xFF if c & 0x80 else (c << 1) & 0xFF
    return c

class FrameParser:
    def __init__(self):
        self.buf = bytearray()
        self.bad = 0

    def feed(self, data: bytes):
        self.buf += data
        out = []
        while True:
            i = self.buf.find(b"\xa5\x5a")
            if i < 0:
                del self.buf[:-1]
                break
            if i:
                del self.buf[:i]
            if len(self.buf) < 3:
                break
            n = self.buf[2]
            if len(self.buf) < 4 + n:
                break
            payload = bytes(self.buf[3:3 + n])
            if crc8(payload) == self.buf[3 + n]:
                out.append(payload)
                del self.buf[:4 + n]
            else:
                self.bad += 1
                del self.buf[:2]
        return out

# payload types
AUDIO, BLE, STATS = 1, 2, 3
AUDIO_HDR = struct.Struct("<BBHII")  # type, flags, len, seq, ts_ms

def parse_audio(p: bytes):
    t, flags, ln, seq, ts = AUDIO_HDR.unpack_from(p)
    return seq, ts, p[AUDIO_HDR.size:AUDIO_HDR.size + ln]

def parse_stats(p: bytes):
    return struct.unpack_from("<II", p, 1)  # rx, dropped

def parse_ble(p: bytes):
    addr, rssi, name, mfg, uuid = (p[1:].decode(errors="replace").split("|") + [""] * 5)[:5]
    return dict(addr=addr, rssi=int(rssi or 0), name=name, mfg=mfg, uuid=uuid)

# mu-law decode table
def _mulaw_dec(u: int) -> int:
    u = ~u & 0xFF
    sign, exp, man = u & 0x80, (u >> 4) & 7, u & 0x0F
    s = (((man << 3) + 0x84) << exp) - 0x84
    return -s if sign else s

MULAW = [_mulaw_dec(i) for i in range(256)]

def decode_mulaw(b: bytes):
    import numpy as np
    return np.array([MULAW[x] for x in b], dtype=np.int16)
