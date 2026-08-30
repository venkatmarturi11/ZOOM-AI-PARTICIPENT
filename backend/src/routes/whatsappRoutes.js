// server/routes/whatsappRoutes.js
//
// Handles incoming WhatsApp messages containing Zoom meeting links.
// Compatible with Twilio for WhatsApp, Green API, UltraMsg, or custom HTTP webhooks.

const express = require('express');
const router = express.Router();

router.post('/incoming', async (req, res) => {
  const appLocals = req.app.locals;

  // Support Twilio ('Body', 'From'), GreenAPI ('messageData', 'senderData'), or generic ('text', 'from', 'message')
  const bodyText = req.body.Body || req.body.text || req.body.message || (req.body.messageData && req.body.messageData.textMessageData && req.body.messageData.textMessage) || '';
  const senderId = req.body.From || req.body.from || (req.body.senderData && req.body.senderData.sender) || 'whatsapp_user';

  if (!bodyText) {
    return res.status(400).json({ success: false, error: 'No message text provided in request body' });
  }

  const cleanText = bodyText.trim();
  console.log(`[WhatsApp Bot] Received message from ${senderId}: "${cleanText}"`);

  // 1. Status Command
  if (cleanText.toLowerCase() === 'status') {
    let statusMsg = 'ℹ️ No active Zoom recording session found.';
    if (appLocals && typeof appLocals.getActiveBotStatus === 'function') {
      statusMsg = appLocals.getActiveBotStatus();
    }

    if (req.body.Body) {
      res.set('Content-Type', 'text/xml');
      return res.send(`<Response><Message>${escapeXml(statusMsg)}</Message></Response>`);
    }
    return res.json({ success: true, reply: statusMsg });
  }

  // 2. Stop Command
  if (cleanText.toLowerCase() === 'stop') {
    let stopMsg = '⏹️ Stop requested. Finalizing video recording...';
    if (appLocals && typeof appLocals.stopActiveBot === 'function') {
      stopMsg = appLocals.stopActiveBot();
    }

    if (req.body.Body) {
      res.set('Content-Type', 'text/xml');
      return res.send(`<Response><Message>${escapeXml(stopMsg)}</Message></Response>`);
    }
    return res.json({ success: true, reply: stopMsg });
  }

  // 3. Zoom Link Parsing & Bot Deployment
  if (appLocals && typeof appLocals.buildZoomUrls === 'function' && typeof appLocals.deployBotFromUrl === 'function') {
    const parsed = appLocals.buildZoomUrls(cleanText);
    if (parsed && parsed.meetingId) {
      console.log(`[WhatsApp Bot] Parsed Zoom meeting link for ID: ${parsed.meetingId}`);
      
      const deployResult = appLocals.deployBotFromUrl(parsed, senderId);
      const replyMsg = `🚀 Autonomous AI Bot deployed to Zoom Meeting *${parsed.meetingId}*!\nStatus: JOINING (1080p HD Auto-Recorder)`;

      if (req.body.Body) {
        res.set('Content-Type', 'text/xml');
        return res.send(`<Response><Message>${escapeXml(replyMsg)}</Message></Response>`);
      }
      return res.json({
        success: true,
        reply: replyMsg,
        meetingId: parsed.meetingId,
        botId: deployResult ? deployResult.botId : null
      });
    }
  }

  const helpReply = `🎥 *Zoom Autonomous Recorder Bot*\n\nSend a Zoom meeting link to auto-deploy a 1080p recorder bot!\n\nCommands:\n• Send Zoom Link (e.g. https://zoom.us/j/1234567890?pwd=...)\n• status - Check active recording\n• stop - Finish & save recording`;

  if (req.body.Body) {
    res.set('Content-Type', 'text/xml');
    return res.send(`<Response><Message>${escapeXml(helpReply)}</Message></Response>`);
  }
  return res.json({ success: true, reply: helpReply });
});

router.get('/status', (req, res) => {
  res.json({ success: true, service: 'WhatsApp Zoom Bot Integration', webhookUrl: '/api/whatsapp/incoming' });
});

function escapeXml(str) {
  return (str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

module.exports = router;
