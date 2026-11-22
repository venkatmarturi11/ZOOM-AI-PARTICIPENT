#!/usr/bin/env python3
"""Generate a Telethon StringSession for GitHub Actions secrets.

Run this ONCE on your own computer (never on CI):

    pip install telethon
    python make_session.py

It will ask for your API ID, API hash, and phone number.
Copy the printed string into your repo secret TELEGRAM_SESSION.
"""
from telethon.sync import TelegramClient
from telethon.sessions import StringSession


def main():
    print("── Telegram StringSession generator ──")
    print("Get API credentials at https://my.telegram.org/apps\n")
    api_id   = int(input("API ID : ").strip())
    api_hash = input("API Hash: ").strip()

    with TelegramClient(StringSession(), api_id, api_hash) as client:
        session_string = client.session.save()
        print("\n✅  Your session string (copy this into the TELEGRAM_SESSION secret):\n")
        print(session_string)
        print("\n⚠️  Keep this string SECRET — it gives full access to your Telegram account.")


if __name__ == "__main__":
    main()
