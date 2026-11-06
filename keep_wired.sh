#!/bin/bash
# Keeps the wired uplink alive: reconnects "Wired connection 1" (enp1s0) whenever it drops, or when it says connected but DNS is dead.
CON="${1:-Wired connection 1}"; DEV="${2:-enp1s0}"; bad=0
while true; do
  state=$(nmcli -t -f DEVICE,STATE dev | grep "^$DEV:" | cut -d: -f2)
  if [ "$state" != "connected" ]; then
    echo "$(date +%T) $DEV is '$state', bringing '$CON' up"; nmcli con up "$CON" ifname "$DEV" >/dev/null 2>&1 && echo "$(date +%T) up" || echo "$(date +%T) failed"
    bad=0
  elif getent hosts router-api.0g.ai >/dev/null 2>&1 || ping -c1 -W2 1.1.1.1 >/dev/null 2>&1; then bad=0
  else
    bad=$((bad+1))
    if [ $bad -ge 3 ]; then echo "$(date +%T) connected but no internet, reconnecting"; nmcli con down "$CON" >/dev/null 2>&1; nmcli con up "$CON" ifname "$DEV" >/dev/null 2>&1; bad=0; fi
  fi
  sleep 3
done
