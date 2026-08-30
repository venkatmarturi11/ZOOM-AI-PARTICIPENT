const { EventEmitter } = require('events');
const repo = require('../db/repository');
const path = require('path');
const RtmsRecorderWorker = require(path.join(__dirname, '../../../workers/rtms-recorder/index'));

class RecordingQueue extends EventEmitter {
  constructor() {
    super();
    this.activeWorkers = new Map(); // jobId -> worker
    this.isRedisAvailable = false;
    this.bullQueue = null;
    this.bullWorker = null;

    this.initBullMQ();
  }

  async initBullMQ() {
    const redisUrl = process.env.REDIS_URL;
    if (!redisUrl) {
      console.log('[Queue] Running with in-memory / database queue engine (Redis not configured in .env)');
      return;
    }

    try {
      const { Queue, Worker } = require('bullmq');
      const IORedis = require('ioredis');

      const connection = new IORedis(redisUrl, { maxRetriesPerRequest: null });
      connection.on('connect', () => {
        console.log('✅ Connected to Redis cluster for BullMQ');
        this.isRedisAvailable = true;
      });

      connection.on('error', (err) => {
        console.warn('⚠️ Redis connection notice:', err.message);
        this.isRedisAvailable = false;
      });

      this.bullQueue = new Queue('zoom-recordings', { connection });

      this.bullWorker = new Worker('zoom-recordings', async (job) => {
        return this.processRecordingJob(job.data);
      }, { connection, concurrency: 5 });

      this.bullWorker.on('completed', (job) => {
        console.log(`[BullMQ] Job ${job.id} completed successfully`);
      });

      this.bullWorker.on('failed', (job, err) => {
        console.error(`[BullMQ] Job ${job.id} failed:`, err.message);
      });
    } catch (err) {
      console.warn('[Queue] BullMQ initialization note (falling back to database queue):', err.message);
      this.isRedisAvailable = false;
    }
  }

  /**
   * Enqueues a new recording job
   */
  async addJob({ meeting, requestedBy, options = {} }) {
    const jobRecord = repo.createJob({
      meetingId: meeting.id,
      requestedBy: requestedBy || 'api-client',
      workerId: `worker-${Date.now()}`
    });

    if (this.isRedisAvailable && this.bullQueue) {
      await this.bullQueue.add(`rec-${jobRecord.id}`, {
        jobId: jobRecord.id,
        meeting,
        options
      }, {
        jobId: jobRecord.id,
        removeOnComplete: false,
        removeOnFail: false
      });
      console.log(`[Queue] Job ${jobRecord.id} enqueued via BullMQ`);
    } else {
      // Direct worker execution
      this.processRecordingJob({ jobId: jobRecord.id, meeting, options }).catch(err => {
        console.error(`[Queue] Local worker execution error on job ${jobRecord.id}:`, err);
      });
    }

    return jobRecord;
  }

  /**
   * Spawns the dedicated RTMS worker for the job
   */
  async processRecordingJob({ jobId, meeting, options }) {
    const job = repo.getJob(jobId) || { id: jobId, meeting_id: meeting.id };
    const worker = new RtmsRecorderWorker(job, { meeting, ...options });
    this.activeWorkers.set(jobId, worker);

    worker.on('state', (stateData) => {
      this.emit('jobState', { ...stateData, meeting });
    });

    worker.on('tick', (tickData) => {
      this.emit('jobTick', { ...tickData, meeting });
    });

    await worker.start();
    return { jobId, status: worker.status };
  }

  /**
   * Signals an active worker to stop and finalize
   */
  async stopJob(jobId) {
    const worker = this.activeWorkers.get(jobId);
    if (!worker) {
      const job = repo.getJob(jobId);
      if (!job) throw new Error(`Job ${jobId} not found`);
      if (['COMPLETED', 'FAILED'].includes(job.status)) return job;
      return repo.updateJobStatus(jobId, 'COMPLETED');
    }

    await worker.stop();
    this.activeWorkers.delete(jobId);
    return repo.getJob(jobId);
  }

  getActiveJob(jobId) {
    return this.activeWorkers.get(jobId) || null;
  }

  listActiveJobs() {
    return repo.getActiveJobs();
  }
}

module.exports = new RecordingQueue();
