const ffmpeg = require('fluent-ffmpeg');
const ffmpegStatic = require('ffmpeg-static');
const fs = require('fs');
const path = require('path');

// Ensure fluent-ffmpeg uses ffmpeg-static binary
if (ffmpegStatic && fs.existsSync(ffmpegStatic)) {
  ffmpeg.setFfmpegPath(ffmpegStatic);
}

function getDurationSeconds(filePath) {
  return new Promise((resolve) => {
    ffmpeg.ffprobe(filePath, (err, metadata) => {
      if (err || !metadata || !metadata.format || !metadata.format.duration) {
        return resolve(null);
      }
      const dur = parseFloat(metadata.format.duration);
      resolve(isNaN(dur) || dur <= 0 ? null : dur);
    });
  });
}

/**
 * Checks video size and compresses if it exceeds target limit (e.g. Telegram 50MB).
 * Uses duration-based bitrate targeting so recordings land reliably under cap.
 * @param {string} filePath - Path to original MP4 video
 * @param {number} maxSizeBytes - Maximum allowed size (default 45MB safety margin for 50MB cap)
 * @returns {Promise<string>} - Resolves with path to final (compressed or original) MP4 file
 */
async function ensureVideoUnderLimit(filePath, maxSizeBytes = 45 * 1024 * 1024) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`File not found: ${filePath}`);
  }

  const stats = fs.statSync(filePath);
  if (stats.size <= maxSizeBytes) {
    return filePath;
  }

  const fileSizeMb = (stats.size / 1024 / 1024).toFixed(2);
  console.log(`[Compressor] Input video size (${fileSizeMb}MB) exceeds target limit (${(maxSizeBytes / 1024 / 1024).toFixed(2)}MB). Compressing...`);

  const outputDir = path.dirname(filePath);
  const ext = path.extname(filePath);
  const baseName = path.basename(filePath, ext);
  const compressedPath = path.join(outputDir, `${baseName}-compressed${ext}`);

  const durationSec = await getDurationSeconds(filePath);
  let outputOptions = [];

  if (durationSec && durationSec > 0) {
    const totalTargetBits = maxSizeBytes * 8;
    const totalTargetBps = totalTargetBits / durationSec;
    const audioBps = 96000;
    let videoBps = totalTargetBps - audioBps;
    if (videoBps < 150000) videoBps = 150000;

    const videoKbps = Math.floor(videoBps / 1000);
    const maxKbps = Math.floor(videoKbps * 1.25);
    const bufKbps = Math.floor(videoKbps * 2);

    console.log(`[Compressor] Duration: ${durationSec.toFixed(1)}s -> Targeting Video Bitrate: ${videoKbps}kbps`);

    outputOptions = [
      '-c:v', 'libx264',
      '-b:v', `${videoKbps}k`,
      '-maxrate', `${maxKbps}k`,
      '-bufsize', `${bufKbps}k`,
      '-preset', 'fast',
      '-c:a', 'aac',
      '-b:a', '96k'
    ];
  } else {
    outputOptions = [
      '-c:v', 'libx264',
      '-crf', '28',
      '-preset', 'fast',
      '-c:a', 'aac',
      '-b:a', '128k'
    ];
  }

  return new Promise((resolve) => {
    ffmpeg(filePath)
      .outputOptions(outputOptions)
      .on('end', () => {
        const compressedStats = fs.statSync(compressedPath);
        console.log(`[Compressor] Compression complete: ${compressedPath} (${(compressedStats.size / 1024 / 1024).toFixed(2)}MB)`);
        resolve(compressedPath);
      })
      .on('error', (err) => {
        console.error('[Compressor] Compression warning:', err.message || err);
        resolve(filePath);
      })
      .save(compressedPath);
  });
}

module.exports = { ensureVideoUnderLimit };
