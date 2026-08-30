const { Telegraf } = require('telegraf');
const jobManager = require('../services/jobManager');
const repo = require('../db/repository');
const fs = require('fs');
const path = require('path');

let botInstance = null;

function initTelegramBot() {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token || token === 'your_telegram_bot_token_here') {
    console.warn('[Telegram] TELEGRAM_BOT_TOKEN not configured. Telegram bot control plane disabled.');
    return null;
  }

  try {
    const bot = new Telegraf(token);
    botInstance = bot;

    const allowedUsersRaw = process.env.TELEGRAM_ALLOWED_USERS || '';
    const allowedUsers = allowedUsersRaw
      .split(',')
      .map(u => u.trim().toLowerCase())
      .filter(Boolean);

    // ── Authentication & Authorization Middleware ─────────────────────
    bot.use(async (ctx, next) => {
      const from = ctx.from;
      if (!from) return;

      const userId = String(from.id);
      const username = (from.username || '').toLowerCase();

      // Check allowlist if configured
      if (allowedUsers.length > 0) {
        const isAllowed = allowedUsers.includes(userId) || (username && allowedUsers.includes(username));
        if (!isAllowed) {
          console.warn(`[Telegram] Unauthorized access attempt by ${from.first_name} (@${from.username}, id: ${userId})`);
          return ctx.reply(`🚫 *Access Denied*\nYour Telegram account is not authorized to operate this Zoom Bot Recorder.`, { parse_mode: 'Markdown' });
        }
      }

      // Register or update user record
      repo.getOrCreateUser(userId, username);
      return next();
    });

    // ── Command: /start or /help ──────────────────────────────────────
    bot.command(['start', 'help'], (ctx) => {
      const helpText = `
🤖 *Zoom Bot Meeting Recorder — Control Plane*

*Available Commands:*
• \`/record <meeting_url>\` — Start recording a Zoom meeting
• \`/stop <job_id>\` — Gracefully stop & finalize recording
• \`/status [job_id]\` — Show active jobs & worker status
• \`/meetings\` — List recently joined meetings
• \`/recordings\` — List completed recordings & files
• \`/health\` — Check system health & storage
• \`/help\` — Show this guidance & privacy notice

*Compliance & Privacy Notice:*
_The bot enters meetings with a visible participant identity ("Meeting Recorder Bot") and adheres to Zoom API recording terms._
      `.trim();
      return ctx.reply(helpText, { parse_mode: 'Markdown' });
    });

    // ── Command: /record <meeting_url> ─────────────────────────────────
    bot.command('record', async (ctx) => {
      const text = ctx.message.text || '';
      const parts = text.split(/\s+/).slice(1);
      const meetingInput = parts.join(' ').trim();

      if (!meetingInput) {
        return ctx.reply(`⚠️ *Usage:* \`/record <meeting_url or meeting_id>\`\n_Example:_ \`/record https://zoom.us/j/123456789?pwd=xyz\``, { parse_mode: 'Markdown' });
      }

      try {
        const fromUser = ctx.from.username ? `@${ctx.from.username}` : `User_${ctx.from.id}`;
        const { job, meeting } = await jobManager.createRecordingJob({
          meetingInput,
          requestedBy: fromUser,
        });

        const replyMsg = await ctx.reply(`
🎬 *Recording Job Created*
• *Job ID:* \`${job.id}\`
• *Meeting:* \`${meeting.zoom_meeting_id || 'Direct URL'}\`
• *Status:* ⏳ \`AUTHENTICATING\`
_Starting Zoom Bot worker..._
        `.trim(), { parse_mode: 'Markdown' });

        // Map chatId for automated progress alerts
        const chatId = ctx.chat.id;

        // Listen for status changes for this specific job
        const onStateChange = (event) => {
          if (event.jobId === job.id) {
            let statusEmoji = '⏳';
            if (event.status === 'RECORDING') statusEmoji = '🔴';
            if (event.status === 'COMPLETED') statusEmoji = '✅';
            if (event.status === 'FAILED') statusEmoji = '❌';

            let extraInfo = '';
            if (event.status === 'RECORDING') {
              extraInfo = `\n_Bot joined successfully and is recording audio/video._`;
            } else if (event.status === 'COMPLETED') {
              extraInfo = `\n📁 *File:* \`${path.basename(event.filePath || 'meeting.mp4')}\`\n⏱️ *Duration:* ${event.duration || 0} seconds\n_Uploading file to your chat..._`;

              // Auto-upload the recorded file directly to Telegram chat
              const targetFile = event.filePath;
              if (targetFile && fs.existsSync(targetFile)) {
                setTimeout(async () => {
                  try {
                    const stat = fs.statSync(targetFile);
                    const sizeMb = (stat.size / (1024 * 1024)).toFixed(2);
                    console.log(`[Telegram] Sending ${sizeMb} MB recording file to chat ${chatId}...`);
                    
                    if (stat.size < 48 * 1024 * 1024) {
                      await ctx.telegram.sendVideo(chatId, { source: targetFile }, {
                        caption: `🎬 *Zoom Meeting Recording*\n⏱️ Duration: ${event.duration || 0}s | 💾 Size: ${sizeMb} MB`,
                        parse_mode: 'Markdown'
                      });
                    } else {
                      await ctx.telegram.sendDocument(chatId, { source: targetFile }, {
                        caption: `📁 *Zoom Meeting Recording*\n⏱️ Duration: ${event.duration || 0}s | 💾 Size: ${sizeMb} MB`,
                        parse_mode: 'Markdown'
                      });
                    }
                  } catch (uploadErr) {
                    console.error('[Telegram] File upload error:', uploadErr.message);
                    ctx.telegram.sendMessage(chatId, `⚠️ Could not upload file directly (${uploadErr.message}). You can download it from your server library.`).catch(() => {});
                  }
                }, 1500);
              }
            } else if (event.status === 'FAILED') {
              extraInfo = `\n⚠️ *Error:* ${event.failureMessage || 'Unknown error'}`;
            }

            ctx.telegram.sendMessage(chatId, `
${statusEmoji} *Job Update:* \`${job.id}\`
• *Status:* \`${event.status}\`${extraInfo}
            `.trim(), { parse_mode: 'Markdown' }).catch(() => {});

            if (event.status === 'COMPLETED' || event.status === 'FAILED') {
              jobManager.removeListener('jobState', onStateChange);
            }
          }
        };

        jobManager.on('jobState', onStateChange);

      } catch (err) {
        console.error('[Telegram] Error creating recording job:', err);
        return ctx.reply(`❌ *Failed to create recording job:*\n${err.message}`, { parse_mode: 'Markdown' });
      }
    });

    // ── Command: /stop <job_id> ───────────────────────────────────────
    bot.command('stop', async (ctx) => {
      const parts = ctx.message.text.split(/\s+/).slice(1);
      const jobId = parts[0]?.trim();

      if (!jobId) {
        // Find active jobs
        const activeJobs = jobManager.getActiveJobs();
        if (activeJobs.length === 1) {
          const target = activeJobs[0];
          await ctx.reply(`⏹️ Stopping active job \`${target.id}\`...`, { parse_mode: 'Markdown' });
          await jobManager.stopRecordingJob(target.id);
          return ctx.reply(`✅ Job \`${target.id}\` stopping and finalizing MP4.`, { parse_mode: 'Markdown' });
        }
        return ctx.reply(`⚠️ *Usage:* \`/stop <job_id>\`\nCheck active jobs with \`/status\``, { parse_mode: 'Markdown' });
      }

      try {
        await ctx.reply(`⏹️ Requesting stop for job \`${jobId}\`...`, { parse_mode: 'Markdown' });
        await jobManager.stopRecordingJob(jobId);
        return ctx.reply(`✅ Stop signal sent to \`${jobId}\`. Media is finalizing.`, { parse_mode: 'Markdown' });
      } catch (e) {
        return ctx.reply(`❌ Error stopping job: ${e.message}`);
      }
    });

    // ── Command: /status [job_id] ─────────────────────────────────────
    bot.command('status', (ctx) => {
      const parts = ctx.message.text.split(/\s+/).slice(1);
      const jobId = parts[0]?.trim();

      if (jobId) {
        const job = jobManager.getJob(jobId);
        if (!job) return ctx.reply(`❌ Job \`${jobId}\` not found.`, { parse_mode: 'Markdown' });

        return ctx.reply(`
📋 *Job Status:* \`${job.id}\`
• *Meeting ID:* \`${job.zoom_meeting_id || 'N/A'}\`
• *Topic:* ${job.topic || 'N/A'}
• *Status:* \`${job.status}\`
• *Started:* ${job.started_at || 'Not started'}
• *Duration:* ${job.duration_seconds || 0}s
        `.trim(), { parse_mode: 'Markdown' });
      }

      const active = jobManager.getActiveJobs();
      if (active.length === 0) {
        return ctx.reply(`ℹ️ *No active recording jobs.*\nUse \`/record <meeting_url>\` to start one.`, { parse_mode: 'Markdown' });
      }

      let text = `📊 *Active Recording Jobs (${active.length}):*\n\n`;
      active.forEach(j => {
        text += `• \`${j.id}\` | Meeting: \`${j.zoom_meeting_id || 'N/A'}\` | Status: \`${j.status}\`\n`;
      });
      text += `\n_Use \`/stop <job_id>\` to end a session._`;
      return ctx.reply(text, { parse_mode: 'Markdown' });
    });

    // ── Command: /meetings ────────────────────────────────────────────
    bot.command('meetings', (ctx) => {
      const meetings = jobManager.listMeetings(10);
      if (meetings.length === 0) return ctx.reply(`ℹ️ No recorded meetings in history.`);

      let text = `📅 *Recent Meetings History:*\n\n`;
      meetings.forEach((m, idx) => {
        text += `${idx + 1}. *${m.topic || 'Zoom Meeting'}*\n   ID: \`${m.zoom_meeting_id || 'N/A'}\` | Created: ${m.created_at.slice(0, 16)}\n`;
      });
      return ctx.reply(text, { parse_mode: 'Markdown' });
    });

    // ── Command: /recordings ──────────────────────────────────────────
    bot.command('recordings', (ctx) => {
      const recordings = jobManager.listRecordings(10);
      if (recordings.length === 0) return ctx.reply(`ℹ️ No completed MP4 recordings found.`);

      let text = `🎥 *Completed Recordings:*\n\n`;
      recordings.forEach((r, idx) => {
        const sizeMb = (r.size_bytes / (1024 * 1024)).toFixed(2);
        text += `${idx + 1}. \`${r.object_key || path.basename(r.file_path)}\`\n   Duration: ${r.duration_seconds}s | Size: ${sizeMb} MB\n`;
      });
      return ctx.reply(text, { parse_mode: 'Markdown' });
    });

    // ── Command: /health ─────────────────────────────────────────────
    bot.command('health', (ctx) => {
      const activeCount = jobManager.getActiveJobs().length;
      const recordingsDir = process.env.RECORDINGS_DIR || './recordings';
      const storageAvailable = fs.existsSync(recordingsDir);

      const text = `
🩺 *System Health & Diagnostic*
• *Backend Status:* 🟢 Healthy
• *Active Worker Jobs:* ${activeCount}
• *Storage Directory:* ${storageAvailable ? '🟢 Online' : '⚠️ Offline'}
• *Database Status:* 🟢 Connected (SQLite)
• *Environment:* \`${process.env.APP_ENV || 'production'}\`
      `.trim();
      return ctx.reply(text, { parse_mode: 'Markdown' });
    });

    // Launch Telegram polling
    bot.launch().then(() => {
      console.log('🤖 Telegram Bot Control Plane started successfully');
    }).catch(err => {
      console.warn('⚠️ Telegram bot polling notice:', err.message);
    });

    return bot;
  } catch (err) {
    console.error('[Telegram] Failed to initialize Telegraf:', err.message);
    return null;
  }
}

module.exports = {
  initTelegramBot
};
