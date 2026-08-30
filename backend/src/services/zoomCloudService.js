// server/services/zoomCloudService.js
//
// Official, ToS-compliant Zoom integration using Server-to-Server OAuth.
// This replaces browser automation entirely: the host explicitly authorizes
// this app once in the Zoom Marketplace, and from then on Zoom itself
// records the meeting (cloud recording) and notifies us via webhook when the
// file is ready. No fake participant, no CAPTCHA, no waiting room, no
// pretending to be a human — Zoom knows exactly what this is.

const https = require('https');

const ZOOM_ACCOUNT_ID = process.env.ZOOM_ACCOUNT_ID;
const ZOOM_CLIENT_ID = process.env.ZOOM_CLIENT_ID;
const ZOOM_CLIENT_SECRET = process.env.ZOOM_CLIENT_SECRET;

function isConfigured() {
  const isPlaceholder = (v) => !v || v.startsWith('your-zoom-');
  return !isPlaceholder(ZOOM_ACCOUNT_ID) && !isPlaceholder(ZOOM_CLIENT_ID) && !isPlaceholder(ZOOM_CLIENT_SECRET);
}

let cachedToken = null;
let cachedTokenExpiry = 0;

// Server-to-Server OAuth: exchange account credentials for a short-lived
// access token. Real Zoom API call — no fabricated tokens.
async function getAccessToken() {
  if (!isConfigured()) {
    throw new Error('Zoom Server-to-Server OAuth is not configured (ZOOM_ACCOUNT_ID / ZOOM_CLIENT_ID / ZOOM_CLIENT_SECRET missing).');
  }

  if (cachedToken && Date.now() < cachedTokenExpiry - 60000) {
    return cachedToken;
  }

  const basicAuth = Buffer.from(`${ZOOM_CLIENT_ID}:${ZOOM_CLIENT_SECRET}`).toString('base64');
  const body = await httpsRequest({
    hostname: 'zoom.us',
    path: `/oauth/token?grant_type=account_credentials&account_id=${ZOOM_ACCOUNT_ID}`,
    method: 'POST',
    headers: { 'Authorization': `Basic ${basicAuth}` }
  });

  const data = JSON.parse(body);
  if (!data.access_token) {
    throw new Error(`Zoom OAuth token request failed: ${body}`);
  }

  cachedToken = data.access_token;
  cachedTokenExpiry = Date.now() + (data.expires_in * 1000);
  return cachedToken;
}

// Creates a real Zoom meeting under the host's own account, with cloud
// auto-recording enabled, so the meeting records itself the moment it
// starts — no bot needs to join at all.
async function createAutoRecordedMeeting({ topic, startTime, durationMinutes, hostEmail }) {
  const token = await getAccessToken();
  const payload = JSON.stringify({
    topic: topic || 'Recorded Meeting',
    type: startTime ? 2 : 1, // scheduled vs instant
    start_time: startTime,
    duration: durationMinutes || 60,
    settings: {
      auto_recording: 'cloud',
      join_before_host: true,
      waiting_room: false
    }
  });

  const userPath = hostEmail ? `users/${encodeURIComponent(hostEmail)}` : 'users/me';
  const body = await httpsRequest({
    hostname: 'api.zoom.us',
    path: `/v2/${userPath}/meetings`,
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    }
  }, payload);

  const data = JSON.parse(body);
  if (!data.id) {
    throw new Error(`Zoom create-meeting failed: ${body}`);
  }
  return data; // includes join_url, start_url, id, password
}

// Fetches the cloud recording metadata for a given meeting, including the
// real, time-limited download URL Zoom generates for each file.
async function getMeetingRecordings(meetingId) {
  const token = await getAccessToken();
  const body = await httpsRequest({
    hostname: 'api.zoom.us',
    path: `/v2/meetings/${meetingId}/recordings`,
    method: 'GET',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  return JSON.parse(body);
}

// Streams a real Zoom-hosted recording file to a local path. Zoom's
// download_url requires the access token appended as a query param OR an
// Authorization header depending on file type; we use the header form,
// which works for the standard MP4/M4A/TRANSCRIPT file types.
async function downloadRecordingFile(downloadUrl, destPath, downloadToken = null) {
  const fs = require('fs');
  const token = downloadToken || (await getAccessToken());
  
  let targetUrl = downloadUrl;
  if (downloadToken) {
    const parsed = new URL(downloadUrl);
    parsed.searchParams.set('access_token', downloadToken);
    targetUrl = parsed.toString();
  }

  const url = new URL(targetUrl);

  return new Promise((resolve, reject) => {
    const headers = downloadToken ? {} : { 'Authorization': `Bearer ${token}` };

    const req = https.request({
      hostname: url.hostname,
      path: url.pathname + url.search,
      method: 'GET',
      headers
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        // Zoom redirects to pre-signed S3 storage URL (which does NOT need auth header)
        https.get(res.headers.location, (res2) => {
          const file = fs.createWriteStream(destPath);
          res2.pipe(file);
          file.on('finish', () => { file.close(); resolve(destPath); });
          file.on('error', reject);
        }).on('error', reject);
        return;
      }
      if (res.statusCode !== 200) {
        reject(new Error(`Zoom file download failed: HTTP ${res.statusCode}`));
        return;
      }
      const file = fs.createWriteStream(destPath);
      res.pipe(file);
      file.on('finish', () => { file.close(); resolve(destPath); });
      file.on('error', reject);
    });
    req.on('error', reject);
    req.end();
  });
}

function httpsRequest(options, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => resolve(data));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

module.exports = {
  isConfigured,
  getAccessToken,
  createAutoRecordedMeeting,
  getMeetingRecordings,
  downloadRecordingFile
};
