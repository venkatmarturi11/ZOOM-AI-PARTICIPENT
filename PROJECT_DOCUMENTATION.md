# Zoom Meeting Recording Suite — Master Technical Specification (v3.0.0)

> **Architecture:** Server-Side RTMS Media Pipeline + Multi-Platform Control Clients  
> **Primary Recording Engine:** Zoom Realtime Media Streams (RTMS) + FFmpeg Muxing  
> **Control Clients:** Android App (Jetpack Compose) • Desktop App (Electron/React) • Telegram Bot  
> **Security Model:** OAuth 2.0 / ZAK Tokenization • Encrypted At-Rest • Ephemeral Passcodes  
> **Document Version:** 3.0.0 — Production Architecture Standard  

---

## 1. System Architecture Overview

```
                 ┌─────────────────────────────────────────┐
                 │          CLIENT CONTROL SURFACES        │
                 │                                         │
                 │   Android App • Desktop • Telegram      │
                 └────────────────────┬────────────────────┘
                                      │ HTTPS / WebSocket
                                      ▼
                 ┌─────────────────────────────────────────┐
                 │               BACKEND API               │
                 │          Node.js / TypeScript           │
                 │      Auth • Jobs • Database Store       │
                 └────────────────────┬────────────────────┘
                                      │
                               Redis / BullMQ
                                      │
                                      ▼
                 ┌─────────────────────────────────────────┐
                 │             RTMS MEDIA WORKER           │
                 │                                         │
                 │  Zoom RTMS Stream Ingestion (Audio/Vid) │
                 │  State Machine & Timestamp Synchronizer │
                 └────────────────────┬────────────────────┘
                                      │
                                      ▼
                 ┌─────────────────────────────────────────┐
                 │             FFMPEG PIPELINE             │
                 │                                         │
                 │   H.264 / AAC Muxing & MP4 Packaging   │
                 └────────────────────┬────────────────────┘
                                      │
                                      ▼
                 ┌─────────────────────────────────────────┐
                 │          PRIVATE OBJECT STORAGE         │
                 │           S3 / Signed URLs              │
                 └─────────────────────────────────────────┘
```

---

## 2. Client Roles & Responsibilities

Client applications serve as **control and monitoring interfaces** rather than handling raw recording:

| Platform | Primary Responsibilities |
| :--- | :--- |
| **Android Application** | User authentication, meeting scheduling, job dispatch, live recording HUD, media playback via Media3 ExoPlayer, signed URL downloads. |
| **Desktop Client (Electron)** | Dashboard overview, quick meeting intake, live waveform monitor, playback library, system diagnostic settings. |
| **Telegram Control Plane** | Remote headless commands (`/record`, `/stop`, `/status`, `/recordings`, `/health`), allowlist authorization, status alerts. |

---

## 3. Core Server Pipeline & RTMS Worker

### 3.1 Media Ingestion & Timestamping
* **Stream Reception:** Ingests raw audio and video chunks from Zoom's official RTMS WebSocket endpoints.
* **Timestamp Alignment:** Uses source packet presentation timestamps rather than arrival times to prevent audio/video drift.

### 3.2 State Machine Lifecycle
```
CREATED ──► QUEUED ──► STARTING ──► CONNECTING ──► CONNECTED ──► RECORDING
                                                                     │
                                                                     ▼
COMPLETED ◄── UPLOADING ◄── FINALIZING ◄── STOPPING ◄────────────────┘
```

### 3.3 FFmpeg Packaging
* Muxes synchronized streams with `-movflags +faststart` for immediate playback.
* Conducts automated finalization checks (container integrity, duration validation, file size verification).

---

## 4. Security & Privacy Architecture

* **Authentication:** Uses official Zoom OAuth 2.0 and ZAK (Zoom Access Key) tokens.
* **Credential Safety:** Client secrets and access tokens are restricted to the server environment.
* **Ephemeral Credentials:** Meeting passcodes are held in memory during the active session rather than stored unencrypted.
* **Access Control:** Audio/video playback URLs are delivered via short-lived signed URLs.

---

## 5. Research & Experimental Modules

### 5.1 Experimental WebView Capture Research Module
* **Classification:** Research & Experimental (Non-Production Fallback).
* **Scope:** Local window surface capture using Android `PixelCopy` and Web Audio API exploration.
* **Condition:** Maintained independently in `experimental/webview-recorder/` to ensure core server-side RTMS stability.

---

## 6. Implementation Roadmap (Phases 1–12)

```
Phase 01: Backend API, Database Schema & BullMQ Job Queue
Phase 02: Zoom OAuth 2.0 & Token Refresh Lifecycle
Phase 02.5: Meeting Eligibility & RTMS Preflight Engine
Phase 03: RTMS WebSocket Worker Implementation
Phase 04: Audio/Video Ingestion & Stream Timestamping
Phase 05: FFmpeg Synchronized MP4 Finalization
Phase 06: Private Cloud Storage & Signed URL Delivery
Phase 07: Android Application (Control, HUD & Playback)
Phase 08: Electron Desktop Application
Phase 09: Telegram Remote Control Plane
Phase 10: Fault Tolerance, Heartbeat Watchdogs & Recovery
Phase 11: Experimental WebView Capture Research Module
Phase 12: AI Transcription & Search Layer
```
