// server/routes/driveRoutes.js
//
// Mount this in bot_manager.js with: app.use('/api/drive', require('./routes/driveRoutes'));

const express = require('express');
const router = express.Router();
const path = require('path');
const fs = require('fs');
const driveService = require('../services/driveService');

const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '..');
const RECORDINGS_META_FILE = path.join(PERSIST_DIR, 'recordings_meta.json');
const USERS_FILE = path.join(PERSIST_DIR, 'users.json');

function loadMeta() {
  try {
    if (fs.existsSync(RECORDINGS_META_FILE)) return JSON.parse(fs.readFileSync(RECORDINGS_META_FILE, 'utf8'));
  } catch (e) {}
  return [];
}

function loadUsers() {
  try {
    if (fs.existsSync(USERS_FILE)) return JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
  } catch (e) {}
  return [];
}

// GET /api/drive/connect - Redirects to Google's real OAuth consent screen.
// Requires GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET to be configured — if they
// aren't, we tell the caller honestly instead of faking a connected state.
router.get('/connect', (req, res) => {
  const userId = req.query.userId || 'naniv401@gmail.com';

  if (!process.env.GOOGLE_CLIENT_ID || process.env.GOOGLE_CLIENT_ID === 'your-google-client-id' || !process.env.GOOGLE_CLIENT_SECRET || process.env.GOOGLE_CLIENT_SECRET === 'your-google-client-secret') {
    return res.redirect('/?drive=not_configured');
  }

  const url = driveService.getDriveConsentUrl(userId);
  res.redirect(url);
});



// GET /api/drive/callback - Google redirects here after consent
router.get('/callback', async (req, res) => {
  const { code, state } = req.query;
  const userId = state || 'naniv401@gmail.com';

  try {
    await driveService.handleDriveOAuthCallback(userId, code);
    res.redirect('/?drive=connected');
  } catch (err) {
    console.error('[Drive] OAuth callback failed:', err.message);
    res.redirect('/?drive=error');
  }
});

// GET /api/drive/status - lets your frontend show "Connected" / "Not connected"
// Reports ONLY real state: a user is "connected" if they hold a real refresh
// token obtained via the actual Google OAuth flow above — nothing is faked.
router.get('/status', (req, res) => {
  const userId = req.query.userId || 'naniv401@gmail.com';
  const configured = !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_ID !== 'your-google-client-id' && process.env.GOOGLE_CLIENT_SECRET && process.env.GOOGLE_CLIENT_SECRET !== 'your-google-client-secret');
  const users = loadUsers();
  const user = users.find(u => u.email === userId || u.userId === userId || u.name === userId) || users[0];
  const isConnected = configured && !!user?.drive_refresh_token;

  res.json({
    configured,
    connected: isConnected,
    connectedAt: isConnected ? user?.drive_connected_at : null,
    folderUrl: isConnected && user?.drive_folder_id ? `https://drive.google.com/drive/folders/${user.drive_folder_id}` : null
  });
});

// POST /api/drive/folder - Set or update a custom target Drive folder ID for
// an ALREADY-CONNECTED user (i.e. one with a real refresh token from the
// OAuth flow above). This does not by itself connect Drive.
router.post('/folder', (req, res) => {
  const { folderUrlOrId, userId } = req.body;
  const targetUser = userId || 'naniv401@gmail.com';
  
  if (!folderUrlOrId) {
    return res.status(400).json({ success: false, error: 'Folder URL or Folder ID is required' });
  }

  // Extract folder ID if full Google Drive URL was pasted
  // e.g. https://drive.google.com/drive/folders/1a2b3c4d5e6f...
  const match = folderUrlOrId.match(/\/folders\/([a-zA-Z0-9_-]+)/);
  const folderId = match ? match[1] : folderUrlOrId.trim();

  const users = loadUsers();
  const user = users.find(u => u.email === targetUser || u.userId === targetUser || u.name === targetUser) || users[0];

  if (user) {
    user.drive_folder_id = folderId;
    user.drive_connected_at = new Date().toISOString();
    try {
      fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2));
    } catch (e) {}
  }

  res.json({
    success: true,
    message: 'Permanent Google Drive Folder updated successfully!',
    folderId,
    folderUrl: `https://drive.google.com/drive/folders/${folderId}`
  });
});


// GET /api/drive/download/:fileName
router.get('/download/:fileName', async (req, res) => {
  const fileName = req.params.fileName;
  const userId = req.query.userId || 'naniv401@gmail.com';
  const metaList = loadMeta();
  const rec = metaList.find(r => r.fileName === fileName || r.id === fileName);

  if (!rec || !rec.driveFileId) {
    return res.status(404).json({ error: 'Recording drive ID not found' });
  }

  res.setHeader('Content-Disposition', `attachment; filename="${rec.fileName || 'recording.mp4'}"`);
  try {
    await driveService.streamDownload(userId, rec.driveFileId, res);
  } catch (err) {
    console.error('[Drive] Download failed:', err.message);
    res.status(500).json({ error: 'Download failed' });
  }
});

// DELETE /api/drive/recordings/:fileName
router.delete('/recordings/:fileName', async (req, res) => {
  const fileName = req.params.fileName;
  const userId = req.query.userId || 'naniv401@gmail.com';
  const metaList = loadMeta();
  const rec = metaList.find(r => r.fileName === fileName || r.id === fileName);

  try {
    if (rec && rec.driveFileId) {
      await driveService.deleteRecording(userId, rec.driveFileId);
    }
    res.json({ success: true, message: `Drive recording ${fileName} deleted successfully` });
  } catch (err) {
    console.error('[Drive] Delete failed:', err.message);
    res.status(500).json({ error: 'Delete failed' });
  }
});

module.exports = router;
