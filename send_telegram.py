#!/usr/bin/env python3
"""Upload meeting recordings to Telegram via MTProto (up to 2 GB per file).

Behaviour:
  • recordings/*.mp4 exists  → upload each file (split if > 1.9 GB).
  • work/.recording_attempted exists but no MP4 → send a failure message (with screenshot if available).
  • Neither exists (no meeting was live)         → do nothing, exit 0 quietly.
"""
import asyncio, glob, math, os, subprocess, sys
from datetime import datetime
from zoneinfo import ZoneInfo
from telethon import TelegramClient
from telethon.sessions import StringSession

LIMIT = 1_900_000_000  # stay well under Telegram's 2 GB ceiling
FLAG  = "work/.recording_attempted"
TZ    = ZoneInfo(os.environ.get("PORTAL_TZ", "Asia/Kolkata"))


def split(path: str) -> list[str]:
    """Split a file into segments that fit under LIMIT using ffmpeg -c copy."""
    size = os.path.getsize(path)
    dur_out = subprocess.check_output(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", path],
    ).decode().strip()
    dur = float(dur_out.split()[0])
    n_parts = math.ceil(size / LIMIT * 1.05)
    seg_sec = int(dur / n_parts) + 1
    os.makedirs("work/parts", exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", path,
         "-c", "copy", "-f", "segment",
         "-segment_time", str(seg_sec), "-reset_timestamps", "1",
         "work/parts/part_%03d.mp4"],
        check=True,
    )
    return sorted(glob.glob("work/parts/part_*.mp4"))


async def main():
    files = sorted(glob.glob("recordings/*.mp4"))
    attempted = os.path.exists(FLAG)

    # Nothing to do: no meeting was live
    if not files and not attempted:
        print("No meeting was live and no recording attempted. Nothing to send.")
        return

    # Connect
    session  = os.environ.get("TELEGRAM_SESSION", "")
    api_id   = os.environ.get("TELEGRAM_API_ID", "")
    api_hash = os.environ.get("TELEGRAM_API_HASH", "")
    if not session or not api_id or not api_hash:
        print("ERROR: Telegram credentials not set, skipping upload.", file=sys.stderr)
        return

    client = TelegramClient(StringSession(session), int(api_id), api_hash)
    await client.start()

    target = os.environ.get("TELEGRAM_TARGET") or "me"
    if isinstance(target, str) and target.lstrip("-").isdigit():
        target = int(target)

    now = datetime.now(TZ).strftime("%Y-%m-%d %H:%M %Z")

    if not files:
        # Recording was attempted but nothing was produced
        msg = (
            f"⚠️ Recording failed on {now}.\n"
            "The meeting was found but no video file was produced.\n"
            "Check the GitHub Actions run log or artifacts for details."
        )
        debug_screens = sorted(glob.glob("recordings/debug_*.png"))
        if debug_screens:
            await client.send_file(target, debug_screens[-1], caption=msg)
        else:
            await client.send_message(target, msg)
        print("Sent failure notification to Telegram.")
        await client.disconnect()
        return

    # Upload each recording
    for f in files:
        parts = split(f) if os.path.getsize(f) > LIMIT else [f]
        for i, p in enumerate(parts, 1):
            size_mb = os.path.getsize(p) / 1e6
            caption = f"📹 Meeting recording — {now}\nPart {i}/{len(parts)} ({size_mb:.0f} MB)"
            if len(parts) == 1:
                caption = f"📹 Meeting recording — {now} ({size_mb:.0f} MB)"
            print(f"Uploading {p} ({size_mb:.0f} MB)...", flush=True)
            await client.send_file(
                target, p,
                caption=caption,
                supports_streaming=True,
            )
            print(f"  ✓ Sent: {caption}", flush=True)

    await client.disconnect()
    print("All uploads complete.")


if __name__ == "__main__":
    asyncio.run(main())
