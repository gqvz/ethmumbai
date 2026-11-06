"""Pretend to be the devkit's BLE scanner: POST /ble lines for the watched beacon. Usage: python3 fake_beacon.py <start_rssi> <end_rssi> <seconds> [hold_seconds]
Ramps RSSI from start to end (a walk away), then keeps going quiet (no posts) for 'hold' seconds if given a negative end of 0."""
import sys, time, urllib.request
a, b, secs = float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
t0 = time.time()
while time.time() - t0 < secs:
    r = int(a + (b - a) * (time.time() - t0) / secs)
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8000/ble", f"dc:0d:30:08:be:e3|{r}|FSC_BP103||\n".encode(), {"X-Device": "fake"}), timeout=3).read()
    time.sleep(1)
print("done at", b)
