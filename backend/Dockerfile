# ── Zoom Bot Cloud Server Dockerfile ──
# Optimized for Render.com / cloud deployment with Playwright headless browser
FROM node:20-bullseye-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DISPLAY=:99 \
    PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=0 \
    PORT=3000 \
    NODE_ENV=production

# 1. Install base utilities, X11 virtual display, PulseAudio, and FFmpeg
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    curl \
    ca-certificates \
    pulseaudio \
    pulseaudio-utils \
    xvfb \
    x11-xserver-utils \
    xauth \
    ffmpeg \
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
    libgbm1 \
    libdrm2 \
    libxcb-dri3-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 2. Install Node dependencies
COPY package*.json ./
RUN npm ci --omit=dev || npm install --production

# 3. Install Playwright Chromium browser (headless for cloud)
RUN npx playwright install chromium --with-deps || true
RUN npx playwright install-deps chromium || true

# 4. Copy application source code
COPY . .

# 5. Ensure runtime directories exist
RUN mkdir -p /app/recordings /app/data /app/storage /app/sessions && \
    chmod +x /app/entrypoint.sh

EXPOSE 3000

# 6. Start container via entrypoint (launches Xvfb + PulseAudio + Node.js)
ENTRYPOINT ["/app/entrypoint.sh"]
