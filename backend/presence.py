"""Track watched BLE beacons; emit found/lost transitions and estimate distance from smoothed RSSI."""
import time
import config

class Presence:
    def __init__(self):
        self.last_seen = {}   # addr -> (t, raw rssi)
        self.ema = {}         # addr -> smoothed rssi
        self.src = {}         # addr -> "esp" | "laptop"
        self.present = {}     # addr -> bool

    def update(self, ble: dict, src: str = "esp"):
        a = ble["addr"].lower()
        if a not in config.WATCH:
            return
        now, r = time.time(), ble["rssi"]
        prev = self.ema.get(a)
        stale = a not in self.last_seen or now - self.last_seen[a][0] > 10
        self.ema[a] = r if prev is None or stale else 0.7 * prev + 0.3 * r
        self.last_seen[a] = (now, r)
        self.src[a] = src

    def tick(self):
        """Return list of transition events. Lost = silent too long, or smoothed signal weaker than BEACON_FAR_RSSI."""
        ev, now = [], time.time()
        for a, label in config.WATCH.items():
            fresh = a in self.last_seen and now - self.last_seen[a][0] < config.BEACON_ABSENT_S
            e = self.ema.get(a)
            margin = 6 if self.present.get(a) is False else 0      # hysteresis so it does not flap at the edge
            seen = bool(fresh and e is not None and e > config.BEACON_FAR_RSSI + margin)
            if seen != self.present.get(a, None):
                if a in self.present or seen:
                    ev.append({"type": "beacon_found" if seen else "beacon_lost", "addr": a, "label": label,
                               "rssi": self.last_seen.get(a, (0, None))[1], "distance_m": self.distance(a)})
                self.present[a] = seen
        return ev

    def distance(self, a):
        """Log-distance path loss estimate in metres (rough: BLE RSSI is noisy)."""
        e = self.ema.get(a)
        return None if e is None else round(10 ** ((config.BEACON_TX_POWER - e) / (10 * config.BEACON_PATH_LOSS)), 1)

    def state(self):
        now = time.time()
        return {config.WATCH[a]: {"present": self.present.get(a, False),
                                  "rssi": self.last_seen[a][1] if a in self.last_seen else None,
                                  "distance_m": self.distance(a) if a in self.last_seen else None,
                                  "source": self.src.get(a),
                                  "age_s": round(now - self.last_seen[a][0], 1) if a in self.last_seen else None}
                for a in config.WATCH}
