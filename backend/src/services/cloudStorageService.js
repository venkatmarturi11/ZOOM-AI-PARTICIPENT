const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const os = require('os');

const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '../..');
const RECORDINGS_DIR = process.env.RECORDINGS_DIR || path.join(PERSIST_DIR, 'recordings');
const STORAGE_DIR = path.join(PERSIST_DIR, 'storage');
const METADATA_FILE = path.join(PERSIST_DIR, 'storage_meta.json');
const SHARES_FILE = path.join(PERSIST_DIR, 'storage_shares.json');

// Ensure directories exist
[RECORDINGS_DIR, STORAGE_DIR].forEach(dir => {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
});

class CloudStorageService {
  constructor() {
    this.meta = this.loadMeta();
    this.shares = this.loadShares();
    this.syncExistingFiles();
  }

  loadMeta() {
    try {
      if (fs.existsSync(METADATA_FILE)) {
        return JSON.parse(fs.readFileSync(METADATA_FILE, 'utf8'));
      }
    } catch (e) {
      console.warn('[CloudStorage] Notice loading storage_meta.json:', e.message);
    }
    return [];
  }

  saveMeta() {
    try {
      fs.writeFileSync(METADATA_FILE, JSON.stringify(this.meta, null, 2), 'utf8');
    } catch (e) {
      console.error('[CloudStorage] Failed to save storage_meta.json:', e.message);
    }
  }

  loadShares() {
    try {
      if (fs.existsSync(SHARES_FILE)) {
        return JSON.parse(fs.readFileSync(SHARES_FILE, 'utf8'));
      }
    } catch (e) {
      console.warn('[CloudStorage] Notice loading storage_shares.json:', e.message);
    }
    return {};
  }

  saveShares() {
    try {
      fs.writeFileSync(SHARES_FILE, JSON.stringify(this.shares, null, 2), 'utf8');
    } catch (e) {
      console.error('[CloudStorage] Failed to save storage_shares.json:', e.message);
    }
  }

  /**
   * Automatically scans recordings/ and storage/ to discover and index files
   */
  syncExistingFiles() {
    const existingFileNames = new Set(this.meta.map(m => m.fileName));
    const dirsToScan = [
      { dir: RECORDINGS_DIR, category: 'recording' },
      { dir: STORAGE_DIR, category: 'upload' }
    ];

    let changes = false;
    for (const { dir, category } of dirsToScan) {
      if (!fs.existsSync(dir)) continue;
      const files = fs.readdirSync(dir);
      for (const file of files) {
        if (file.startsWith('page@') || file.endsWith('.tmp')) continue;
        if (!existingFileNames.has(file)) {
          const filePath = path.join(dir, file);
          try {
            const stats = fs.statSync(filePath);
            if (stats.isFile()) {
              const fileId = `file_${crypto.randomBytes(6).toString('hex')}`;
              this.meta.push({
                id: fileId,
                fileName: file,
                originalName: file,
                filePath,
                fileSize: stats.size,
                mimeType: this.detectMimeType(file),
                category,
                createdAt: stats.birthtime ? stats.birthtime.toISOString() : new Date().toISOString(),
                downloadsCount: 0
              });
              existingFileNames.add(file);
              changes = true;
            }
          } catch (e) {}
        }
      }
    }

    if (changes) {
      this.saveMeta();
    }
  }

  detectMimeType(fileName) {
    const ext = path.extname(fileName).toLowerCase();
    const map = {
      '.mp4': 'video/mp4',
      '.webm': 'video/webm',
      '.mkv': 'video/x-matroska',
      '.mov': 'video/quicktime',
      '.avi': 'video/x-msvideo',
      '.mp3': 'audio/mpeg',
      '.wav': 'audio/wav',
      '.m4a': 'audio/mp4',
      '.ogg': 'audio/ogg',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.jpeg': 'image/jpeg',
      '.webp': 'image/webp',
      '.gif': 'image/gif',
      '.pdf': 'application/pdf',
      '.txt': 'text/plain',
      '.json': 'application/json',
      '.apk': 'application/vnd.android.package-archive',
      '.zip': 'application/zip'
    };
    return map[ext] || 'application/octet-stream';
  }

  /**
   * Registers a newly uploaded file
   */
  addUploadedFile({ originalName, fileName, filePath, sizeBytes, mimeType, uploadedBy, category }) {
    const fileId = `file_${crypto.randomBytes(6).toString('hex')}`;
    const item = {
      id: fileId,
      fileName,
      originalName: originalName || fileName,
      filePath,
      fileSize: sizeBytes,
      mimeType: mimeType || this.detectMimeType(fileName),
      category: category || 'upload',
      uploadedBy: uploadedBy || 'user',
      createdAt: new Date().toISOString(),
      downloadsCount: 0
    };
    this.meta.unshift(item);
    this.saveMeta();
    return item;
  }

  getFile(fileIdOrName) {
    return this.meta.find(m => m.id === fileIdOrName || m.fileName === fileIdOrName) || null;
  }

  listFiles(categoryFilter = null) {
    this.syncExistingFiles();
    // Validate that files still exist on disk
    this.meta = this.meta.filter(m => fs.existsSync(m.filePath));
    this.saveMeta();

    if (categoryFilter) {
      return this.meta.filter(m => m.category === categoryFilter);
    }
    return this.meta;
  }

  deleteFile(fileId) {
    const idx = this.meta.findIndex(m => m.id === fileId || m.fileName === fileId);
    if (idx === -1) return false;

    const item = this.meta[idx];
    try {
      if (fs.existsSync(item.filePath)) {
        fs.unlinkSync(item.filePath);
      }
    } catch (e) {
      console.warn(`[CloudStorage] Could not delete physical file ${item.filePath}:`, e.message);
    }

    this.meta.splice(idx, 1);
    this.saveMeta();

    // Clean up any shares for this file
    for (const shareId in this.shares) {
      if (this.shares[shareId].fileId === item.id) {
        delete this.shares[shareId];
      }
    }
    this.saveShares();

    return true;
  }

  incrementDownloadCount(fileId) {
    const item = this.getFile(fileId);
    if (item) {
      item.downloadsCount = (item.downloadsCount || 0) + 1;
      this.saveMeta();
    }
  }

  /**
   * Generates a time-expiring public share link
   */
  createShareLink(fileId, { expiresInHours = 24, password = null, allowDownload = true } = {}) {
    const file = this.getFile(fileId);
    if (!file) {
      throw new Error('File not found');
    }

    const shareId = crypto.randomBytes(8).toString('hex');
    const expiresAt = expiresInHours > 0 
      ? new Date(Date.now() + expiresInHours * 3600 * 1000).toISOString()
      : null; // null = never expires

    this.shares[shareId] = {
      shareId,
      fileId: file.id,
      fileName: file.fileName,
      originalName: file.originalName,
      fileSize: file.fileSize,
      mimeType: file.mimeType,
      expiresAt,
      password: password ? crypto.createHash('sha256').update(password).digest('hex') : null,
      hasPassword: !!password,
      allowDownload,
      createdAt: new Date().toISOString(),
      viewsCount: 0
    };

    this.saveShares();
    return this.shares[shareId];
  }

  getShare(shareId) {
    const share = this.shares[shareId];
    if (!share) return null;

    if (share.expiresAt && new Date(share.expiresAt).getTime() < Date.now()) {
      delete this.shares[shareId];
      this.saveShares();
      return null;
    }

    const file = this.getFile(share.fileId);
    if (!file || !fs.existsSync(file.filePath)) {
      delete this.shares[shareId];
      this.saveShares();
      return null;
    }

    share.viewsCount = (share.viewsCount || 0) + 1;
    this.saveShares();
    return { ...share, filePath: file.filePath };
  }

  /**
   * Storage Statistics & Breakdown
   */
  getStorageStats() {
    this.syncExistingFiles();
    let totalUsedBytes = 0;
    const categories = {
      video: { count: 0, bytes: 0 },
      audio: { count: 0, bytes: 0 },
      image: { count: 0, bytes: 0 },
      document: { count: 0, bytes: 0 },
      other: { count: 0, bytes: 0 }
    };

    for (const item of this.meta) {
      totalUsedBytes += item.fileSize || 0;
      const mime = item.mimeType || '';
      if (mime.startsWith('video/')) {
        categories.video.count++;
        categories.video.bytes += item.fileSize;
      } else if (mime.startsWith('audio/')) {
        categories.audio.count++;
        categories.audio.bytes += item.fileSize;
      } else if (mime.startsWith('image/')) {
        categories.image.count++;
        categories.image.bytes += item.fileSize;
      } else if (mime.includes('pdf') || mime.includes('text') || mime.includes('document')) {
        categories.document.count++;
        categories.document.bytes += item.fileSize;
      } else {
        categories.other.count++;
        categories.other.bytes += item.fileSize;
      }
    }

    // Server storage space
    const totalAllocatedCap = 100 * 1024 * 1024 * 1024; // 100 GB virtual cap
    return {
      totalFiles: this.meta.length,
      usedBytes: totalUsedBytes,
      usedMb: (totalUsedBytes / (1024 * 1024)).toFixed(1),
      usedGb: (totalUsedBytes / (1024 * 1024 * 1024)).toFixed(2),
      allocatedCapBytes: totalAllocatedCap,
      percentUsed: Math.min(100, (totalUsedBytes / totalAllocatedCap * 100)).toFixed(1),
      categories
    };
  }

  /**
   * Network Interfaces & Server Discovery Information
   */
  getServerNetworkInfo(port = 3000) {
    const nets = os.networkInterfaces();
    const lanIps = [];

    for (const name in nets) {
      for (const net of nets[name]) {
        if (net.family === 'IPv4' && !net.internal) {
          lanIps.push(net.address);
        }
      }
    }

    const primaryIp = lanIps[0] || 'localhost';
    return {
      primaryIp,
      lanIps,
      port,
      lanUrl: `http://${primaryIp}:${port}`,
      localhostUrl: `http://localhost:${port}`,
      androidEmulatorUrl: `http://10.0.2.2:${port}`
    };
  }
}

module.exports = new CloudStorageService();
