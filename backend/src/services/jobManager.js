const { EventEmitter } = require('events');
const repo = require('../db/repository');
const zoomAuth = require('./zoomAuth');
const recordingQueue = require('../queue/recordingQueue');

class JobManager extends EventEmitter {
  constructor() {
    super();

    // Forward queue events
    recordingQueue.on('jobState', (data) => this.emit('jobState', data));
    recordingQueue.on('jobTick', (data) => this.emit('jobTick', data));
  }

  /**
   * Creates and dispatches a new recording job from a meeting URL or meeting number
   */
  async createRecordingJob({ meetingInput, password, topic, requestedBy }) {
    // 1. Parse meeting input
    const parsed = zoomAuth.parseMeetingInput(meetingInput);
    const effectivePassword = password || parsed.password;

    // 2. Create meeting record in DB
    const meeting = repo.createMeeting({
      zoomMeetingId: parsed.meetingNumber,
      password: effectivePassword,
      joinUrl: parsed.joinUrl || meetingInput,
      topic: topic || (parsed.meetingNumber ? `Meeting ${parsed.meetingNumber}` : 'Zoom Session')
    });

    // 3. Dispatch to BullMQ queue
    const job = await recordingQueue.addJob({
      meeting,
      requestedBy: requestedBy || 'system',
    });

    return { job, meeting };
  }

  /**
   * Stop an active recording job
   */
  async stopRecordingJob(jobId) {
    return recordingQueue.stopJob(jobId);
  }

  getJob(jobId) {
    return repo.getJob(jobId);
  }

  getActiveJobs() {
    return recordingQueue.listActiveJobs();
  }

  listRecordings(limit = 20) {
    return repo.listRecordings(limit);
  }

  listMeetings(limit = 20) {
    return repo.listMeetings(limit);
  }
}

module.exports = new JobManager();
