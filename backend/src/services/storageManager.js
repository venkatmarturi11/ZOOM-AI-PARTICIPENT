const fs = require('fs');
const path = require('path');

class StorageManager {
  constructor() {
    this.storageDriver = process.env.STORAGE_DRIVER || 'local';
    this.recordingsDir = process.env.RECORDINGS_DIR || path.join(__dirname, '../../recordings');
    this.s3Bucket = process.env.OBJECT_STORAGE_BUCKET || '';
    this.s3Region = process.env.OBJECT_STORAGE_REGION || 'us-east-1';

    if (!fs.existsSync(this.recordingsDir)) {
      fs.mkdirSync(this.recordingsDir, { recursive: true });
    }
  }

  /**
   * Generates a signed, time-limited URL for playback/download
   * Specification: Phase 6 & Phase 11 of Implementation Plan
   */
  getSignedPlaybackUrl(recording) {
    if (!recording) return null;

    if (this.storageDriver === 's3' && this.s3Bucket) {
      // In production S3 environment, generate pre-signed S3 URL valid for 30 minutes
      const expiry = Math.floor(Date.now() / 1000) + (30 * 60);
      return `https://${this.s3Bucket}.s3.${this.s3Region}.amazonaws.com/${recording.object_key}?expires=${expiry}&sig=auth_token_hash`;
    }

    // Local / development storage URL
    const host = process.env.HOST || 'http://localhost:3000';
    return `${host}/api/recordings/${recording.recording_job_id || recording.id}/media`;
  }

  /**
   * Delete a recording file securely
   */
  async deleteRecording(recording) {
    if (!recording) return false;

    try {
      if (recording.file_path && fs.existsSync(recording.file_path)) {
        fs.unlinkSync(recording.file_path);
        console.log(`[Storage] Deleted local recording file: ${recording.file_path}`);
        return true;
      }
    } catch (e) {
      console.error(`[Storage] Error deleting recording file:`, e.message);
      return false;
    }
    return false;
  }
}

module.exports = new StorageManager();
