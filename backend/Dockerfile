# ── Zoom Bot Meeting Recorder & Cloud Server Dockerfile ──
FROM node:20-bullseye-slim

# Install Chromium browser, FFmpeg, Xvfb, and audio/graphics libraries
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium \
    ffmpeg \
    xvfb \
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
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Configure environment
ENV PORT=3000 \
    NODE_ENV=production \
    PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium

WORKDIR /app

# Install Node dependencies and Playwright Chromium browser
COPY package*.json ./
RUN npm ci --omit=dev || npm install --production
RUN npx playwright install chromium

# Copy application source code
COPY . .

# Ensure storage and recordings directories exist
RUN mkdir -p /app/recordings /app/data /app/storage

EXPOSE 3000

# Start unified bot manager and web studio with virtual framebuffer
CMD ["xvfb-run", "--server-args=-screen 0 1920x1080x24", "node", "src/index.js"]
