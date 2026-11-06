"""Laptop Bluetooth as a beacon scanner (BlueZ via bluetoothctl): used when no ESP32 BLE board is attached."""
import re, subprocess, time
import config

RSSI = re.compile(r"Device ([0-9A-Fa-f:]{17}) RSSI: .*?\((-?\d+)\)")
ANSI = re.compile(r"\x1b\[[0-9;]*m|\r")

def run(on_adv, stop):
    watch = {a.lower() for a in config.WATCH}
    while not stop.is_set():
        p = None
        try:
            p = subprocess.Popen(["bluetoothctl"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
            for cmd in ("power on", "menu scan", "transport le", "duplicate-data on", "back", "scan on"):
                p.stdin.write(cmd + "\n"); p.stdin.flush(); time.sleep(0.15)
            print("[ble] laptop bluetooth scanning", flush=True)
            for line in p.stdout:
                m = RSSI.search(ANSI.sub("", line))
                if m and m.group(1).lower() in watch:
                    on_adv(m.group(1).lower(), int(m.group(2)))
                if stop.is_set():
                    break
        except Exception as e:
            print(f"[ble] laptop scan error: {e}; retrying", flush=True)
        finally:
            if p:
                try: p.stdin.write("scan off\nquit\n"); p.stdin.flush()
                except Exception: pass
                p.terminate()
        time.sleep(3)
