#!/usr/bin/env python3
"""Portal -> live meeting card -> Join -> 'Join from browser' -> record -> compress.

Environment variables (all optional except PORTAL_URL):
  PORTAL_URL          URL of the meetings portal
  PORTAL_USER         Portal login username
  PORTAL_PASS         Portal login password
  PORTAL_STORAGE_STATE  Path to Playwright storage-state JSON (optional)
  PORTAL_TZ           Timezone for card times (default: Asia/Kolkata)
  CARD_SELECTOR       CSS selector for one meeting card (default: .meeting-card)
  JOIN_TEXT            Regex for the Join button text
  JOIN_EARLY_MIN      Minutes before start to allow joining (default: 5)
  WAIT_MINUTES        How long to poll for a live card (default: 20)
  MAX_MINUTES         Hard recording time limit (default: 120)
  DISPLAY_NAME        Name shown to other participants (default: Recorder)
  DISPLAY             X display for ffmpeg (default: :99)
"""
import os, re, signal, subprocess, sys, time, traceback
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlparse, parse_qs, urlencode
from zoneinfo import ZoneInfo
from playwright.sync_api import sync_playwright

# ──────────────────────── configuration ────────────────────────
E = os.environ.get
PORTAL      = E("PORTAL_URL", "")
TZ          = ZoneInfo(E("PORTAL_TZ", "Asia/Kolkata"))
CARD        = E("CARD_SELECTOR", ".meeting-card")
JOIN_RE     = re.compile(E("JOIN_TEXT", r"^\s*join( now| meeting)?\s*$"), re.I)
EARLY       = int(E("JOIN_EARLY_MIN", "5"))
WAIT        = int(E("WAIT_MINUTES", "20"))
MAX_MIN     = int(E("MAX_MINUTES", "120"))
NAME        = E("DISPLAY_NAME", "Recorder")
OUT         = "recordings/meeting.mp4"
RAW         = "work/raw.mkv"
FLAG        = "work/.recording_attempted"
PORTAL_HOST = urlparse(PORTAL).netloc

# ──────────────────────── patterns ─────────────────────────────
TIME_RE = re.compile(
    r"(\d{1,2})[:.](\d{2})\s*([AP]M)?|(\d{1,2})\s*([AP]M)",
    re.I,
)

END_RE = re.compile(
    r"meeting (has )?ended|removed from the meeting|host has ended|"
    r"you have left|this meeting has been ended|"
    r"thank you for (attending|joining)", re.I,
)

BLOCKED = re.compile(
    r"(doubleclick|googlesyndication|adservice|hotjar|facebook\.net|"
    r"segment\.(io|com)|optimizely|onetrust|cookielaw)", re.I,
)

LOGIN_RE = re.compile(
    r"(facebook\.com|accounts\.google\.com|appleid\.apple\.com|"
    r"zoom\.[a-z]+/(signin|signup|register|google_oauth|fb_oauth|"
    r"apple_oauth|sso|oauth))", re.I,
)

# Buttons that are always safe to click (case-insensitive exact match).
DISMISS = r"^(got it|ok|okay|dismiss|close|continue|not now|no thanks|skip|i agree|agree|accept)$"

# Buttons we must NEVER click, even if a broad pattern matches.
NEVER_CLICK = re.compile(
    r"^(leave|end|end meeting|sign ?in|sign ?up|register|log ?in|"
    r"facebook|google|apple)$", re.I,
)


# ──────────────────────── logging ──────────────────────────────
def log(msg):
    print(f"[{datetime.now(TZ):%Y-%m-%d %H:%M:%S %Z}] {msg}", flush=True)


def screenshot(page, label="debug"):
    """Save a debug screenshot. Silently ignores errors."""
    try:
        os.makedirs("recordings", exist_ok=True)
        ts = datetime.now(TZ).strftime("%H%M%S")
        path = f"recordings/debug_{label}_{ts}.png"
        page.screenshot(path=path, full_page=True)
        log(f"Screenshot saved: {path}")
    except Exception as exc:
        log(f"Screenshot failed: {exc}")


# ──────────────────────── network guard ────────────────────────
def route_guard(route):
    """Abort ad/tracker requests and sign-in navigations (portal host exempt)."""
    url = route.request.url
    on_portal = PORTAL_HOST and urlparse(url).netloc == PORTAL_HOST
    if BLOCKED.search(url) or (not on_portal and LOGIN_RE.search(url)):
        return route.abort()
    route.continue_()


# ──────────────────────── time parsing ─────────────────────────
def _dt(h, m, ap, base):
    """Build a datetime from hour, minute, optional AM/PM and a reference date."""
    h, m = int(h), int(m)
    if ap:
        h = h % 12 + (12 if ap.upper() == "PM" else 0)
    return base.replace(hour=h, minute=m, second=0, microsecond=0)


def card_window(text, now):
    """Parse start/end times from card text.

    Supported formats:
      "10:00 AM - 11:00 AM"   two 12-h times
      "6.00pm" or "7.30 pm"   dot-separated times
      "10:00 - 11:00 AM"      first time inherits AM/PM from second
      "15:00 to 16:00"        24-h format
      "6:00 PM" / "6pm"       single time → +60 min

    Returns (start, end) or None.
    """
    matches = TIME_RE.findall(text)
    if not matches:
        return None
    # Normalize match groups: [[hour, minute, ampm], ...]
    ts = [
        [m[0] or m[3], m[1] or "00", (m[2] or m[4] or "").strip()]
        for m in matches
    ]
    # "10:00 - 11:00 AM" → inherit AM/PM from second to first
    if len(ts) > 1 and not ts[0][2] and ts[1][2]:
        ts[0][2] = ts[1][2]
    start = _dt(*ts[0], now)
    end = _dt(*ts[1], now) if len(ts) > 1 else start + timedelta(hours=1)
    # Fix cross-period: "10:00 - 1:00 PM" (start wrongly got PM → 22:00)
    if end < start:
        start -= timedelta(hours=12)
    # Overnight edge case (unlikely for daytime meetings)
    if end < start:
        end += timedelta(days=1)
    return start, end


def find_live_card(page):
    """Return the first card whose time window contains *now* (±EARLY min)."""
    now = datetime.now(TZ)
    for card in page.locator(CARD).all():
        try:
            w = card_window(card.inner_text(), now)
            if w and w[0] - timedelta(minutes=EARLY) <= now <= w[1]:
                return card
        except Exception:
            continue
    return None


# ──────────────────────── helpers ──────────────────────────────
def click(page, pattern, role="button", timeout=800):
    """Click the first matching button/link across all frames.

    Searches every frame (Zoom nests content in iframes).
    Returns True on success.
    """
    rx = re.compile(pattern, re.I)
    for fr in page.frames:
        for r in (role, "link"):
            try:
                loc = fr.get_by_role(r, name=rx).first
                # Safety check: never click dangerous buttons
                try:
                    label = loc.inner_text(timeout=200).strip()
                    if NEVER_CLICK.match(label):
                        continue
                except Exception:
                    pass
                loc.click(timeout=timeout)
                return True
            except Exception:
                pass
    return False


def web_url(u):
    """Convert a Zoom launcher URL to the browser-join URL.

    https://zoom.us/j/12345678?pwd=abc#success
    → https://zoom.us/wc/join/12345678?pwd=abc
    """
    p = urlparse(u)
    m = re.search(r"/(?:j|w|wc/join)/(\d+)", p.path)
    if not m:
        return u
    q = parse_qs(p.query)
    keep = {k: q[k][0] for k in ("tk", "pwd") if k in q}
    return f"{p.scheme}://{p.netloc}/wc/join/{m.group(1)}?{urlencode(keep)}"


def login_if_needed(page):
    """Fill username/password on the portal login page if credentials are set."""
    user, pw = E("PORTAL_USER"), E("PORTAL_PASS")
    if not user or not pw:
        return
    try:
        if page.locator("input[type=password]").count():
            page.locator("input[type=email], input[type=text]").first.fill(user)
            page.locator("input[type=password]").first.fill(pw)
            page.locator("button[type=submit], input[type=submit]").first.click()
            page.wait_for_load_state("networkidle", timeout=15000)
            log("Portal login completed")
    except Exception as exc:
        log(f"Portal login attempt: {exc}")


# ──────────────────────── Zoom join flow ───────────────────────
def join_zoom(page):
    """Navigate: Zoom launcher → 'Join from browser' → pre-join → Join.

    Returns True if the Join button was successfully clicked.
    """
    page.wait_for_load_state("domcontentloaded")
    guest_url = web_url(page.url)
    log(f"Zoom URL: {page.url[:100]}")

    # Step 1 — "Join from your browser"
    found = False
    for attempt in range(25):
        if click(page, r"join from (your )?browser", timeout=600):
            log("Clicked 'Join from your browser'")
            found = True
            break
        if click(page, r"join from (your )?browser", role="link", timeout=400):
            log("Clicked 'Join from your browser' (link)")
            found = True
            break
        if attempt == 15:
            log("'Join from browser' not visible after 15 s, navigating to web client")
            page.goto(guest_url, wait_until="domcontentloaded")
        time.sleep(1)

    if not found:
        log("Forcing web client URL")
        page.goto(guest_url, wait_until="domcontentloaded")
    time.sleep(3)

    # Step 2 — sign-in bounce guard
    if re.search(r"sign-?in|sign-?up|register|login|chrome-error", page.url, re.I):
        log(f"Redirected to sign-in ({page.url[:80]}), bouncing back")
        page.goto(guest_url, wait_until="domcontentloaded")
        time.sleep(3)

    # Step 3 — initial pop-up sweep
    click(page, DISMISS)

    # Step 4 — display name
    if NAME:
        for fr in page.frames:
            try:
                inp = fr.locator("#input-for-name, #inputname, [placeholder*='name' i]")
                if inp.count():
                    inp.first.fill(NAME, timeout=2000)
                    log(f"Display name set: '{NAME}'")
                    break
            except Exception:
                pass

    # Step 5 — click Join (host forces muted + video off, so only Join)
    joined = False
    for attempt in range(20):
        if click(page, r"^join$|^join meeting$", timeout=800):
            log("Clicked Join button")
            joined = True
            break
        click(page, DISMISS, timeout=300)
        time.sleep(1)

    if not joined:
        screenshot(page, "join_failed")
        log("WARNING: Could not click the Join button")
    time.sleep(5)

    # Step 6 — "Join Audio by Computer"
    for _ in range(5):
        if click(page, r"join audio by computer|computer audio", timeout=1500):
            log("Clicked 'Join Audio by Computer'")
            break
        time.sleep(1)

    # Final dismiss sweep
    click(page, DISMISS, timeout=500)
    log("Join flow completed")
    return joined


# ──────────────────────── recording ────────────────────────────
def start_ffmpeg():
    """Start a live x11grab + pulse capture → work/raw.mkv."""
    os.makedirs("work", exist_ok=True)
    os.makedirs("recordings", exist_ok=True)
    display = E("DISPLAY", ":99")
    cmd = [
        "ffmpeg", "-y", "-loglevel", "warning",
        "-f", "x11grab", "-framerate", "10", "-video_size", "1280x720",
        "-i", display,
        "-f", "pulse", "-i", "virtual.monitor",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k",
        RAW,
    ]
    log(f"ffmpeg command: {' '.join(cmd)}")
    logf = open("work/ffmpeg.log", "wb")   # a PIPE nobody reads fills up and freezes ffmpeg
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=logf, stderr=logf)


def stop_ffmpeg(proc):
    """Stop ffmpeg gracefully (send 'q'), fall back to SIGINT/kill."""
    if proc is None:
        return
    try:
        proc.stdin.write(b"q")
        proc.stdin.flush()
        proc.wait(timeout=30)
    except Exception:
        try:
            proc.send_signal(signal.SIGINT)
            proc.wait(timeout=15)
        except Exception:
            proc.kill()
    log("ffmpeg stopped")


def compress():
    """Re-encode raw MKV → compact MP4 (HEVC preferred, H.264 fallback).

    Returns True if the output file exists.
    """
    if not os.path.exists(RAW):
        log("No raw recording found, skipping compression")
        return False

    raw_mb = os.path.getsize(RAW) / 1e6
    log(f"Raw recording: {raw_mb:.0f} MB")
    os.makedirs("recordings", exist_ok=True)

    base = [
        "ffmpeg", "-y", "-i", RAW,
        "-vf", "fps=10,scale=-2:720",
        "-c:a", "aac", "-b:a", "64k", "-ac", "1",
        "-movflags", "+faststart",
    ]
    x265 = base + [
        "-c:v", "libx265", "-preset", "fast", "-crf", "28",
        "-tag:v", "hvc1", "-x265-params", "log-level=error", OUT,
    ]
    x264 = base + [
        "-c:v", "libx264", "-preset", "slow", "-crf", "26",
        "-pix_fmt", "yuv420p", OUT,
    ]

    log("Compressing with x265...")
    if subprocess.run(x265).returncode != 0:
        log("x265 failed, falling back to x264...")
        subprocess.run(x264, check=True)

    final_mb = os.path.getsize(OUT) / 1e6
    log(f"Final: {final_mb:.1f} MB  (ratio: {raw_mb / max(final_mb, 0.1):.1f}×)")

    # Delete raw file on success
    try:
        os.remove(RAW)
        log("Raw file deleted")
    except OSError:
        pass

    return True


# ──────────────────────── main ─────────────────────────────────
def main():
    if not PORTAL:
        log("ERROR: PORTAL_URL is not set"); sys.exit(1)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=False, args=[
            "--no-sandbox",
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
            "--disable-notifications",
            "--autoplay-policy=no-user-gesture-required",
            "--window-size=1280,720",
            "--window-position=0,0",
            "--kiosk",
            "--disable-infobars",
        ])
        state_path = E("PORTAL_STORAGE_STATE")
        ctx = browser.new_context(
            viewport={"width": 1280, "height": 720},
            permissions=[],
            storage_state=(state_path if state_path and os.path.exists(state_path) else None),
        )
        ctx.route("**/*", route_guard)

        # ── block unwanted tabs ──
        allow_new = {"tab": False}

        def on_new_page(p):
            if not allow_new["tab"]:
                log(f"Blocked unwanted tab: {p.url[:60]}")
                try:
                    p.close()
                except Exception:
                    pass

        ctx.on("page", on_new_page)

        portal = ctx.new_page()
        portal.on("dialog", lambda d: d.dismiss())
        log(f"Opening portal: {PORTAL[:80]}")
        portal.goto(PORTAL, wait_until="domcontentloaded")
        login_if_needed(portal)

        # ── 1. wait for a live meeting card ──
        deadline = time.time() + WAIT * 60
        card = None
        while time.time() < deadline:
            card = find_live_card(portal)
            if card:
                log("Live meeting card found!")
                break
            log("No live meeting, retrying in 30 s...")
            time.sleep(30)
            portal.reload(wait_until="domcontentloaded")

        if not card:
            log("No live meeting found within wait period. Exiting quietly.")
            browser.close()
            sys.exit(0)

        # ── 2. click the Join button ──
        btn = card.get_by_role("button", name=JOIN_RE)
        if not btn.count():
            btn = card.get_by_role("link", name=JOIN_RE)
        if not btn.count():
            btn = card.locator("a, button").filter(has_text=JOIN_RE)

        allow_new["tab"] = True
        try:
            with ctx.expect_page(timeout=15000) as page_info:
                btn.first.click()
            meet = page_info.value
            log("Join opened in a new tab")
        except Exception:
            meet = portal
            log("Join stayed in the same tab")
        allow_new["tab"] = False

        meet.on("dialog", lambda d: d.dismiss())
        if meet is not portal:
            portal.close()
        meet.bring_to_front()

        # ── 3. navigate Zoom join flow ──
        # Mark recording as attempted BEFORE join so a failure still triggers
        # a Telegram notification via send_telegram.py
        Path(FLAG).parent.mkdir(parents=True, exist_ok=True)
        Path(FLAG).touch()

        if not join_zoom(meet):
            log("Join failed, aborting instead of recording a blank screen")
            browser.close()
            sys.exit(1)

        # ── 4. record until meeting ends or hard limit ──
        ff = start_ffmpeg()
        log("Recording started")
        end_time = time.time() + MAX_MIN * 60
        guest_url = ""
        try:
            guest_url = web_url(meet.url)
        except Exception:
            pass

        try:
            while time.time() < end_time:
                time.sleep(4)

                # Tab closed?
                if meet.is_closed():
                    log("Meeting tab closed")
                    break

                # URL check
                try:
                    cur = meet.url
                except Exception:
                    log("Cannot read URL — tab likely closed")
                    break

                if "postattendee" in cur:
                    log("Post-attendee page detected")
                    break

                # Bounce back if redirected to a login page
                if LOGIN_RE.search(cur) and guest_url:
                    log(f"Redirected to login ({cur[:60]}), bouncing back")
                    meet.goto(guest_url, wait_until="domcontentloaded")
                    time.sleep(3)
                    continue

                # Dismiss pop-ups
                try:
                    click(meet, DISMISS, timeout=250)
                except Exception:
                    pass

                # Check end-of-meeting text in every frame
                try:
                    ended = False
                    for fr in meet.frames:
                        try:
                            body = fr.inner_text("body", timeout=1500)
                            if END_RE.search(body):
                                log("End-of-meeting text detected")
                                ended = True
                                break
                        except Exception:
                            pass
                    if ended:
                        break
                except Exception:
                    pass

        except KeyboardInterrupt:
            log("Interrupted")
        finally:
            stop_ffmpeg(ff)
            if not meet.is_closed():
                screenshot(meet, "final")
            browser.close()

        # ── 5. compress ──
        if compress():
            log(f"Done. Output: {OUT}")
        else:
            log("Compression produced no output")


if __name__ == "__main__":
    main()
