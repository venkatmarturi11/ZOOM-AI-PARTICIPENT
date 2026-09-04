#!/usr/bin/env python3
"""
Native Zoom Meeting Runner & Unified PulseAudio + X11 FFmpeg Recorder.
Adapted from the proven zoomrec architecture for high-fidelity audio/video recording.
Communicates status via JSON-encoded stdout lines for Node.js parent integration.
"""

import os
import sys
import time
import json
import signal
import subprocess
import argparse
import atexit
from datetime import datetime

# Optional GUI libraries (fallback gracefully if testing without X11)
try:
    import pyautogui
    import psutil
    pyautogui.FAILSAFE = False
except ImportError:
    pyautogui = None
    psutil = None

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
IMG_PATH = os.path.join(PROJECT_ROOT, 'res', 'img')

def emit(event_type, **kwargs):
    """Emits structured JSON events to stdout for parent Node.js process."""
    data = {"event": event_type, "timestamp": datetime.utcnow().isoformat() + "Z"}
    data.update(kwargs)
    print(json.dumps(data), flush=True)

class NativeZoomRecorder:
    def __init__(self, meeting_id, password, display_name, target_url, output_path, resolution="1280x720", sink="speaker.monitor", display=":99"):
        self.meeting_id = str(meeting_id or "").strip()
        self.password = str(password or "").strip()
        self.display_name = str(display_name or "Zoom Bot").strip()
        self.target_url = str(target_url or "").strip()
        self.output_path = os.path.abspath(output_path)
        self.resolution = resolution
        self.sink = sink
        self.display = display
        
        self.zoom_proc = None
        self.ffmpeg_proc = None
        self.running = True
        self.in_meeting = False
        self.recording_started = False

    def cleanup(self):
        self.running = False
        emit("status", status="CLEANUP", message="Terminating processes...")
        
        # 1. Stop FFmpeg gracefully (SIGINT allows it to finalize MP4 moov atom)
        if self.ffmpeg_proc and self.ffmpeg_proc.poll() is None:
            try:
                emit("status", status="STOPPING_RECORDING", message="Finalizing video file...")
                self.ffmpeg_proc.send_signal(signal.SIGINT)
                try:
                    self.ffmpeg_proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.ffmpeg_proc.kill()
            except Exception as e:
                emit("log", message=f"FFmpeg stop error: {e}")

        # 2. Close Zoom
        if self.zoom_proc and self.zoom_proc.poll() is None:
            try:
                self.zoom_proc.terminate()
                try:
                    self.zoom_proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.zoom_proc.kill()
            except Exception as e:
                emit("log", message=f"Zoom proc kill error: {e}")

        # Kill any remaining zoom processes
        if psutil:
            for proc in psutil.process_iter(['name']):
                try:
                    if proc.info['name'] and 'zoom' in proc.info['name'].lower():
                        proc.kill()
                except Exception:
                    pass

        # Verify output file
        if os.path.exists(self.output_path) and os.path.getsize(self.output_path) > 1000:
            emit("status", status="STOPPED", message="Recording finalized successfully",
                 file_size=os.path.getsize(self.output_path), output_file=self.output_path)
        else:
            emit("status", status="STOPPED", message="Process ended, check output file", output_file=self.output_path)

    def locate_and_click(self, img_name, confidence=0.85, timeout=10, step=0.5):
        if not pyautogui:
            return False
        img_file = os.path.join(IMG_PATH, img_name)
        if not os.path.exists(img_file):
            return False

        end_time = time.time() + timeout
        while time.time() < end_time and self.running:
            try:
                location = pyautogui.locateCenterOnScreen(img_file, confidence=confidence)
                if location:
                    pyautogui.click(location)
                    return True
            except Exception:
                pass
            time.sleep(step)
        return False

    def show_toolbars(self):
        if not pyautogui:
            return
        try:
            w, h = pyautogui.size()
            pyautogui.moveTo(w // 2, 0, duration=0.2)
            pyautogui.moveTo(w // 2, h - 1, duration=0.2)
        except Exception:
            pass

    def start_ffmpeg_recording(self):
        if self.recording_started:
            return
        
        os.makedirs(os.path.dirname(self.output_path), exist_ok=True)
        disp = os.environ.get('DISPLAY', self.display)

        # Unified FFmpeg command identical to zoomrec
        # Captures PulseAudio virtual sink monitor AND X11 virtual screen simultaneously
        ffmpeg_cmd = [
            'ffmpeg', '-nostats', '-loglevel', 'error', '-y',
            '-f', 'pulse', '-ac', '2', '-i', self.sink,
            '-f', 'x11grab', '-r', '30', '-s', self.resolution, '-i', disp,
            '-acodec', 'aac', '-b:a', '192k',
            '-vcodec', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p',
            '-threads', '0', '-async', '1', '-vsync', '1',
            self.output_path
        ]

        emit("status", status="STARTING_RECORDING", message=f"Starting unified FFmpeg recording to {os.path.basename(self.output_path)}")
        try:
            self.ffmpeg_proc = subprocess.Popen(
                ffmpeg_cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                preexec_fn=os.setsid if hasattr(os, 'setsid') else None
            )
            self.recording_started = True
            emit("status", status="RECORDING", message="Live Recording Active (Video + Audio)", output_file=self.output_path)
        except Exception as e:
            emit("error", message=f"Failed to spawn FFmpeg: {e}")

    def run(self):
        emit("status", status="INITIALIZING", message="Starting Zoom Native Bot runner...")

        # Setup exit handlers
        signal.signal(signal.SIGINT, lambda s, f: sys.exit(0))
        signal.signal(signal.SIGTERM, lambda s, f: sys.exit(0))
        atexit.register(self.cleanup)

        # 1. Determine launch command
        join_url = self.target_url
        if not join_url and self.meeting_id:
            if "zoom.us" in self.meeting_id:
                join_url = self.meeting_id
            else:
                pwd_param = f"?pwd={self.password}" if self.password else ""
                join_url = f"https://zoom.us/j/{self.meeting_id}{pwd_param}"

        emit("status", status="LAUNCHING_ZOOM", message=f"Launching native Zoom client for: {join_url or self.meeting_id}")
        
        # Kill any zombie zoom instances first
        try:
            subprocess.run(["killall", "-9", "zoom"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

        time.sleep(1)

        # Start Zoom desktop client
        try:
            if join_url:
                zoom_cmd = f'zoom --url="{join_url}"'
            else:
                zoom_cmd = "zoom"
            self.zoom_proc = subprocess.Popen(
                zoom_cmd,
                shell=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                preexec_fn=os.setsid if hasattr(os, 'setsid') else None
            )
        except Exception as e:
            emit("error", message=f"Failed to execute zoom: {e}")
            return

        emit("status", status="WAITING_FOR_ZOOM", message="Waiting for Zoom window to open...")
        time.sleep(4)

        # 2. Automated Join Workflow
        start_time = time.time()
        while self.running and (time.time() - start_time < 90):
            # Check for "Join a Meeting" button if opened without direct URL
            if self.locate_and_click('join_meeting.png', timeout=1):
                emit("status", status="JOINING", message="Clicked 'Join a Meeting'")
                time.sleep(1.5)
                # Type meeting ID and name
                if pyautogui:
                    pyautogui.write(self.meeting_id, interval=0.05)
                    pyautogui.press('tab')
                    pyautogui.write(self.display_name, interval=0.05)
                    pyautogui.press('enter')
                time.sleep(2)

            # Passcode prompt if present
            if self.password and self.locate_and_click('join.png', timeout=1):
                time.sleep(1)
                if pyautogui:
                    pyautogui.write(self.password, interval=0.05)
                    pyautogui.press('enter')

            # Join with Computer Audio
            if self.locate_and_click('join_with_computer_audio.png', timeout=1.5):
                emit("status", status="AUDIO_CONNECTED", message="Joined with Computer Audio!")
                self.in_meeting = True
                break

            if self.locate_and_click('join_audio.png', timeout=1.5):
                time.sleep(1)
                self.locate_and_click('join_with_computer_audio.png', timeout=2)
                self.in_meeting = True
                break

            # Popups / Consents ("Got it", "OK", "Meeting is being recorded")
            self.locate_and_click('got_it.png', timeout=0.5)
            self.locate_and_click('ok.png', timeout=0.5)
            self.locate_and_click('meeting_is_being_recorded.png', timeout=0.5)

            time.sleep(1)

        # 3. In Meeting: Ensure Muted, Fullscreen, and Start FFmpeg Recording
        emit("status", status="IN_MEETING", message="In Meeting Room. Muting mic and starting recording...")
        time.sleep(2)

        # Dismiss any leftover consent dialogs
        self.locate_and_click('got_it.png', timeout=1)
        self.locate_and_click('ok.png', timeout=1)

        # Mute microphone if unmuted
        self.show_toolbars()
        if self.locate_and_click('mute.png', timeout=1.5):
            emit("status", status="MUTED", message="Participant microphone MUTED")

        # Fullscreen meeting
        self.show_toolbars()
        self.locate_and_click('view.png', timeout=1)
        self.locate_and_click('fullscreen.png', timeout=1)

        # 4. Start Unified FFmpeg Recording
        self.start_ffmpeg_recording()

        # 5. Maintenance Loop: Keep popups dismissed and watch for meeting end
        while self.running:
            time.sleep(3)

            # Periodic popup dismissal ("OK", "Got it", "Recording notice")
            self.locate_and_click('got_it.png', timeout=0.3)
            self.locate_and_click('ok.png', timeout=0.3)

            # Detect meeting ended by host
            if (self.locate_and_click('meeting_ended_by_host_1.png', timeout=0.3) or
                self.locate_and_click('meeting_ended_by_host_2.png', timeout=0.3)):
                emit("status", status="MEETING_ENDED", message="Meeting ended by host")
                break

            # Check if Zoom process died
            if self.zoom_proc and self.zoom_proc.poll() is not None:
                emit("status", status="ZOOM_EXITED", message="Zoom process exited")
                break

def main():
    parser = argparse.ArgumentParser(description="Native Zoom Runner & Recorder (zoomrec architecture)")
    parser.add_argument("--meeting-id", dest="meeting_id", default="")
    parser.add_argument("--password", dest="password", default="")
    parser.add_argument("--name", dest="display_name", default="Zoom Bot")
    parser.add_argument("--url", dest="target_url", default="")
    parser.add_argument("--output", dest="output_path", required=True)
    parser.add_argument("--resolution", dest="resolution", default="1280x720")
    parser.add_argument("--sink", dest="sink", default="speaker.monitor")
    parser.add_argument("--display", dest="display", default=":99")

    args = parser.parse_args()

    recorder = NativeZoomRecorder(
        meeting_id=args.meeting_id,
        password=args.password,
        display_name=args.display_name,
        target_url=args.target_url,
        output_path=args.output_path,
        resolution=args.resolution,
        sink=args.sink,
        display=args.display
    )
    recorder.run()

if __name__ == "__main__":
    main()
