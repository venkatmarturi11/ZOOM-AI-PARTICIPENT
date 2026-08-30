const path = require('path');
const fs = require('fs');
const { EventEmitter } = require('events');
const ZoomRtmsClient = require('../../packages/zoom/rtmsClient');
const ffmpegFinalizer = require('../../packages/media/ffmpegFinalizer');
const repo = require('../../backend/src/db/repository');

const recordingsDir = process.env.RECORDINGS_DIR || path.join(__dirname, '../../recordings');
if (!fs.existsSync(recordingsDir)) {
  fs.mkdirSync(recordingsDir, { recursive: true });
}

/**
 * Dedicated RTMS Media Worker
 * Specification: Phases 4, 5, 6, 8, 9 of Implementation Plan PDF
 */
class RtmsRecorderWorker extends EventEmitter {
  constructor(job, options = {}) {
    super();
    this.job = job;
    this.meeting = options.meeting || {};
    this.status = 'CREATED';
    this.rtmsClient = null;
    this.startTime = null;
    this.durationSeconds = 0;
    this.isStopping = false;
    this.outputPath = null;
  }

  updateState(status, extra = {}) {
    this.status = status;
    repo.updateJobStatus(this.job.id, status, extra);
    this.emit('state', {
      jobId: this.job.id,
      status,
      duration: this.durationSeconds,
      ...extra
    });
    console.log(`[RTMSWorker:${this.job.id}] State transition -> ${status}`);
  }

  async start() {
    try {
      this.updateState('STARTING');
      await new Promise(r => setTimeout(r, 400));

      this.updateState('CONNECTING');

      this.rtmsClient = new ZoomRtmsClient({
        meetingId: this.meeting.zoom_meeting_id,
        passcode: this.meeting.password,
      });

      this.rtmsClient.on('status', (s) => {
        if (s === 'CONNECTED' && this.status !== 'RECORDING') {
          this.updateState('CONNECTED');
          setTimeout(() => {
            this.startTime = Date.now();
            this.updateState('RECORDING');
            this.startTimer();
          }, 300);
        }
      });

      await this.rtmsClient.connect();

      const timestamp = Date.now();
      const filename = `meeting_${this.meeting.zoom_meeting_id || 'rtms'}_${this.job.id}_${timestamp}.mp4`;
      this.outputPath = path.join(recordingsDir, filename);

    } catch (err) {
      console.error(`[RTMSWorker:${this.job.id}] Failed to start:`, err);
      this.updateState('FAILED', {
        failureCode: 'CONNECT_FAILED',
        failureMessage: err.message
      });
    }
  }

  startTimer() {
    this.timerInterval = setInterval(() => {
      if (this.status === 'RECORDING') {
        this.durationSeconds = Math.floor((Date.now() - this.startTime) / 1000);
        this.emit('tick', {
          jobId: this.job.id,
          duration: this.durationSeconds
        });
      }
    }, 1000);
  }

  async stop() {
    if (this.isStopping || this.status === 'COMPLETED' || this.status === 'FAILED') return;
    this.isStopping = true;

    this.updateState('STOPPING');
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
      this.timerInterval = null;
    }

    if (this.rtmsClient) {
      await this.rtmsClient.disconnect();
    }

    try {
      this.updateState('FINALIZING');
      console.log(`[RTMSWorker:${this.job.id}] Starting FFmpeg finalization pipeline...`);

      const result = await ffmpegFinalizer.finalizeRecording({
        outputPath: this.outputPath,
        durationSeconds: this.durationSeconds
      });

      this.updateState('UPLOADING');
      await new Promise(r => setTimeout(r, 400));

      // Save finalized recording metadata in DB
      repo.saveRecording({
        jobId: this.job.id,
        filePath: this.outputPath,
        objectKey: path.basename(this.outputPath),
        durationSeconds: this.durationSeconds,
        sizeBytes: result.sizeBytes,
        codec: 'h264/aac'
      });

      this.updateState('COMPLETED', {
        filePath: this.outputPath,
        duration: this.durationSeconds,
        sizeBytes: result.sizeBytes,
        checks: result.checks
      });

      console.log(`[RTMSWorker:${this.job.id}] Recording completed successfully: ${this.outputPath} (${this.durationSeconds}s)`);
    } catch (err) {
      console.error(`[RTMSWorker:${this.job.id}] Finalization error:`, err);
      this.updateState('FAILED', {
        failureCode: 'MEDIA_FAILED',
        failureMessage: err.message
      });
    }
  }
}

module.exports = RtmsRecorderWorker;
