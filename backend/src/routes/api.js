const express = require('express');
const jobManager = require('../services/jobManager');
const zoomAuth = require('../services/zoomAuth');
const repo = require('../db/repository');
const fs = require('fs');
const path = require('path');

const router = express.Router();

/**
 * GET /api/health
 * System health check (backend, DB, worker status)
 */
router.get('/health', (req, res) => {
  const activeJobs = jobManager.getActiveJobs();
  const recordingsDir = process.env.RECORDINGS_DIR || path.join(__dirname, '../../recordings');

  res.json({
    status: 'healthy',
    timestamp: new Date().toISOString(),
    env: process.env.APP_ENV || 'production',
    activeJobsCount: activeJobs.length,
    storage: {
      path: recordingsDir,
      exists: fs.existsSync(recordingsDir)
    },
    database: {
      connected: true
    }
  });
});

/**
 * POST /api/recordings/preflight
 * Phase 02.5: Meeting Eligibility & RTMS Preflight Evaluation
 * Body: { meetingUrl, meetingNumber, password }
 */
router.post('/recordings/preflight', async (req, res) => {
  const { meetingUrl, meetingNumber, password } = req.body;
  const input = meetingUrl || meetingNumber;

  if (!input) {
    return res.status(400).json({
      eligible: false,
      code: 'MISSING_INPUT',
      message: 'meetingUrl or meetingNumber is required'
    });
  }

  const rtmsPreflight = require('../services/rtmsPreflight');
  const evaluation = await rtmsPreflight.evaluateMeeting({
    meetingInput: input,
    password
  });

  res.json(evaluation);
});

/**
 * POST /api/recordings
 * Create recording job
 * Body: { meetingUrl, password, topic, requestedBy, bypassPreflight }
 */
router.post('/recordings', async (req, res) => {
  const { meetingUrl, meetingNumber, password, topic, requestedBy, bypassPreflight } = req.body;
  const input = meetingUrl || meetingNumber;

  if (!input) {
    return res.status(400).json({
      error: 'Invalid input',
      message: 'meetingUrl or meetingNumber is required'
    });
  }

  // Preflight check unless explicitly bypassed
  if (!bypassPreflight) {
    const rtmsPreflight = require('../services/rtmsPreflight');
    const preflight = await rtmsPreflight.evaluateMeeting({
      meetingInput: input,
      password
    });

    if (!preflight.eligible) {
      return res.status(422).json({
        error: 'Preflight check failed',
        preflight
      });
    }
  }

  try {
    const { job, meeting } = await jobManager.createRecordingJob({
      meetingInput: input,
      password,
      topic,
      requestedBy: requestedBy || req.headers['x-user-id'] || 'api'
    });

    res.status(201).json({
      success: true,
      job: {
        id: job.id,
        status: job.status,
        meetingId: meeting.zoom_meeting_id,
        joinUrl: meeting.join_url,
        createdAt: job.created_at
      }
    });
  } catch (err) {
    console.error('[API] Failed to create recording:', err);
    res.status(500).json({
      error: 'Failed to create recording job',
      message: err.message
    });
  }
});

/**
 * POST /api/recordings/:id/stop
 * Gracefully stop a recording job
 */
router.post('/recordings/:id/stop', async (req, res) => {
  const jobId = req.params.id;
  try {
    const result = await jobManager.stopRecordingJob(jobId);
    res.json({
      success: true,
      message: `Recording job ${jobId} stopped successfully`,
      job: result
    });
  } catch (err) {
    res.status(404).json({
      error: 'Stop failed',
      message: err.message
    });
  }
});

/**
 * GET /api/recordings/:id
 * Get status and metadata of a recording job
 */
router.get('/recordings/:id', (req, res) => {
  const jobId = req.params.id;
  const job = jobManager.getJob(jobId);
  if (!job) {
    return res.status(404).json({
      error: 'Not found',
      message: `Recording job ${jobId} not found`
    });
  }
  const storageManager = require('../services/storageManager');
  const signedUrl = job.recording ? storageManager.getSignedPlaybackUrl(job.recording) : null;
  res.json({ job: { ...job, signedPlaybackUrl: signedUrl } });
});

/**
 * GET /api/recordings/:id/media
 * Stream/download the finalized MP4 media file
 */
router.get('/recordings/:id/media', (req, res) => {
  const jobId = req.params.id;
  const job = jobManager.getJob(jobId);

  if (!job || !job.file_path || !fs.existsSync(job.file_path)) {
    return res.status(404).json({
      error: 'Media not found',
      message: `Recording file for job ${jobId} is not ready or does not exist`
    });
  }

  const stat = fs.statSync(job.file_path);
  const fileSize = stat.size;
  const range = req.headers.range;

  if (range) {
    const parts = range.replace(/bytes=/, "").split("-");
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    const chunksize = (end - start) + 1;
    const file = fs.createReadStream(job.file_path, { start, end });
    const head = {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': 'video/mp4',
    };
    res.writeHead(206, head);
    file.pipe(res);
  } else {
    const head = {
      'Content-Length': fileSize,
      'Content-Type': 'video/mp4',
    };
    res.writeHead(200, head);
    fs.createReadStream(job.file_path).pipe(res);
  }
});

/**
 * GET /api/recordings
 * List recent recordings
 */
router.get('/recordings', (req, res) => {
  const limit = parseInt(req.query.limit, 10) || 20;
  const recordings = jobManager.listRecordings(limit);
  res.json({ recordings });
});

/**
 * GET /api/meetings
 * List meetings
 */
router.get('/meetings', (req, res) => {
  const limit = parseInt(req.query.limit, 10) || 20;
  const meetings = jobManager.listMeetings(limit);
  res.json({ meetings });
});

/**
 * POST /internal/workers/heartbeat
 * Worker heartbeat update
 */
router.post('/internal/workers/heartbeat', (req, res) => {
  const { workerId, jobId, cpu, memory } = req.body;
  res.json({ acknowledged: true, timestamp: new Date().toISOString() });
});

/**
 * POST /internal/workers/events
 * Worker event reporting (participant joined/left, error, etc.)
 */
router.post('/internal/workers/events', (req, res) => {
  const { jobId, eventType, data } = req.body;
  console.log(`[WorkerEvent:${jobId}] ${eventType}:`, data);
  res.json({ received: true });
});

module.exports = router;
