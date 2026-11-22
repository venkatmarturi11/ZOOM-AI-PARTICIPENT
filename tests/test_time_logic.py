"""Unit tests for portal_recorder time parsing, card selection, and URL conversion."""
import sys, os, pytest
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

# Make sure the project root is on sys.path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from portal_recorder import card_window, web_url, _dt

TZ = ZoneInfo("Asia/Kolkata")


def _now(hour, minute=0, tz=TZ):
    """Helper: build a timezone-aware datetime for today at the given time."""
    return datetime(2026, 6, 15, hour, minute, 0, tzinfo=tz)


# ═══════════════════════ card_window ═══════════════════════════

class TestCardWindow:
    """card_window(text, now) → (start, end) or None."""

    # ── basic 12-hour formats ──

    def test_12h_am_am(self):
        start, end = card_window("Team Standup  10:00 AM - 11:00 AM", _now(10, 30))
        assert (start.hour, start.minute) == (10, 0)
        assert (end.hour, end.minute) == (11, 0)

    def test_12h_pm_pm(self):
        start, end = card_window("Review  3:00 PM - 4:30 PM", _now(15, 30))
        assert (start.hour, start.minute) == (15, 0)
        assert (end.hour, end.minute) == (16, 30)

    def test_12h_am_pm(self):
        start, end = card_window("Workshop  11:00 AM - 1:00 PM", _now(12))
        assert (start.hour, start.minute) == (11, 0)
        assert (end.hour, end.minute) == (13, 0)

    # ── dot-separated and custom class formats ──

    def test_dot_format_advance_java(self):
        start, end = card_window("advacnce java/adv java at 6.00pm", _now(18))
        assert (start.hour, start.minute) == (18, 0)
        assert (end.hour, end.minute) == (19, 0)

    def test_dot_format_react_js(self):
        start, end = card_window("react js /react at 7.30 pm", _now(19, 30))
        assert (start.hour, start.minute) == (19, 30)
        assert (end.hour, end.minute) == (20, 30)

    def test_single_hour_pm(self):
        start, end = card_window("adv java at 6pm", _now(18))
        assert (start.hour, start.minute) == (18, 0)
        assert (end.hour, end.minute) == (19, 0)

    def test_numbers_in_title_ignored(self):
        start, end = card_window("Java 17 & React 18 class at 6.00pm", _now(18))
        assert (start.hour, start.minute) == (18, 0)
        assert (end.hour, end.minute) == (19, 0)

    # ── shared AM/PM ──

    def test_shared_am(self):
        """'10:00 - 11:00 AM' → both are AM."""
        start, end = card_window("10:00 - 11:00 AM", _now(10, 30))
        assert start.hour == 10
        assert end.hour == 11

    def test_shared_pm(self):
        """'3:00 - 4:00 PM' → both are PM."""
        start, end = card_window("3:00 - 4:00 PM", _now(15, 30))
        assert start.hour == 15
        assert end.hour == 16

    def test_shared_pm_cross_period(self):
        """'10:00 - 1:00 PM' → start=10 AM, end=1 PM (heuristic fix)."""
        start, end = card_window("10:00 - 1:00 PM", _now(12))
        assert start.hour == 10
        assert end.hour == 13

    # ── 24-hour formats ──

    def test_24h_dash(self):
        start, end = card_window("Lecture  15:00 - 16:00", _now(15, 30))
        assert start.hour == 15
        assert end.hour == 16

    def test_24h_to(self):
        start, end = card_window("Lab  09:00 to 10:30", _now(9, 15))
        assert (start.hour, start.minute) == (9, 0)
        assert (end.hour, end.minute) == (10, 30)

    # ── single time (no end) ──

    def test_single_time_12h(self):
        """Only a start time → end = start + 60 min."""
        start, end = card_window("Kickoff at 2:00 PM", _now(14))
        assert start.hour == 14
        assert (end - start) == timedelta(hours=1)

    def test_single_time_24h(self):
        start, end = card_window("Sync 09:30", _now(9, 30))
        assert (start.hour, start.minute) == (9, 30)
        assert (end - start) == timedelta(hours=1)

    # ── edge cases ──

    def test_no_time(self):
        assert card_window("No time info here", _now(10)) is None

    def test_empty(self):
        assert card_window("", _now(10)) is None

    def test_midnight_12h(self):
        start, end = card_window("12:00 AM - 1:00 AM", _now(0, 30))
        assert start.hour == 0
        assert end.hour == 1

    def test_noon(self):
        start, end = card_window("12:00 PM - 1:00 PM", _now(12, 30))
        assert start.hour == 12
        assert end.hour == 13

    def test_case_insensitive(self):
        start, end = card_window("10:00 am - 11:00 am", _now(10, 30))
        assert start.hour == 10
        assert end.hour == 11


# ═══════════════════════ live-card selection logic ═════════════

class TestLiveCardLogic:
    """Test the selection window: start - EARLY_MIN ≤ now ≤ end."""

    def _is_live(self, text, now_h, now_m=0, early=5):
        """Simulate find_live_card's check without Playwright."""
        now = _now(now_h, now_m)
        w = card_window(text, now)
        if w is None:
            return False
        return w[0] - timedelta(minutes=early) <= now <= w[1]

    def test_within_window(self):
        assert self._is_live("10:00 AM - 11:00 AM", 10, 30)

    def test_exactly_at_start(self):
        assert self._is_live("10:00 AM - 11:00 AM", 10, 0)

    def test_exactly_at_end(self):
        assert self._is_live("10:00 AM - 11:00 AM", 11, 0)

    def test_early_join_allowed(self):
        """5 minutes before start should be live."""
        assert self._is_live("10:00 AM - 11:00 AM", 9, 55)

    def test_early_join_boundary(self):
        """Exactly 5 minutes before start → live."""
        assert self._is_live("10:00 AM - 11:00 AM", 9, 55, early=5)

    def test_too_early(self):
        """6 minutes before start → NOT live."""
        assert not self._is_live("10:00 AM - 11:00 AM", 9, 54, early=5)

    def test_after_end(self):
        assert not self._is_live("10:00 AM - 11:00 AM", 11, 1)

    def test_before_window(self):
        assert not self._is_live("3:00 PM - 4:00 PM", 14, 0)


# ═══════════════════════ web_url ═══════════════════════════════

class TestWebUrl:
    """web_url(u) converts Zoom launcher URLs to browser-join URLs."""

    def test_basic_j_path(self):
        url = "https://zoom.us/j/12345678?pwd=abc123#success"
        assert web_url(url) == "https://zoom.us/wc/join/12345678?pwd=abc123"

    def test_keeps_tk_and_pwd(self):
        url = "https://zoom.us/j/12345678?tk=TOKEN&pwd=PASS&other=x"
        result = web_url(url)
        assert "tk=TOKEN" in result
        assert "pwd=PASS" in result
        assert "other" not in result

    def test_drops_fragment(self):
        url = "https://zoom.us/j/99999?pwd=p#fragment"
        assert "#" not in web_url(url)

    def test_already_wc_join(self):
        url = "https://zoom.us/wc/join/12345678?tk=x&pwd=y"
        result = web_url(url)
        assert "/wc/join/12345678" in result
        assert "tk=x" in result

    def test_w_path(self):
        url = "https://us02web.zoom.us/w/98765432?pwd=z"
        result = web_url(url)
        assert "/wc/join/98765432" in result
        assert "pwd=z" in result

    def test_no_meeting_id(self):
        """Non-meeting URL → returned unchanged."""
        url = "https://zoom.us/something-else"
        assert web_url(url) == url

    def test_no_query_params(self):
        url = "https://zoom.us/j/12345678"
        result = web_url(url)
        assert "/wc/join/12345678" in result
        assert result.endswith("?") or result.endswith("12345678?")

    def test_custom_domain(self):
        url = "https://company.zoom.us/j/11111?pwd=abc"
        result = web_url(url)
        assert result.startswith("https://company.zoom.us/wc/join/11111")


# ═══════════════════════ _dt helper ════════════════════════════

class TestDt:
    """_dt(h, m, ap, base) → datetime."""

    def test_am(self):
        d = _dt("10", "30", "AM", _now(0))
        assert (d.hour, d.minute) == (10, 30)

    def test_pm(self):
        d = _dt("3", "00", "PM", _now(0))
        assert d.hour == 15

    def test_12_pm_is_noon(self):
        d = _dt("12", "00", "PM", _now(0))
        assert d.hour == 12

    def test_12_am_is_midnight(self):
        d = _dt("12", "00", "AM", _now(0))
        assert d.hour == 0

    def test_24h_no_ampm(self):
        d = _dt("15", "45", "", _now(0))
        assert (d.hour, d.minute) == (15, 45)

    def test_24h_none(self):
        d = _dt("9", "00", None, _now(0))
        assert d.hour == 9
