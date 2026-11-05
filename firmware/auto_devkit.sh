#!/bin/bash
# waits for the ESP32 devkit (CP2102) to appear, flashes brain firmware, runs a 25 s capture test
P=/dev/serial/by-id/usb-Silicon_Labs_CP2102_USB_to_UART_Bridge_Controller_0001-if00-port0
OUT=/home/gqvz/dev/ethmumbai/firmware/devkit_autotest.log
for i in $(seq 1 3000); do
  if [ -e "$P" ]; then
    sleep 5
    echo "devkit appeared $(date)" > $OUT
    (cd /home/gqvz/dev/ethmumbai/firmware/esp32_brain && pio run -t upload >> $OUT 2>&1)
    sleep 3
    (cd /home/gqvz/dev/ethmumbai/backend && python3 capture_test.py 25 esp32=$P >> $OUT 2>&1)
    echo "done $(date)" >> $OUT
    exit 0
  fi
  sleep 10
done
echo "devkit never appeared" > $OUT
