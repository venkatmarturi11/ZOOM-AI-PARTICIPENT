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

def normalize_selector(sel):
    """Normalize CSS or XPath selector for Playwright.

    If given a single-child XPath like '/html/body/.../div[1]', generalize to '/div'
    so all sibling cards are discovered, and ensure the 'xpath=' prefix is added.
    """
    sel = (sel or "").strip()
    if not sel:
        return ".meeting-card"
    if re.search(r"/[a-zA-Z0-9_-]+\[1\]$", sel):
        sel = re.sub(r"\[1\]$", "", sel)
    if sel.startswith("/") and not sel.startswith("xpath="):
        return f"xpath={sel}"
    return sel


# ──────────────────────── configuration ────────────────────────
E = os.environ.get
PORTAL      = E("PORTAL_URL", "https://classes.nareshit.com/live")
TZ          = ZoneInfo(E("PORTAL_TZ", "Asia/Kolkata"))
CARD        = normalize_selector(E("CARD_SELECTOR", "/html/body/div/div/div[1]/div[4]/div"))
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
DURATION_RE = re.compile(r"(\d{1,2})[:.](\d{2})\s*(?:Hr\'?s?|hrs?|hours?)", re.I)
TIME_RE = re.compile(
    r"\b(\d{1,2})[:.](\d{2})\s*([AP]M)?|\b(\d{1,2})(\d{2})\s*([AP]M)|\b(\d{1,2})\s*([AP]M)\b",
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
    """Parse start and end times from card text.

    Supports:
      - NareshIT portal: "ADV JAVA 6 PM ... 06:00 PM • 01:30 Hr's" -> 18:00 to 19:30
      - NareshIT portal: "React JS 730 PM ... 07:30 PM • 01:30 Hr's" -> 19:30 to 21:00
      - Standard range:  "10:00 AM - 11:00 AM", "15:00 to 16:00"
      - Shared period:   "10:00 - 11:00 AM"
      - Single time:     "6:00 PM" / "6pm" -> duration or +60 min
    """
    dur_match = DURATION_RE.search(text)
    duration = None
    if dur_match:
        dh, dm = dur_match.groups()
        duration = timedelta(hours=int(dh), minutes=int(dm))
        text_no_dur = text[:dur_match.start()]   # only look at times BEFORE the duration
    else:
        text_no_dur = text

    raw_times = []
    for m in TIME_RE.finditer(text_no_dur):
        g = m.groups()
        h = g[0] or g[3] or g[6]
        mi = g[1] or g[4] or "00"
        ap = (g[2] or g[5] or g[7] or "").strip()
        raw_times.append((h, mi, ap))

    if not raw_times:
        return None

    # Inherit AM/PM if second has it and first doesn't
    if len(raw_times) > 1 and not raw_times[0][2] and raw_times[1][2]:
        raw_times[0] = (raw_times[0][0], raw_times[0][1], raw_times[1][2])

    dts = [_dt(*t, now) for t in raw_times]
    if duration:
        # The time printed right before the duration is the real start
        # (a title like "6 PM" may be outdated if the class was rescheduled).
        return dts[-1], dts[-1] + duration
    # Filter to distinct times (avoid duplicates like "6 PM", "@ 6:00 PM", "06:00 PM")
    distinct = []
    for d in dts:
        if not any(abs((d - x).total_seconds()) < 300 for x in distinct):
            distinct.append(d)

    start = distinct[0]
    if len(distinct) > 1:
        end = distinct[1]
        if end < start:
            start -= timedelta(hours=12)
        if end < start:
            end += timedelta(days=1)
    elif duration:
        end = start + duration
    else:
        end = start + timedelta(hours=1)

    return start, end


SCAN = {"read": 0, "pending": False}   # filled by find_live_card on every scan
EARLY_EXIT = E("EARLY_EXIT", "true").lower() == "true"


def _auto_discover_cards(page):
    """Auto-discover class card containers by locating Join buttons and walking up to
    the nearest ancestor that also contains time text (e.g. '06:00 PM • 01:30 Hr's').
    Used as a fallback when the configured CARD_SELECTOR misses the real cards."""
    try:
        # Clean up old markers from a previous call
        page.evaluate("() => document.querySelectorAll('[data-portal-card]').forEach(e => e.removeAttribute('data-portal-card'))")
        cards_info = page.evaluate("""() => {
            const joinRe = /^\\s*join(\\s+now|\\s+meeting)?\\s*$/i;
            const timeRe = /\\d{1,2}[:.:]\\d{2}\\s*[AP]M/i;
            const buttons = [...document.querySelectorAll('button, a, [role="button"]')]
                .filter(el => joinRe.test((el.textContent || '').trim()) && el.offsetParent !== null);
            const results = [];
            const seen = new Set();
            for (const btn of buttons) {
                let el = btn.parentElement;
                for (let d = 0; d < 10 && el && el !== document.body; d++, el = el.parentElement) {
                    const text = (el.innerText || '').trim();
                    if (timeRe.test(text) && text.length < 800 && !seen.has(text)) {
                        seen.add(text);
                        const marker = '__card_' + results.length;
                        el.setAttribute('data-portal-card', marker);
                        results.push(marker);
                        break;
                    }
                }
            }
            return results;
        }""")
        if not cards_info:
            return []
        return [page.locator(f"[data-portal-card='{m}']").first
                for m in cards_info
                if page.locator(f"[data-portal-card='{m}']").count()]
    except Exception as exc:
        log(f"   Auto-discover fallback error: {exc}")
        return []


def _ensure_live_tab(page):
    """After an SPA reload, explicitly re-select Live Classes > Today's Classes."""
    try:
        page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:
        pass
    time.sleep(1)
    try:
        tab = page.get_by_text(re.compile(r"Live\s*Classes", re.I))
        if tab.count():
            tab.first.click()
            time.sleep(1)
    except Exception:
        pass
    try:
        tab = page.get_by_text(re.compile(r"Today.?s\s*Classes", re.I))
        if tab.count():
            tab.first.click()
            time.sleep(1)
    except Exception:
        pass
    try:
        page.wait_for_load_state("networkidle", timeout=10000)
    except Exception:
        pass
    time.sleep(2)


def wait_for_cards(page, seconds=15):
    """After a reload the portal (a single-page app) is empty for a moment.
    Wait for the network to settle and for some card text to appear before scanning."""
    try:
        page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:
        pass
    end = time.time() + seconds
    while time.time() < end:
        try:
            if any(c.inner_text().strip() for c in page.locator(CARD).all()):
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def find_live_card(page):
    """Return the first card whose time window contains *now* (±EARLY min)."""
    now = datetime.now(TZ)
    SCAN["read"], SCAN["pending"] = 0, False
    try:
        cards = page.locator(CARD).all()
    except Exception as exc:
        log(f"⚠️ Error querying cards with selector '{CARD}': {exc}")
        cards = []

    # Check if primary selector found any cards with parseable times
    primary_has_times = False
    if cards:
        for c in cards:
            try:
                if card_window(c.inner_text(timeout=2000).strip(), now):
                    primary_has_times = True
                    break
            except Exception:
                pass

    if not primary_has_times:
        fallback = _auto_discover_cards(page)
        if fallback:
            log(f"   (Primary selector found no timed cards; using {len(fallback)} auto-discovered card(s) with Join buttons)")
            cards = fallback
        elif not cards:
            log(f"   (No class cards found on portal)")
            return None

    log(f"   Found {len(cards)} card(s) on portal (current time: {now:%I:%M %p %Z}):")
    candidates = []

    for idx, card in enumerate(cards, 1):
        try:
            raw = card.inner_text(timeout=3000).strip()
            w = card_window(raw, now)
            if raw:
                SCAN["read"] += 1
            if w and w[1] > now:
                SCAN["pending"] = True      # live now or still to come today
            lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
            title = lines[0] if lines else f"Card #{idx}"
            if len(lines) > 1 and ("hrs" in lines[0].lower() or "left" in lines[0].lower()):
                title = lines[1]  # Use class name instead of "5 hrs left" badge

            if w:
                start, end = w
                is_live = (start - timedelta(minutes=EARLY)) <= now <= end
                status_str = f"🟢 LIVE NOW ({start:%I:%M %p} - {end:%I:%M %p})" if is_live else f"⏳ Scheduled for {start:%I:%M %p} - {end:%I:%M %p}"
                log(f"   [{idx}] {title[:35]:<35} -> {status_str}")
                if is_live:
                    dist = abs((now - start).total_seconds())
                    candidates.append((dist, idx, title, card, start, end))
            else:
                log(f"   [{idx}] {title[:35]:<35} -> ℹ️  (No valid time range detected)")
                # Debug: log first 120 chars of raw text for diagnostics
                if raw:
                    log(f"         raw: {repr(raw[:120])}")
        except Exception as exc:
            log(f"   [{idx}] Error reading card #{idx}: {exc}")
            continue

    if not candidates:
        return None

    # Pick exactly ONE card whose start time is closest to now
    candidates.sort(key=lambda c: c[0])
    best = candidates[0]
    log(f"   🎯 Selected 1 card matching current time: '{best[2]}' ({best[4]:%I:%M %p} - {best[5]:%I:%M %p})")
    return best[3]


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
    """Return the URL as-is — no rewriting, to preserve one-time tokens (tk) and passwords."""
    return u


def login_if_needed(page):
    """Fill username/password on the portal login page if credentials are set."""
    user, pw = E("PORTAL_USER"), E("PORTAL_PASS")
    if not user or not pw:
        return
    try:
        pw_input = page.locator("input[type=password], input[name=password]")
        if pw_input.count():
            log("Portal login page detected, submitting credentials...")
            user_input = page.locator(
                "input[name=Login], input[placeholder*='Email or Phone' i], "
                "input[type=email], input[type=tel], input[name*='user' i], "
                "input[name*='email' i], input[name*='phone' i], input[name*='mobile' i], "
                "input[placeholder*='mobile' i], input[placeholder*='user' i], input[type=text]"
            )
            if user_input.count():
                user_input.first.fill(user)
            pw_input.first.fill(pw)

            submit_btn = page.locator(
                ".login-submit-button, button[type=submit], input[type=submit], "
                "button:has-text('Login'), button:has-text('Log in'), button:has-text('Sign In')"
            )
            if submit_btn.count():
                submit_btn.first.click()
            else:
                pw_input.first.press("Enter")

            page.wait_for_load_state("networkidle", timeout=15000)
            time.sleep(3)
            log(f"✅ Portal login submitted! Current URL: {page.url}")

            # Ensure we navigate to the 'Live Classes' tab after login
            if "live" not in page.url:
                log("➡️ Navigating to 'Live Classes' tab...")
                live_tab = page.get_by_text("Live Classes")
                if live_tab.count():
                    live_tab.first.click()
                    page.wait_for_load_state("networkidle", timeout=15000)
                    time.sleep(3)
                    log(f"   ✓ Clicked 'Live Classes' tab! Current URL: {page.url}")

            if "live" not in page.url and PORTAL:
                log(f"   ➡️ Direct navigating to {PORTAL}...")
                page.goto(PORTAL, wait_until="networkidle")
                time.sleep(2)
    except Exception as exc:
        log(f"⚠️ Portal login attempt: {exc}")


# ──────────────────────── Zoom join flow ───────────────────────
def join_zoom(page):
    """Navigate: Zoom launcher → 'Join from browser' → pre-join → Join.

    Returns True if the Join button was successfully clicked.
    """
    page.wait_for_load_state("domcontentloaded")
    guest_url = web_url(page.url)
    log(f"🌐 Opened Zoom launch page: {page.url[:80]}...")

    # Step 1 — "Join from your browser"
    log("🔍 Looking for 'Join from your browser' link (avoiding desktop app)...")
    found = False
    for attempt in range(25):
        if click(page, r"join from (your )?browser", timeout=600):
            log("   ✓ Clicked 'Join from your browser'")
            found = True
            break
        if click(page, r"join from (your )?browser", role="link", timeout=400):
            log("   ✓ Clicked 'Join from your browser' (link)")
            found = True
            break
        if attempt == 15:
            log("   ℹ️  'Join from browser' not visible after 15s; opening direct web URL...")
            page.goto(guest_url, wait_until="domcontentloaded")
        time.sleep(1)

    if not found:
        log("   ➡️ Navigating directly to web client...")
        page.goto(guest_url, wait_until="domcontentloaded")
    time.sleep(3)

    # Step 2 — sign-in bounce guard
    if re.search(r"sign-?in|sign-?up|register|login|chrome-error", page.url, re.I):
        log(f"   🛡️ Redirected to sign-in ({page.url[:60]}), bouncing back to guest page...")
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
                    log(f"   ✓ Set participant name: '{NAME}'")
                    break
            except Exception:
                pass

    # Step 5 — click Join (host forces muted + video off, so only Join)
    joined = False
    log("🔘 Clicking 'Join Meeting' button...")
    for attempt in range(20):
        if click(page, r"^join$|^join meeting$", timeout=800):
            log("   ✓ Successfully clicked Join!")
            joined = True
            break
        click(page, DISMISS, timeout=300)
        time.sleep(1)

    if not joined:
        screenshot(page, "join_failed")
        log("❌ WARNING: Could not click the Join button (debug screenshot saved to artifacts)")
    time.sleep(5)

    # Step 6 — "Join Audio by Computer"
    for _ in range(5):
        if click(page, r"join audio by computer|computer audio", timeout=1500):
            log("   ✓ Connected audio ('Join Audio by Computer')")
            break
        time.sleep(1)

    # Final dismiss sweep
    click(page, DISMISS, timeout=500)
    log("🎉 Successfully connected to Zoom meeting!")
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

        # ── create main portal page first ──
        portal = ctx.new_page()
        portal.on("dialog", lambda d: d.dismiss())

        # ── block unwanted pop-up tabs (except when clicking Join) ──
        allow_new = {"tab": False}

        def on_new_page(p):
            if p == portal:
                return
            if not allow_new["tab"]:
                log(f"🛡️  Blocked unwanted pop-up tab: {p.url[:60]}")
                try:
                    p.close()
                except Exception:
                    pass

        ctx.on("page", on_new_page)

        log(f"🚀 Opening meetings portal: {PORTAL}")
        portal.goto(PORTAL, wait_until="domcontentloaded")
        login_if_needed(portal)

        # ── ensure we are on the Live Classes tab ──
        _ensure_live_tab(portal)
        log(f"   ✓ On Live Classes tab ({portal.url})")

        # ── 1. wait for a live meeting card ──
        deadline = time.time() + WAIT * 60
        card = None
        iteration = 0
        idle_checks = 0
        while time.time() < deadline:
            # Make sure we remain on the live classes page on each check
            if "live" not in portal.url:
                _ensure_live_tab(portal)

            iteration += 1
            log(f"🔍 [Check #{iteration}] Scanning for live class...")
            card = find_live_card(portal)
            if card:
                log("🎯 LIVE CLASS FOUND! Preparing to join...")
                break
            idle_checks = idle_checks + 1 if (SCAN["read"] and not SCAN["pending"]) else 0
            if EARLY_EXIT and idle_checks >= 2:
                log("ℹ️  The portal shows no live or upcoming class right now. Exiting early to save minutes.")
                screenshot(portal, "no_class")
                browser.close()
                sys.exit(0)
            log(f"⏳ Class is not live yet. Next check in 30 seconds...")
            time.sleep(30)
            portal.reload(wait_until="domcontentloaded")
            _ensure_live_tab(portal)
            wait_for_cards(portal)

        if not card:
            log("ℹ️  No live meeting found within the 20-minute window. Exiting quietly.")
            browser.close()
            sys.exit(0)

        # ── 2. click the Join button ──
        log("👉 Clicking 'Join' on the live class card...")
        btn = card.get_by_role("button", name=JOIN_RE)
        if not btn.count():
            btn = card.get_by_role("link", name=JOIN_RE)
        if not btn.count():
            btn = card.locator("a, button, [role='button'], input[type='button']").filter(has_text=JOIN_RE)
        if not btn.count():
            btn = card.locator("a[href*='zoom.us'], a[href*='/j/'], a[href*='/wc/']")
        if not btn.count():
            btn = card.locator("a, button, [role='button']").filter(has_text=re.compile(r"join", re.I))

        allow_new["tab"] = True
        try:
            with ctx.expect_page(timeout=15000) as page_info:
                btn.first.click()
            meet = page_info.value
            log("   ✓ Zoom opened in a new tab")
        except Exception:
            meet = portal
            log("   ✓ Zoom opened in the same tab")
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
        log("🎥 Screen and audio recording started!")
        log(f"⏱️  Monitoring meeting... (Hard time limit: {MAX_MIN} mins)")
        end_time = time.time() + MAX_MIN * 60
        guest_url = ""
        try:
            guest_url = web_url(meet.url)
        except Exception:
            pass

        loop_count = 0
        try:
            while time.time() < end_time:
                time.sleep(4)
                loop_count += 1
                if loop_count % 15 == 0:
                    elapsed_min = int(loop_count * 4 / 60)
                    log(f"🔴 Meeting recording active... ({elapsed_min} min elapsed)")

                # Tab closed?
                if meet.is_closed():
                    log("🏁 Meeting tab was closed by host or browser.")
                    break

                # URL check
                try:
                    cur = meet.url
                except Exception:
                    log("🏁 Cannot read page URL — meeting tab closed.")
                    break

                if "postattendee" in cur:
                    log("🏁 Meeting ended (Zoom post-attendee page detected).")
                    break

                # Bounce back if redirected to a login page
                if LOGIN_RE.search(cur) and guest_url:
                    log(f"   🛡️ Redirected to login ({cur[:60]}), bouncing back to meeting...")
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
                                log("🏁 Meeting ended (End-of-meeting notice detected).")
                                ended = True
                                break
                        except Exception:
                            pass
                    if ended:
                        break
                except Exception:
                    pass

        except KeyboardInterrupt:
            log("🛑 Recording manually interrupted.")
        finally:
            log("⏹️  Stopping screen recording...")
            stop_ffmpeg(ff)
            if not meet.is_closed():
                screenshot(meet, "final")
            browser.close()

        # ── 5. compress ──
        log("🔄 Starting video post-processing and compression...")
        if compress():
            log(f"🎉 Complete! Final video ready for Telegram delivery: {OUT}")
        else:
            log("⚠️ Compression finished with no output file.")


if __name__ == "__main__":
    main()
