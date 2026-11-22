# portal-meeting-recorder

A fully automatic, **GitHub Actions-based** bot that joins your scheduled meetings from a web portal, records the screen and audio, compresses the video, and uploads it to your Telegram Saved Messages.

## How it works

```
┌─────────┐    ┌────────────┐    ┌────────────┐    ┌──────────┐    ┌──────────┐
│  Cron   │───▸│Open portal │───▸│ Click Join │───▸│  Record  │───▸│ Upload   │
│ trigger │    │ find card  │    │ via browser│    │  screen  │    │ Telegram │
└─────────┘    └────────────┘    └────────────┘    └──────────┘    └──────────┘
```

1. GitHub Actions cron fires 5 minutes before each meeting.
2. `portal_recorder.py` opens your meetings portal, finds the live card by matching the current time, and clicks **Join**.
3. On the Zoom launcher page it clicks **"Join from your browser"** (never the desktop app).
4. It records the screen (Xvfb) and browser audio (PulseAudio null sink) with ffmpeg.
5. When the meeting ends, it compresses the recording (HEVC or H.264) and `send_telegram.py` uploads it via MTProto (up to 2 GB).

---

## Setup

### 1. Create a **private** GitHub repo

> [!CAUTION]
> The repo **must be private**. The Telegram session string gives full access to your account.

```bash
git clone <your-repo-url>
cd portal-meeting-recorder
```

### 2. Get Telegram API credentials

1. Go to [my.telegram.org/apps](https://my.telegram.org/apps) and create an app.
2. Note your **API ID** and **API Hash**.

### 3. Generate a StringSession

Run this **once** on your own computer:

```bash
pip install telethon
python make_session.py
```

It will ask for your API ID, API hash, and phone number. Copy the printed session string.

### 4. Add GitHub Actions secrets

Go to **Settings → Secrets and variables → Actions** and add:

| Secret | Required | Description |
|--------|----------|-------------|
| `PORTAL_URL` | ✅ | Full URL of your meetings portal page |
| `PORTAL_USER` | If login needed | Portal username / email |
| `PORTAL_PASS` | If login needed | Portal password |
| `TELEGRAM_API_ID` | ✅ | From my.telegram.org |
| `TELEGRAM_API_HASH` | ✅ | From my.telegram.org |
| `TELEGRAM_SESSION` | ✅ | The string from `make_session.py` |
| `TELEGRAM_TARGET` | ❌ | Chat/user ID or `"me"` (default: Saved Messages) |

### 5. Find your `CARD_SELECTOR`

1. Open your portal in a browser.
2. Right-click a meeting card → **Inspect**.
3. Find the CSS selector that matches one card (e.g., `.meeting-card`, `div.session-item`, `[data-testid="meeting"]`).
4. Edit the `CARD_SELECTOR` env var in `.github/workflows/portal-record.yml`.

### 6. Set your meeting times (cron)

Cron schedules in GitHub Actions are **UTC**. Convert your meeting times:

| Meeting time (IST) | UTC equivalent | Cron (5 min early) |
|---------------------|----------------|---------------------|
| 10:00 AM IST | 04:30 UTC | `25 4 * * 1-5` |
| 10:30 AM IST | 05:00 UTC | `55 4 * * 1-5` |
| 2:00 PM IST | 08:30 UTC | `25 8 * * 1-5` |
| 3:00 PM IST | 09:30 UTC | `25 9 * * 1-5` |
| 4:00 PM IST | 10:30 UTC | `25 10 * * 1-5` |

Edit the two `cron:` lines in `.github/workflows/portal-record.yml`.

### 7. Push and go

```bash
git add -A
git commit -m "Initial setup"
git push
```

You can also trigger a run manually from **Actions → Portal meeting recorder → Run workflow**.

---

## Configuration reference

All settings are environment variables in the workflow file:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORTAL_URL` | — | Meetings portal URL |
| `PORTAL_TZ` | `Asia/Kolkata` | Timezone for card time parsing |
| `CARD_SELECTOR` | `.meeting-card` | CSS selector for one meeting card |
| `JOIN_TEXT` | `join( now\| meeting)?` | Regex to match the Join button text |
| `JOIN_EARLY_MIN` | `5` | Join this many minutes before the card's start time |
| `WAIT_MINUTES` | `20` | Poll for a live card for this long, then exit |
| `MAX_MINUTES` | `120` | Hard recording time limit |
| `DISPLAY_NAME` | `Recorder` | Name shown in the Zoom participant list |

---

## Project structure

```
├── .github/workflows/
│   └── portal-record.yml      # GitHub Actions workflow
├── tests/
│   ├── test_time_logic.py      # Unit tests for time parsing and URL conversion
│   └── mock_portal.html        # Mock portal page for local testing
├── portal_recorder.py          # Main bot: portal → join → record → compress
├── send_telegram.py            # Upload recordings via MTProto
├── make_session.py             # One-time Telegram StringSession generator
├── requirements.txt            # Python dependencies
└── README.md                   # This file
```

---

## Running tests

```bash
pip install pytest playwright telethon
pytest tests/ -v
```

---

## Local dry-run (mock portal)

You can test the card-selection and time-parsing logic locally **without a real meeting**:

1. Edit the times in `tests/mock_portal.html` to match the current time.
2. Serve it locally:
   ```bash
   python -m http.server 8080
   ```
3. Run the recorder with the mock portal:
   ```bash
   # Linux/macOS
   PORTAL_URL=http://localhost:8080/tests/mock_portal.html \
   WAIT_MINUTES=1 \
   python portal_recorder.py

   # Windows (PowerShell)
   $env:PORTAL_URL="http://localhost:8080/tests/mock_portal.html"
   $env:WAIT_MINUTES="1"
   python portal_recorder.py
   ```

The bot will find the live card, click Join, and try to open Zoom (which will fail since it's a mock URL — that's expected). Check the logs to verify the correct card was selected.

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| **No live meeting found** | Card times don't match `now ± 5 min` | Check `PORTAL_TZ`, cron schedule (UTC!), and card time format |
| **Can't click "Join from browser"** | Zoom changed their UI | Check debug screenshots in Actions artifacts; update selectors |
| **Redirected to Zoom sign-in** | Meeting requires authenticated users | The bot can only join meetings that allow guests via browser |
| **Waiting room, no entry** | Host hasn't admitted the bot | The bot waits; host must admit it. Add a recognisable `DISPLAY_NAME` |
| **Recording has no audio** | PulseAudio sink not set up | Check the "Start virtual display and audio" step logs |
| **Upload fails** | Invalid or expired session | Re-run `make_session.py` and update the `TELEGRAM_SESSION` secret |
| **Job exceeds 6 hours** | Meeting + compression > 345 min | Lower `MAX_MINUTES`; GitHub hard-limits jobs at 6 h |
| **File > 2 GB** | Very long meeting | The uploader auto-splits at 1.9 GB segments |

---

## Test plan

### Automated tests (`pytest`)
- **Time parsing**: 12-h, 24-h, shared AM/PM, single time, edge cases (noon, midnight).
- **Live-card selection**: within window, early join, too early, after end.
- **URL conversion**: `/j/` → `/wc/join/`, fragment drop, `tk`/`pwd` retention.

### Manual integration tests
1. **Mock portal dry-run** (see above) — verifies card selection without a real meeting.
2. **`workflow_dispatch` trigger** — run the full workflow manually from the Actions tab.
3. **Verify Telegram delivery** — check Saved Messages for the uploaded video.
4. **Pop-up dismissal** — join a test meeting and confirm transcript/recording notices are dismissed.

---

## Known limits

- **GitHub Actions**: jobs are hard-limited to **6 hours** (we set 345 min). Free-tier repos get 2000 min/month; each run uses ≈ meeting duration + compression time.
- **Zoom UI changes**: Zoom periodically updates button text and page structure. If the bot can't join, check the debug screenshots and update selectors.
- **Waiting rooms**: the bot cannot bypass a waiting room — the host must admit it.
- **Host settings**: if the meeting requires sign-in or a passcode the bot doesn't have, it will exit with a failure message.
- **Telegram session**: the `StringSession` gives **full account access**. Keep the repo private and rotate if compromised.

---

## Ethics

- The bot joins only through **your own portal's meeting link**.
- It **never bypasses** host settings (passcodes, waiting rooms, auth requirements).
- The `DISPLAY_NAME` (default: "Recorder") is visible to all participants — **you** are responsible for informing attendees and complying with consent/recording laws in your jurisdiction.
