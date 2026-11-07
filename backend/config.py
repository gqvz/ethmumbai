import os, pathlib

ROOT = pathlib.Path(__file__).resolve().parent
DATA = ROOT / "data"

def _load_env():
    p = ROOT / ".env"
    if p.exists():
        for line in p.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))
_load_env()

BY_ID = "/dev/serial/by-id/"
PORTS = {  # name -> serial port (all at 460800)
    "esp32": os.getenv("PORT_ESP32", BY_ID + "usb-Silicon_Labs_CP2102_USB_to_UART_Bridge_Controller_0001-if00-port0"),
}
HTTP_BIND = os.getenv('HTTP_BIND', '0.0.0.0')  # ESPs POST here over plain HTTP (the laptop on your Wi-Fi)
HTTP_PORT = int(os.getenv('HTTP_PORT', '8000'))
BAUD = 460800
SR = 16000

# 0G Private Computer (OpenAI-compatible router)
OG_BASE = os.getenv("OG_BASE_URL", "https://router-api.0g.ai/v1")
OG_KEY = os.getenv("OG_API_KEY", "")
OG_STT_MODEL = os.getenv("OG_STT_MODEL", "whisper-large-v3")
OG_STT_FALLBACK = os.getenv("OG_STT_FALLBACK", "qwen3.5-omni-flash")  # used only when whisper errors
OG_CHAT_MODEL = os.getenv("OG_CHAT_MODEL", "qwen3.5-flash")  # id confirmed on pc.0g.ai

# BLE beacons we care about: address -> label
WATCH = {"dc:0d:30:08:be:e3": "FSC_BP103", "44:a4:80:18:7e:90": "Rockerz 512 ANC"}   # headphones: proximity only (BLE address of "512ANC_BLE")
for _p in filter(None, os.getenv("EXTRA_WATCH", "").split(",")):   # "addr=label,addr=label" for testing with other devices
    _a, _, _l = _p.partition("="); WATCH[_a.strip().lower()] = _l.strip() or _a.strip()
BEACON_ABSENT_S = float(os.getenv("BEACON_ABSENT_S", "25"))   # silence that counts as "left"
BEACON_FAR_RSSI = float(os.getenv("BEACON_FAR_RSSI", "-85"))  # smoothed RSSI weaker than this counts as "left"
BEACON_TX_POWER = float(os.getenv("BEACON_TX_POWER", "-59"))  # RSSI at 1 m, for the distance estimate
BEACON_PATH_LOSS = float(os.getenv("BEACON_PATH_LOSS", "2.2"))
LAPTOP_BLE = os.getenv("LAPTOP_BLE", "1") == "1"              # also scan with the laptop's own Bluetooth

# VAD / segmentation
VAD_FRAME_S = 0.1
VAD_MIN_RMS = 350        # absolute floor (after mu-law decode, before gain)
VAD_RATIO = 3.0          # x noise floor
VAD_HANG_S = 1.2
SEG_MIN_S = 0.8
SEG_MIN_P90 = 360        # a real segment must have loud frames (speech p90 frame RMS ~1200+, room noise <400)
SEG_MIN_ACTIVE = 0.12    # and at least this share of frames above 300 RMS
SEG_MAX_S = 30.0
ARCHIVE_CHUNK_S = 30

# voice agent
STT_LANG = os.getenv("STT_LANG", "en")
CONVO_GAP_S = float(os.getenv("CONVO_GAP_S", "90"))   # silence that ends a conversation
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "")               # optional push (ntfy.sh/<topic>); off by default
AGENT_NAME = os.getenv("AGENT_NAME", "Atlas")     # the wake word: "Hey <name>"
STT_VOCAB = os.getenv("STT_VOCAB", "0G, ETHGlobal, ENS, " + AGENT_NAME)

def post(*a, **k):
    """requests.post that rides out short network blips (DNS / connection errors) instead of dropping the segment."""
    import requests, time
    for i in range(5):
        try: return requests.post(*a, **k)
        except requests.ConnectionError:
            if i == 4: raise
            time.sleep(2)

ANCHOR_ADDRESS = os.getenv("ANCHOR_ADDRESS", "")   # EpisodeAnchor on 0G Galileo (og/deploy.mjs)
NUDGE_DELAY_S = float(os.getenv("NUDGE_DELAY_S", "600"))   # how long after a conversation ends before a nudge about something you said you would do
