/**
 * Telegram Bot Service (telegramService.js)
 * Enables Telegram users to record Zoom meetings by sending `/record <link>`, raw Meeting IDs, or pasting Zoom URLs.
 * Features interactive inline buttons, real-time progress notifications, and auto-chunked MP4 video delivery.
 */

const TelegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

let botInstance = null;

/**
 * Safely escape HTML characters for Telegram HTML parse_mode
 */
function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/**
 * Initialize Telegram Bot Service
 * @param {Object} config - { token, localApiUrl, deployBot, stopBot, activeBots, getVaultRecordings }
 */
function initTelegramBot({ token, localApiUrl, deployBot, stopBot, activeBots, getVaultRecordings }) {
  if (!token) {
    console.log('[Telegram Bot] TELEGRAM_BOT_TOKEN not provided in .env. Telegram Bot service disabled.');
    return null;
  }

  const options = { polling: true };
  if (localApiUrl) {
    options.baseApiUrl = localApiUrl;
  }

  try {
    botInstance = new TelegramBot(token, options);
    console.log('[Telegram Bot] Service successfully initialized and polling for commands.');

    // Helper: Send HTML formatted message safely
    const sendMessage = async (chatId, text, extraOptions = {}) => {
      try {
        return await botInstance.sendMessage(chatId, text, { parse_mode: 'HTML', ...extraOptions });
      } catch (e) {
        console.error(`[Telegram Bot Error] Failed to send message to ${chatId}:`, e.message);
      }
    };

    // Handler: /start or /help
    botInstance.onText(/\/(start|help)/i, (msg) => {
      const chatId = msg.chat.id;
      const helpText = `
<b>🤖 Ai ZoomParticipant — Telegram Recording Bot</b>

Welcome! Send me any Zoom meeting link or Meeting ID to record full 1080p HD MP4 video and receive it directly in this chat.

<b>Commands:</b>
• <code>/record &lt;zoom_link&gt; [passcode]</code> — Start recording a Zoom meeting
• <code>/status</code> — View live status of active meeting recording
• <code>/stop</code> — Stop current recording and receive MP4 video
• <code>/vault</code> — List recent meeting recordings

<b>Examples:</b>
• <code>https://zoom.us/j/82705502661?pwd=xxxx</code>
• <code>82705502661 passcode123</code>

<i>Or simply paste any Zoom meeting link directly into this chat!</i>
      `;
      sendMessage(chatId, helpText);
    });

    // Handler: Interactive Inline Keyboard Button Clicks (/stop via button)
    botInstance.on('callback_query', async (query) => {
      const chatId = query.message.chat.id;
      const data = query.data;

      if (data === 'stop_recording' || data.startsWith('stop_bot_')) {
        await botInstance.answerCallbackQuery(query.id, { text: '⏹ Stopping recording session...' }).catch(() => {});
        let stopped = false;

        for (const [botId, bot] of activeBots) {
          if (!bot.telegramChatId || String(bot.telegramChatId) === String(chatId) || activeBots.size === 1) {
            stopped = true;
            await sendMessage(chatId, `⏹ <b>Stopping recording for Meeting ${escapeHtml(bot.meetingId)}...</b>\nProcessing MP4 video capture...`);
            await stopBot(botId);
            break;
          }
        }

        if (!stopped) {
          sendMessage(chatId, 'ℹ️ No active recording session found to stop.');
        }
      }
    });

    // Handler: Zoom link paste or Meeting ID detection
    const zoomUrlRegex = /https?:\/\/([^\s]+)?zoom\.us\/[^\s]+/i;
    const meetingIdDigitsRegex = /\b\d{9,11}\b/;

    botInstance.on('message', async (msg) => {
      const text = (msg.text || '').trim();
      const chatId = msg.chat.id;

      if (!text || text.startsWith('/start') || text.startsWith('/help') || text.startsWith('/status') || text.startsWith('/stop') || text.startsWith('/vault')) {
        return;
      }

      let meetingUrlToDeploy = '';
      let passcode = '';

      const matchUrl = text.match(zoomUrlRegex);
      if (matchUrl) {
        meetingUrlToDeploy = matchUrl[0];
        const parts = text.split(/\s+/);
        if (parts.length > 1 && !parts[1].startsWith('http')) {
          passcode = parts[1];
        }
      } else {
        const matchId = text.match(meetingIdDigitsRegex);
        if (matchId) {
          const meetingId = matchId[0];
          const parts = text.split(/\s+/);
          if (parts.length > 1 && parts[0] === meetingId) {
            passcode = parts[1];
          } else if (parts.length > 2) {
            passcode = parts[2];
          }
          meetingUrlToDeploy = `https://zoom.us/j/${meetingId}${passcode ? `?pwd=${encodeURIComponent(passcode)}` : ''}`;
        }
      }

      if (meetingUrlToDeploy) {
        const rawUserFirstName = msg.from ? (msg.from.first_name || 'Telegram User') : 'Telegram User';
        const botName = `${rawUserFirstName}'s Bot`;

        await sendMessage(chatId, `
<b>✅ Zoom Meeting Received</b>

<b>Link / Target:</b> <code>${escapeHtml(meetingUrlToDeploy)}</code>
${passcode ? `<b>Passcode:</b> <code>${escapeHtml(passcode)}</code>\n` : ''}
🚀 <i>Deploying 1080p HD Autonomous Recording Bot...</i>
        `);

        try {
          const deployResult = await deployBot({
            meetingUrl: meetingUrlToDeploy,
            passcode: passcode,
            botName: botName,
            videoQuality: '1080p',
            videoFormat: 'mp4',
            telegramChatId: chatId
          });

          if (deployResult.success) {
            await sendMessage(chatId, `
<b>🔴 RECORDING STARTED</b>

<b>Meeting ID:</b> <code>${escapeHtml(deployResult.meetingId)}</code>
<b>Bot Display Name:</b> <code>${escapeHtml(botName)}</code>
<b>Resolution:</b> 1080p Full HD MP4

<i>I am monitoring the live screen. Tap the button below or send <code>/stop</code> anytime to finish and receive the MP4 video!</i>
            `, {
              reply_markup: {
                inline_keyboard: [
                  [
                    { text: '⏹ Stop Recording & Get Video MP4', callback_data: 'stop_recording' }
                  ]
                ]
              }
            });
          } else {
            await sendMessage(chatId, `❌ <b>Failed to start recording:</b> ${escapeHtml(deployResult.message || 'Check meeting URL.')}`);
          }
        } catch (err) {
          await sendMessage(chatId, `❌ <b>Error deploying recording bot:</b> ${escapeHtml(err.message)}`);
        }
      }
    });

    // Handler: /status
    botInstance.onText(/\/status/i, async (msg) => {
      const chatId = msg.chat.id;
      let activeCount = 0;
      let statusLines = [];

      for (const [botId, bot] of activeBots) {
        if (!bot.telegramChatId || String(bot.telegramChatId) === String(chatId) || activeBots.size === 1) {
          activeCount++;
          statusLines.push(`• <b>Bot:</b> ${escapeHtml(bot.botName)} | <b>Meeting:</b> <code>${escapeHtml(bot.meetingId)}</code> | <b>Status:</b> ${escapeHtml(bot.status || 'RECORDING')}`);
        }
      }

      if (activeCount === 0) {
        sendMessage(chatId, 'ℹ️ No active Zoom recording sessions currently running.');
      } else {
        sendMessage(chatId, `<b>🎥 Active Zoom Recording Sessions (${activeCount}):</b>\n\n${statusLines.join('\n')}`, {
          reply_markup: {
            inline_keyboard: [
              [
                { text: '⏹ Stop Recording Session', callback_data: 'stop_recording' }
              ]
            ]
          }
        });
      }
    });

    // Handler: /stop
    botInstance.onText(/\/stop/i, async (msg) => {
      const chatId = msg.chat.id;
      let stopped = false;

      for (const [botId, bot] of activeBots) {
        if (!bot.telegramChatId || String(bot.telegramChatId) === String(chatId) || activeBots.size === 1) {
          stopped = true;
          sendMessage(chatId, `⏹ <b>Stopping recording for Meeting ${escapeHtml(bot.meetingId)}...</b>\nProcessing MP4 video capture...`);
          await stopBot(botId);
          break;
        }
      }

      if (!stopped) {
        sendMessage(chatId, '⚠️ No active recording session found to stop.');
      }
    });

    // Handler: /vault
    botInstance.onText(/\/vault/i, async (msg) => {
      const chatId = msg.chat.id;
      const recordings = getVaultRecordings();

      if (!recordings || recordings.length === 0) {
        return sendMessage(chatId, '📁 <b>Recorded Meetings Library</b> is currently empty.');
      }

      const list = recordings.slice(0, 10).map((r, i) => 
        `<b>${i+1}. ${escapeHtml(r.fileName)}</b>\n   • Size: ${escapeHtml(r.sizeMb) || 'N/A'} | Date: ${r.createdAt ? new Date(r.createdAt).toLocaleDateString() : 'Recent'}`
      ).join('\n\n');

      sendMessage(chatId, `<b>📁 Recorded Meetings Vault (${recordings.length}):</b>\n\n${list}`);
    });

    return botInstance;
  } catch (err) {
    console.error('[Telegram Bot Error] Failed to initialize Telegram Bot:', err.message);
    return null;
  }
}

/**
 * Push completed recording MP4 directly into the user's Telegram chat
 */
async function notifyAndUploadRecording({ chatId, fileName, filePath, sizeBytes }) {
  if (!botInstance || !chatId || !fs.existsSync(filePath)) {
    return;
  }

  try {
    await botInstance.sendMessage(chatId, `
⏹ <b>RECORDING FINISHED</b>

<b>File Name:</b> <code>${escapeHtml(fileName)}</code>
<b>Size:</b> ${(sizeBytes / (1024 * 1024)).toFixed(1)} MB

📤 <i>Uploading MP4 video to chat...</i>
    `, { parse_mode: 'HTML' });

    const maxTelegramBytes = 50 * 1024 * 1024; // 50 MB standard bot API limit

    if (sizeBytes <= maxTelegramBytes) {
      // Direct Upload
      await botInstance.sendVideo(chatId, filePath, {
        caption: `🎥 <b>Zoom Meeting Recording</b>\n<code>${escapeHtml(fileName)}</code>`,
        parse_mode: 'HTML'
      }, {
        filename: fileName,
        contentType: 'video/mp4'
      });
    } else {
      // File exceeds 50 MB: Split into 45 MB parts using FFmpeg and upload each part
      await botInstance.sendMessage(chatId, `ℹ️ <i>File exceeds 50 MB Telegram API limit. Splitting into 45 MB playable MP4 parts...</i>`, { parse_mode: 'HTML' });
      
      const parts = await splitVideoFile(filePath, 45 * 1024 * 1024);
      for (let i = 0; i < parts.length; i++) {
        const partPath = parts[i];
        const partName = path.basename(partPath);
        await botInstance.sendVideo(chatId, partPath, {
          caption: `🎥 <b>Zoom Recording (Part ${i + 1} of ${parts.length})</b>\n<code>${escapeHtml(partName)}</code>`,
          parse_mode: 'HTML'
        }, {
          filename: partName,
          contentType: 'video/mp4'
        });
        // Cleanup temp part file
        try { fs.unlinkSync(partPath); } catch (e) {}
      }
    }
  } catch (err) {
    console.error(`[Telegram Upload Error] Failed to upload ${fileName} to ${chatId}:`, err.message);
    try {
      await botInstance.sendMessage(chatId, `⚠️ <b>Video Saved in Vault</b>\nFile: <code>${escapeHtml(fileName)}</code> (${(sizeBytes / (1024 * 1024)).toFixed(1)} MB)\n<i>Note: Could not attach video directly due to file size or network limit. Access it anytime in your web Vault!</i>`, { parse_mode: 'HTML' });
    } catch (e) {}
  }
}

/**
 * Split large MP4 video file into chunks smaller than chunkSizeBytes using FFmpeg
 */
function splitVideoFile(filePath, chunkSizeBytes = 45 * 1024 * 1024) {
  return new Promise((resolve) => {
    let ffmpegPath = 'ffmpeg';
    try {
      ffmpegPath = require('ffmpeg-static') || 'ffmpeg';
    } catch (e) {}
    const stats = fs.statSync(filePath);
    const totalBytes = stats.size;
    const numChunks = Math.ceil(totalBytes / chunkSizeBytes);

    if (numChunks <= 1) return resolve([filePath]);

    // Get video duration via ffmpeg command
    const durationCmd = `"${ffmpegPath}" -i "${filePath}" 2>&1`;
    exec(durationCmd, (err, stdout, stderr) => {
      const output = stdout + stderr;
      const durMatch = output.match(/Duration:\s*(\d+):(\d+):(\d+\.\d+)/);
      let totalDurationSec = 600; // default 10 mins fallback

      if (durMatch) {
        totalDurationSec = (parseInt(durMatch[1]) * 3600) + (parseInt(durMatch[2]) * 60) + parseFloat(durMatch[3]);
      }

      const chunkDurationSec = Math.floor(totalDurationSec / numChunks);
      const outputParts = [];
      let completed = 0;

      const dir = path.dirname(filePath);
      const baseName = path.basename(filePath, path.extname(filePath));

      for (let i = 0; i < numChunks; i++) {
        const startSec = i * chunkDurationSec;
        const outPartPath = path.join(dir, `${baseName}_part${i + 1}.mp4`);
        outputParts.push(outPartPath);

        const splitCmd = `"${ffmpegPath}" -ss ${startSec} -i "${filePath}" -t ${chunkDurationSec} -c copy -y "${outPartPath}"`;
        exec(splitCmd, (splitErr) => {
          completed++;
          if (completed === numChunks) {
            resolve(outputParts);
          }
        });
      }
    });
  });
}

module.exports = {
  initTelegramBot,
  notifyAndUploadRecording
};
