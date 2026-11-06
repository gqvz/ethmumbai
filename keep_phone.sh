#!/bin/bash
# Keeps the USB route to the phone alive: the app then talks to the bridge at http://127.0.0.1:8000 over the cable (no WiFi / Tailscale needed).
ADB=/home/gqvz/.local/share/android-sdk/platform-tools/adb
while true; do $ADB reverse tcp:8000 tcp:8000 >/dev/null 2>&1; sleep 5; done
