# Zoom Bot Meeting Recorder — Backend & Telegram Control Plane

An automated meeting recording service built according to the **Zoom Bot Meeting Recorder Technical Documentation**.

## Features

- **Telegram Control Plane**: Control recording jobs directly from Telegram with commands:
  - `/record <meeting_url>` — Create recording job and start bot worker
  - `/stop <job_id>` — Stop recording and finalize MP4
  - `/status [job_id]` — Check active jobs and worker states
  - `/meetings` — View recently recorded meetings
  - `/recordings` — List MP4 recordings with file details
  - `/health` — Diagnostics on backend, database, and storage
- **State Machine**: Follows official meeting lifecycle:
  `QUEUED` → `AUTHENTICATING` → `AUTHORIZED` → `JOINING` → `RECORDING` → `FINALIZING` → `COMPLETED`
- **Zoom Authentication & Token Management**:
  - Meeting SDK JWT generation
  - OAuth 2.0 & ZAK (Zoom Access Key) token retrieval
  - Zoom meeting URL & passcode parsing
- **REST API Endpoints**:
  - `GET /api/health` — System status
  - `POST /api/recordings` — Create recording job
  - `POST /api/recordings/:id/stop` — Stop recording job
  - `GET /api/recordings/:id` — Get recording job details
  - `GET /api/recordings` — List all recordings
  - `GET /oauth/zoom/authorize` — OAuth login for bot Zoom user

---

## Configuration (`.env`)

Copy `.env.example` to `.env` and fill in:

```ini
APP_ENV=development
PORT=3000

# Telegram Bot Token (from @BotFather)
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrSTUvwxYZ
# Allowed Telegram user IDs (empty allows all)
TELEGRAM_ALLOWED_USERS=123456789

# Zoom Meeting SDK & OAuth App Credentials
ZOOM_CLIENT_ID=your_client_id
ZOOM_CLIENT_SECRET=your_client_secret
ZOOM_SDK_KEY=your_sdk_key
ZOOM_SDK_SECRET=your_sdk_secret
ZOOM_REDIRECT_URI=http://localhost:3000/oauth/zoom/callback

# Storage
DATABASE_PATH=./data/zoomrecord.sqlite
RECORDINGS_DIR=./recordings
```

---

## Running the Backend

```bash
cd backend
npm install
npm start
```
