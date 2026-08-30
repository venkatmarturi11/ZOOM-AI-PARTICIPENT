const { getDb } = require('./index');
const { v4: uuidv4 } = require('uuid');

class Repository {
  constructor() {
    this.db = getDb();
  }

  // ── Users & Operators ───────────────────────────────────────────────
  getOrCreateUser(telegramUserId, username) {
    if (this.db.isEmbedded) {
      let user = this.db.data.users.find(u => u.telegram_user_id === String(telegramUserId));
      if (!user) {
        user = {
          id: uuidv4(),
          telegram_user_id: String(telegramUserId),
          username: username || '',
          role: 'operator',
          created_at: new Date().toISOString()
        };
        this.db.data.users.push(user);
        this.db.save();
      }
      return user;
    }

    let user = this.db.prepare('SELECT * FROM users WHERE telegram_user_id = ?').get(String(telegramUserId));
    if (!user) {
      const id = uuidv4();
      this.db.prepare('INSERT INTO users (id, telegram_user_id, username, role) VALUES (?, ?, ?, ?)').run(id, String(telegramUserId), username || '', 'operator');
      user = this.db.prepare('SELECT * FROM users WHERE id = ?').get(id);
    }
    return user;
  }

  // ── Zoom Accounts (OAuth / ZAK) ─────────────────────────────────────
  saveZoomAccount({ zoomUserId, displayName, accessToken, refreshToken, expiresAt }) {
    if (this.db.isEmbedded) {
      let acc = this.db.data.zoom_accounts.find(a => a.zoom_user_id === zoomUserId);
      if (acc) {
        acc.display_name = displayName || acc.display_name;
        acc.oauth_access_token = accessToken;
        acc.oauth_refresh_token = refreshToken;
        acc.token_expires_at = expiresAt;
      } else {
        acc = {
          id: uuidv4(),
          zoom_user_id: zoomUserId,
          display_name: displayName,
          oauth_access_token: accessToken,
          oauth_refresh_token: refreshToken,
          token_expires_at: expiresAt,
          created_at: new Date().toISOString()
        };
        this.db.data.zoom_accounts.push(acc);
      }
      this.db.save();
      return acc;
    }

    const existing = this.db.prepare('SELECT * FROM zoom_accounts WHERE zoom_user_id = ?').get(zoomUserId);
    if (existing) {
      this.db.prepare(`
        UPDATE zoom_accounts 
        SET oauth_access_token = ?, oauth_refresh_token = ?, token_expires_at = ?, display_name = ?
        WHERE zoom_user_id = ?
      `).run(accessToken, refreshToken, expiresAt, displayName || existing.display_name, zoomUserId);
      return this.db.prepare('SELECT * FROM zoom_accounts WHERE zoom_user_id = ?').get(zoomUserId);
    } else {
      const id = uuidv4();
      this.db.prepare(`
        INSERT INTO zoom_accounts (id, zoom_user_id, display_name, oauth_access_token, oauth_refresh_token, token_expires_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(id, zoomUserId, displayName, accessToken, refreshToken, expiresAt);
      return this.db.prepare('SELECT * FROM zoom_accounts WHERE id = ?').get(id);
    }
  }

  getActiveZoomAccount() {
    if (this.db.isEmbedded) {
      return this.db.data.zoom_accounts[0] || null;
    }
    return this.db.prepare('SELECT * FROM zoom_accounts ORDER BY created_at DESC LIMIT 1').get() || null;
  }

  // ── Meetings ────────────────────────────────────────────────────────
  createMeeting({ zoomMeetingId, password, joinUrl, topic }) {
    const id = uuidv4();
    if (this.db.isEmbedded) {
      const meeting = {
        id,
        zoom_meeting_id: zoomMeetingId,
        password: password || '',
        join_url: joinUrl,
        topic: topic || `Zoom Meeting ${zoomMeetingId}`,
        status: 'pending',
        created_at: new Date().toISOString()
      };
      this.db.data.meetings.push(meeting);
      this.db.save();
      return meeting;
    }

    this.db.prepare(`
      INSERT INTO meetings (id, zoom_meeting_id, password, join_url, topic, status)
      VALUES (?, ?, ?, ?, ?, 'pending')
    `).run(id, zoomMeetingId, password || '', joinUrl, topic || `Zoom Meeting ${zoomMeetingId}`);
    return this.db.prepare('SELECT * FROM meetings WHERE id = ?').get(id);
  }

  getMeeting(id) {
    if (this.db.isEmbedded) {
      return this.db.data.meetings.find(m => m.id === id) || null;
    }
    return this.db.prepare('SELECT * FROM meetings WHERE id = ?').get(id) || null;
  }

  listMeetings(limit = 20) {
    if (this.db.isEmbedded) {
      return [...this.db.data.meetings].reverse().slice(0, limit);
    }
    return this.db.prepare('SELECT * FROM meetings ORDER BY created_at DESC LIMIT ?').all(limit);
  }

  // ── Recording Jobs ──────────────────────────────────────────────────
  createJob({ meetingId, requestedBy, workerId }) {
    const id = 'REC-' + uuidv4().substring(0, 8).toUpperCase();
    if (this.db.isEmbedded) {
      const job = {
        id,
        meeting_id: meetingId,
        worker_id: workerId || `worker-${Date.now()}`,
        status: 'QUEUED',
        requested_by: String(requestedBy || 'system'),
        started_at: null,
        ended_at: null,
        failure_code: null,
        failure_message: null,
        created_at: new Date().toISOString()
      };
      this.db.data.recording_jobs.push(job);
      this.db.save();
      return job;
    }

    this.db.prepare(`
      INSERT INTO recording_jobs (id, meeting_id, worker_id, status, requested_by)
      VALUES (?, ?, ?, 'QUEUED', ?)
    `).run(id, meetingId, workerId || `worker-${Date.now()}`, String(requestedBy || 'system'));
    return this.db.prepare('SELECT * FROM recording_jobs WHERE id = ?').get(id);
  }

  updateJobStatus(jobId, status, extra = {}) {
    const now = new Date().toISOString();
    if (this.db.isEmbedded) {
      const job = this.db.data.recording_jobs.find(j => j.id === jobId);
      if (job) {
        job.status = status;
        if (status === 'RECORDING' && !job.started_at) job.started_at = now;
        if (status === 'COMPLETED' || status === 'FAILED') job.ended_at = now;
        if (extra.failureCode) job.failure_code = extra.failureCode;
        if (extra.failureMessage) job.failure_message = extra.failureMessage;
        this.db.save();
        return job;
      }
      return null;
    }

    let startedAtUpdate = '';
    let endedAtUpdate = '';
    if (status === 'RECORDING') startedAtUpdate = ', started_at = COALESCE(started_at, CURRENT_TIMESTAMP)';
    if (status === 'COMPLETED' || status === 'FAILED') endedAtUpdate = ', ended_at = CURRENT_TIMESTAMP';

    this.db.prepare(`
      UPDATE recording_jobs
      SET status = ?, failure_code = ?, failure_message = ? ${startedAtUpdate} ${endedAtUpdate}
      WHERE id = ?
    `).run(status, extra.failureCode || null, extra.failureMessage || null, jobId);
    return this.db.prepare('SELECT * FROM recording_jobs WHERE id = ?').get(jobId);
  }

  getJob(id) {
    if (this.db.isEmbedded) {
      const job = this.db.data.recording_jobs.find(j => j.id === id);
      if (!job) return null;
      const meeting = this.db.data.meetings.find(m => m.id === job.meeting_id);
      const recording = this.db.data.recordings.find(r => r.recording_job_id === job.id);
      return { ...job, meeting, recording };
    }

    const job = this.db.prepare(`
      SELECT j.*, m.zoom_meeting_id, m.topic, m.join_url, r.file_path, r.duration_seconds, r.size_bytes
      FROM recording_jobs j
      LEFT JOIN meetings m ON j.meeting_id = m.id
      LEFT JOIN recordings r ON r.recording_job_id = j.id
      WHERE j.id = ?
    `).get(id);
    return job || null;
  }

  getActiveJobs() {
    if (this.db.isEmbedded) {
      return this.db.data.recording_jobs.filter(j => 
        ['QUEUED', 'AUTHENTICATING', 'AUTHORIZED', 'JOINING', 'WAITING_FOR_HOST', 'RECORDING', 'STOPPING', 'FINALIZING'].includes(j.status)
      );
    }
    return this.db.prepare(`
      SELECT j.*, m.zoom_meeting_id, m.topic
      FROM recording_jobs j
      LEFT JOIN meetings m ON j.meeting_id = m.id
      WHERE j.status IN ('QUEUED', 'AUTHENTICATING', 'AUTHORIZED', 'JOINING', 'WAITING_FOR_HOST', 'RECORDING', 'STOPPING', 'FINALIZING')
      ORDER BY j.created_at DESC
    `).all();
  }

  // ── Recordings ──────────────────────────────────────────────────────
  saveRecording({ jobId, filePath, objectKey, durationSeconds, sizeBytes, codec }) {
    const id = uuidv4();
    if (this.db.isEmbedded) {
      const rec = {
        id,
        recording_job_id: jobId,
        file_path: filePath,
        object_key: objectKey || '',
        duration_seconds: durationSeconds || 0,
        size_bytes: sizeBytes || 0,
        codec: codec || 'h264/aac',
        created_at: new Date().toISOString()
      };
      this.db.data.recordings.push(rec);
      this.db.save();
      return rec;
    }

    this.db.prepare(`
      INSERT INTO recordings (id, recording_job_id, file_path, object_key, duration_seconds, size_bytes, codec)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(id, jobId, filePath, objectKey || '', durationSeconds || 0, sizeBytes || 0, codec || 'h264/aac');
    return this.db.prepare('SELECT * FROM recordings WHERE id = ?').get(id);
  }

  listRecordings(limit = 20) {
    if (this.db.isEmbedded) {
      return [...this.db.data.recordings].reverse().slice(0, limit);
    }
    return this.db.prepare(`
      SELECT r.*, j.status as job_status, m.zoom_meeting_id, m.topic
      FROM recordings r
      LEFT JOIN recording_jobs j ON r.recording_job_id = j.id
      LEFT JOIN meetings m ON j.meeting_id = m.id
      ORDER BY r.created_at DESC
      LIMIT ?
    `).all(limit);
  }
}

module.exports = new Repository();
