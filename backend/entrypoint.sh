#!/bin/bash
set -e

echo "[Container Entrypoint] Initializing Zoom Bot & Recording Studio environment..."

# 1. Start virtual X11 display if not running
export DISPLAY=${DISPLAY:-":99"}
if ! pgrep -x "Xvfb" > /dev/null; then
    echo "[Entrypoint] Starting Xvfb on $DISPLAY with resolution 1280x720x24..."
    rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null || true
    Xvfb "$DISPLAY" -screen 0 1280x720x24 -ac +extension GLX +render -noreset &
    sleep 1
fi

# 2. Initialize PulseAudio with virtual speaker sink (zoomrec architecture)
echo "[Entrypoint] Configuring PulseAudio virtual audio devices..."
rm -rf /var/run/pulse /var/lib/pulse ~/.config/pulse 2>/dev/null || true

if ! pulseaudio --check 2>/dev/null; then
    pulseaudio -D --exit-idle-time=-1 --log-level=error || true
    sleep 1
fi

# Create virtual speaker sink for Zoom output -> FFmpeg capture
pactl load-module module-null-sink sink_name=speaker sink_properties=device.description="speaker" 2>/dev/null || true
pactl set-default-sink speaker 2>/dev/null || true

# Create virtual microphone sink for participant loopback if needed
pactl load-module module-null-sink sink_name=microphone sink_properties=device.description="microphone" 2>/dev/null || true
pactl set-source-volume 1 100% 2>/dev/null || true

echo "[Entrypoint] PulseAudio virtual sinks initialized: default sink -> speaker"

# 3. Create required directories
mkdir -p /app/recordings /app/data /app/storage /app/sessions

# 4. Start Node.js backend and bot studio
echo "[Entrypoint] Starting Node.js unified server on port ${PORT:-3000}..."
exec node src/index.js
