# Experimental WebView Capture Research Module

> **Status:** Research & Experimental (Non-Production Fallback)  
> **Location:** `experimental/webview-recorder/`  

---

## Overview

This module isolates the client-side WebView automation, `PixelCopy` surface capture, and Web Audio API exploration from the authoritative production server-side RTMS media pipeline.

### Core Experiments:
1. **In-App Window Surface Capture via `PixelCopy`**:
   - Direct window decor view frame grabbing avoiding system screen-cast prompts for own-window content.
   - Zero-allocation `BufferPool` and fast bitwise ARGB $\to$ NV12 transcoders.
2. **Web Audio Tap Experiments**:
   - `AudioWorklet` and `RTCPeerConnection` media track hooks.
3. **Synthetic WebRTC Environment Mocking**:
   - Synthetic oscillator silent audio and black canvas video streams.

### Architecture Isolation Rule:
The production application must never depend on the experimental WebView module for meeting recordings. All authoritative recordings are performed server-side via Zoom RTMS.
