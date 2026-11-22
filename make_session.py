#!/usr/bin/env python3
"""Generate a Telethon StringSession for GitHub Actions secrets.

Run this ONCE on your own computer (never on CI):

    python make_session.py
    # OR with arguments:
    python make_session.py <api_id> <api_hash>

It will ask for your phone number and the Telegram code.
Copy the printed string into your repo secret TELEGRAM_SESSION.
"""
import sys
from telethon.sync import TelegramClient
from telethon.sessions import StringSession


def main():
    print("── Telegram StringSession generator ──")
    print("Get API credentials at https://my.telegram.org/apps\n")

    if len(sys.argv) >= 3:
        api_id_raw = sys.argv[1].strip()
        api_hash = sys.argv[2].strip()
    else:
        api_id_raw = input("API ID : ").strip()
        api_hash = input("API Hash: ").strip()

    try:
        api_id = int(api_id_raw)
    except ValueError:
        print(f"Error: API ID must be a number, got '{api_id_raw}'")
        sys.exit(1)

    print(f"\nConnecting to Telegram with API ID: {api_id}...")
    with TelegramClient(StringSession(), api_id, api_hash) as client:
        session_string = client.session.save()
        print("\n" + "=" * 60)
        print("✅  Your session string (copy this into the TELEGRAM_SESSION secret):\n")
        print(session_string)
        print("=" * 60)
        print("\n⚠️  Keep this string SECRET — it gives full access to your Telegram account.")


if __name__ == "__main__":
    main()
