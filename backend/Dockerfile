# ── Zoom Bot Meeting Recorder & Cloud Server Dockerfile ──
# Enhanced with zoomrec native Zoom + PulseAudio architecture
FROM node:20-bullseye-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:99 \
    PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium \
    PORT=3000 \
    NODE_ENV=production

# 1. Install base utilities, X11, PulseAudio, FFmpeg, and Python
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    curl \
    ca-certificates \
    pulseaudio \
    pulseaudio-utils \
    pavucontrol \
    xvfb \
    x11-xserver-utils \
    xauth \
    ffmpeg \
    chromium \
    python3 \
    python3-pip \
    python3-dev \
    fonts-freefont-ttf \
    fonts-noto-color-emoji \
    fonts-dejavu-core \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcups2 \
    libdbus-1-3 \
    libgdk-pixbuf2.0-0 \
    libnspr4 \
    libnss3 \
    libx11-xcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    libxss1 \
    libxtst6 \
    libgl1-mesa-glx \
    libegl1-mesa \
    libxi6 \
    libsm6 \
    libxrender1 \
    libpulse0 \
    libxslt1.1 \
    libsqlite3-0 \
    libxcb-keysyms1 \
    libxcb-xtest0 \
    libxcb-xinerama0 \
    libxcb-shape0 \
    libxcb-shm0 \
    libxcb-xfixes0 \
    libxcb-randr0 \
    libxcb-image0 \
    && rm -rf /var/lib/apt/lists/*

# 2. Install official native Zoom Linux desktop client
RUN wget -q -O /tmp/zoom_amd64.deb https://cdn.zoom.us/prod/5.13.4.711/zoom_amd64.deb && \
    (dpkg -i /tmp/zoom_amd64.deb || apt-get install -y -f) && \
    rm -f /tmp/zoom_amd64.deb

# 3. Install Python automation dependencies (zoomrec matching)
RUN pip3 install --no-cache-dir \
    pyautogui \
    opencv-python-headless \
    pillow \
    psutil \
    requests

WORKDIR /app

# 4. Install Node dependencies
COPY package*.json ./
RUN npm ci --omit=dev || npm install --production
RUN npx playwright install ffmpeg || true

# 5. Copy application source code and template images
COPY . .

# 6. Ensure runtime directories exist and permissions are set
RUN mkdir -p /app/recordings /app/data /app/storage /app/sessions /app/res/img && \
    chmod +x /app/entrypoint.sh /app/src/native_zoom_runner.py

EXPOSE 3000

# 7. Start container via entrypoint (launches Xvfb + PulseAudio + Node.js)
ENTRYPOINT ["/app/entrypoint.sh"]
