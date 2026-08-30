// server/routes/zoomWebhookRoutes.js
//
// Receives real Zoom webhook events. Two things happen here:
//  1. Zoom's mandatory "URL validation" handshake (endpoint.url_validation) —
//     Zoom sends this once when you save the webhook URL in the Marketplace
//     app, and periodically to re-verify. Must respond with an HMAC-SHA256
//     hash of the plainToken, or Zoom refuses to deliver real events.
//  2. recording.completed — fired when a cloud recording finishes
//     processing. We download the real file and drop it into the same
//     recordings pipeline (and metadata format) the rest of the app uses.

const express = require('express');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const router = express.Router();

const ZOOM_WEBHOOK_SECRET_TOKEN_RAW = process.env.ZOOM_WEBHOOK_SECRET_TOKEN;
const ZOOM_WEBHOOK_SECRET_TOKEN = (ZOOM_WEBHOOK_SECRET_TOKEN_RAW && !ZOOM_WEBHOOK_SECRET_TOKEN_RAW.startsWith('your-zoom-'))
  ? ZOOM_WEBHOOK_SECRET_TOKEN_RAW
  : null;

router.post('/', async (req, res) => {
  const event = req.body;

  // --- 1. URL validation handshake (required by Zoom before it will send real events) ---
  if (event && event.event === 'endpoint.url_validation') {
    if (!ZOOM_WEBHOOK_SECRET_TOKEN) {
      console.error('[Zoom Webhook] Received validation challenge but ZOOM_WEBHOOK_SECRET_TOKEN is not set — cannot respond correctly.');
      return res.status(500).json({ error: 'Webhook secret token not configured' });
    }
    const plainToken = event.payload.plainToken;
    const hashForValidate = crypto
      .createHmac('sha256', ZOOM_WEBHOOK_SECRET_TOKEN)
      .update(plainToken)
      .digest('hex');
    return res.json({ plainToken, encryptedToken: hashForValidate });
  }

  // --- 2. Verify the signature on every real event, so we only trust genuine Zoom traffic ---
  // Uses the raw request bytes (req.rawBody, captured by the express.json()
  // verify hook in bot_manager.js) rather than JSON.stringify(req.body) —
  // re-serializing a parsed object is not guaranteed byte-identical to what
  // Zoom actually signed, which would cause valid webhooks to intermittently
  // fail verification.
  if (ZOOM_WEBHOOK_SECRET_TOKEN) {
    const timestamp = req.headers['x-zm-request-timestamp'];
    const signature = req.headers['x-zm-signature'];
    const rawBody = req.rawBody ? req.rawBody.toString('utf8') : JSON.stringify(req.body);
    const message = `v0:${timestamp}:${rawBody}`;
    const expectedSig = 'v0=' + crypto.createHmac('sha256', ZOOM_WEBHOOK_SECRET_TOKEN).update(message).digest('hex');
    if (signature !== expectedSig) {
      console.warn('[Zoom Webhook] Signature mismatch — rejecting event (not genuinely from Zoom).');
      return res.status(401).json({ error: 'Invalid signature' });
    }
  }

  // Acknowledge immediately; Zoom expects a fast 200 and will retry if you're slow.
  res.status(200).json({ received: true });

  if (!event || event.event !== 'recording.completed') return;

  try {
    await handleRecordingCompleted(event, req.app.locals);
  } catch (err) {
    console.error('[Zoom Webhook] Error processing recording.completed:', err.message);
  }
});

async function handleRecordingCompleted(event, appLocals) {
  const zoomCloudService = require('../services/zoomCloudService');
  const obj = event.payload && event.payload.object;
  if (!obj || !obj.recording_files) {
    console.warn('[Zoom Webhook] recording.completed event missing recording_files');
    return;
  }

  const downloadToken = event.download_token || (event.payload && event.payload.download_token);

  const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '..');
  const RECORDINGS_DIR = path.join(PERSIST_DIR, 'recordings');
  if (!fs.existsSync(RECORDINGS_DIR)) fs.mkdirSync(RECORDINGS_DIR, { recursive: true });

  // Download audio-only (M4A) and/or video (MP4) files
  const audioFile = obj.recording_files.find(f => f.file_type === 'M4A' || f.recording_type === 'audio_only');
  const videoFile = obj.recording_files.find(f => f.file_type === 'MP4' || f.recording_type === 'shared_screen_with_speaker_view');

  const filesToDownload = [];
  if (audioFile) filesToDownload.push({ file: audioFile, ext: 'm4a', label: 'Audio_Only' });
  if (videoFile) filesToDownload.push({ file: videoFile, ext: 'mp4', label: 'Full_Video' });

  if (filesToDownload.length === 0) {
    console.warn('[Zoom Webhook] No audio/video files in recording.completed payload for meeting', obj.id);
    return;
  }

  for (const item of filesToDownload) {
    try {
      const fileName = `ZoomMeeting_${obj.id}_${item.label}_${Date.now()}.${item.ext}`;
      const filePath = path.join(RECORDINGS_DIR, fileName);

      console.log(`[Zoom Webhook] Downloading ${item.label} cloud recording for meeting ${obj.id} (${obj.topic})...`);
      await zoomCloudService.downloadRecordingFile(item.file.download_url, filePath, downloadToken);

      const stat = fs.statSync(filePath);
      if (stat && stat.size > 0 && appLocals && typeof appLocals.addRecordingToVault === 'function') {
        appLocals.addRecordingToVault({
          fileName,
          filePath,
          meetingId: String(obj.id),
          botName: `${obj.host_email || obj.topic || 'Zoom Cloud Recording'} (${item.label})`,
          sizeBytes: stat.size,
          source: 'zoom_cloud_recording'
        });
      }
    } catch (downloadErr) {
      console.error(`[Zoom Webhook] Failed downloading ${item.label}:`, downloadErr.message);
    }
  }

  console.log(`[Zoom Webhook] Recording saved: ${fileName} (${(stat.size / 1024 / 1024).toFixed(2)} MB)`);
}

module.exports = router;
