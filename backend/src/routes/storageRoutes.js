const express = require('express');
const router = express.Router();
const path = require('path');
const fs = require('fs');
const multer = require('multer');
const cloudStorage = require('../services/cloudStorageService');

const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '../..');
const STORAGE_DIR = path.join(PERSIST_DIR, 'storage');
if (!fs.existsSync(STORAGE_DIR)) fs.mkdirSync(STORAGE_DIR, { recursive: true });

// Configure Multer for cloud storage uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, STORAGE_DIR);
  },
  filename: (req, file, cb) => {
    const timestamp = Date.now();
    const cleanOriginal = file.originalname.replace(/[^a-zA-Z0-9_.-]/g, '_');
    cb(null, `${timestamp}_${cleanOriginal}`);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 2 * 1024 * 1024 * 1024 } // 2 GB per file
});

/**
 * POST /api/storage/upload
 * Multi-file and single-file upload endpoint
 */
router.post('/upload', upload.array('files', 10), (req, res) => {
  if (!req.files || req.files.length === 0) {
    return res.status(400).json({ success: false, message: 'No files uploaded.' });
  }

  const uploadedItems = [];
  const uploadCategory = req.body.category || (req.body.source === 'mobile' ? 'mobile_storage' : 'upload');
  const uploader = req.body.source === 'mobile' ? 'mobile_app' : (req.user ? req.user.email : 'studio_user');

  for (const file of req.files) {
    const item = cloudStorage.addUploadedFile({
      originalName: file.originalname,
      fileName: file.filename,
      filePath: file.path,
      sizeBytes: file.size,
      mimeType: file.mimetype,
      category: uploadCategory,
      uploadedBy: uploader
    });
    uploadedItems.push(item);
  }

  console.log(`[CloudStorage] Successfully uploaded ${uploadedItems.length} file(s).`);
  res.json({
    success: true,
    message: `Successfully uploaded ${uploadedItems.length} file(s) to Cloud Storage Vault.`,
    files: uploadedItems
  });
});

/**
 * GET /api/storage/files
 * Lists all files and recordings stored in the vault
 */
router.get('/files', (req, res) => {
  const category = req.query.category || null;
  const files = cloudStorage.listFiles(category);
  res.json({ success: true, count: files.length, files });
});

/**
 * GET /api/storage/stats
 * Storage usage metrics and media breakdown
 */
router.get('/stats', (req, res) => {
  const stats = cloudStorage.getStorageStats();
  const netInfo = cloudStorage.getServerNetworkInfo(process.env.PORT || 3000);
  res.json({
    success: true,
    stats,
    server: netInfo
  });
});

/**
 * GET /api/storage/stream/:fileId
 * HTTP 206 Partial Content Video/Audio Streaming with range request support
 */
router.get('/stream/:fileId', (req, res) => {
  const item = cloudStorage.getFile(req.params.fileId);
  if (!item || !fs.existsSync(item.filePath)) {
    return res.status(404).json({ success: false, message: 'File not found in storage.' });
  }

  const stat = fs.statSync(item.filePath);
  const fileSize = stat.size;
  const range = req.headers.range;

  if (range) {
    const parts = range.replace(/bytes=/, "").split("-");
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;

    if (start >= fileSize) {
      res.status(416).send(`Requested range not satisfiable\n${start} >= ${fileSize}`);
      return;
    }

    const chunksize = (end - start) + 1;
    const fileStream = fs.createReadStream(item.filePath, { start, end });
    const head = {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': item.mimeType || 'video/mp4',
    };
    res.writeHead(206, head);
    fileStream.pipe(res);
  } else {
    const head = {
      'Content-Length': fileSize,
      'Content-Type': item.mimeType || 'video/mp4',
      'Accept-Ranges': 'bytes',
    };
    res.writeHead(200, head);
    fs.createReadStream(item.filePath).pipe(res);
  }
});

/**
 * GET /api/storage/download/:fileId
 * Direct file download with attachment headers
 */
router.get('/download/:fileId', (req, res) => {
  const item = cloudStorage.getFile(req.params.fileId);
  if (!item || !fs.existsSync(item.filePath)) {
    return res.status(404).json({ success: false, message: 'File not found.' });
  }

  cloudStorage.incrementDownloadCount(item.id);
  res.download(item.filePath, item.originalName || item.fileName);
});

/**
 * POST /api/storage/share
 * Generates an expiring public share link
 */
router.post('/share', (req, res) => {
  const { fileId, expiresInHours, password, allowDownload } = req.body;
  if (!fileId) {
    return res.status(400).json({ success: false, message: 'fileId is required' });
  }

  try {
    const share = cloudStorage.createShareLink(fileId, {
      expiresInHours: Number(expiresInHours) || 24,
      password,
      allowDownload: allowDownload !== false
    });

    const host = req.get('host') || `localhost:${process.env.PORT || 3000}`;
    const protocol = req.protocol || 'http';
    const publicShareUrl = `${protocol}://${host}/s/${share.shareId}`;

    res.json({
      success: true,
      shareId: share.shareId,
      publicShareUrl,
      expiresAt: share.expiresAt,
      message: 'Shareable public link generated successfully!'
    });
  } catch (err) {
    res.status(500).json({ success: false, message: err.message });
  }
});

/**
 * DELETE /api/storage/files/:fileId
 * Deletes file permanently
 */
router.delete('/files/:fileId', (req, res) => {
  const ok = cloudStorage.deleteFile(req.params.fileId);
  if (!ok) {
    return res.status(404).json({ success: false, message: 'File not found or already deleted.' });
  }
  res.json({ success: true, message: 'File deleted from Cloud Storage Vault successfully.' });
});

/**
 * POST /api/storage/sync-drive/:fileId
 * Syncs a vault file to Google Drive
 */
router.post('/sync-drive/:fileId', async (req, res) => {
  const item = cloudStorage.getFile(req.params.fileId);
  if (!item || !fs.existsSync(item.filePath)) {
    return res.status(404).json({ success: false, message: 'File not found.' });
  }

  try {
    const driveService = require('../services/driveService');
    const userId = req.body.userId || (req.user ? req.user.email : 'default_user');

    if (!driveService.isUserConnected(userId)) {
      return res.status(400).json({
        success: false,
        message: 'Google Drive is not connected for this user. Connect Google Drive first in Settings.'
      });
    }

    const driveResult = await driveService.uploadRecording(userId, item.filePath);
    res.json({
      success: true,
      message: 'File synced to Google Drive successfully!',
      driveFileId: driveResult.id,
      webViewLink: driveResult.webViewLink
    });
  } catch (err) {
    console.error('[CloudStorage] Drive sync error:', err.message);
    res.status(500).json({ success: false, message: `Google Drive sync failed: ${err.message}` });
  }
});

module.exports = router;
