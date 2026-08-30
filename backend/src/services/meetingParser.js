/**
 * Advanced Zoom Meeting URL & Invitation Parser
 * Ported & adapted from ai-bot enterprise meeting parser.
 */

const ZOOM_HOST_PATTERN = /^([a-z0-9-]+\.)?zoom\.us$/i;
const MEETING_PATH_PATTERN = /^\/(?:j|w|wc|s)\/(\d{9,11})(?:\/(?:join|start))?$/i;
const VANITY_PATH_PATTERN = /^\/my\/([a-zA-Z0-9._-]+)$/;
const BLOCKED_SCHEMES = new Set(['javascript:', 'file:', 'data:', 'vbscript:']);

function isValidMeetingId(id) {
  if (!id) return false;
  return /^\d{9,11}$/.test(String(id).trim().replace(/[\s-]/g, ''));
}

function extractZoomUrl(text) {
  if (!text) return null;
  const urlPattern = /https:\/\/[a-z0-9.-]*zoom\.us\/[^\s]+/i;
  const match = urlPattern.exec(text);
  return match ? match[0] : null;
}

function parseMeetingInput(input) {
  if (!input) {
    return { success: false, error: 'No input provided.' };
  }

  const trimmed = input.trim();

  // 1. If it's a numeric meeting ID directly
  const numericOnly = trimmed.replace(/[\s-]/g, '');
  if (/^\d{9,11}$/.test(numericOnly)) {
    return {
      success: true,
      meeting: {
        meetingId: numericOnly,
        passcode: null,
        domain: 'zoom.us',
        isVanityUrl: false,
        joinUrl: `https://app.zoom.us/wc/${numericOnly}/join?prefer=1`,
        directWcUrl: `https://app.zoom.us/wc/${numericOnly}/join?prefer=1`,
        originalUrl: trimmed
      }
    };
  }

  // 2. Check for URL inside text
  const extractedUrl = extractZoomUrl(trimmed) || (trimmed.startsWith('http') ? trimmed : null);
  if (!extractedUrl) {
    return {
      success: false,
      error: 'Invalid meeting ID or URL. Expected 9-11 digits or a valid Zoom URL.'
    };
  }

  const lowerInput = extractedUrl.toLowerCase();
  for (const scheme of BLOCKED_SCHEMES) {
    if (lowerInput.startsWith(scheme)) {
      return { success: false, error: 'Invalid URL scheme.' };
    }
  }

  let url;
  try {
    url = new URL(extractedUrl);
  } catch (err) {
    return { success: false, error: `Invalid URL format: ${err.message}` };
  }

  if (!ZOOM_HOST_PATTERN.test(url.hostname)) {
    return { success: false, error: 'Not a Zoom meeting URL (must be zoom.us).' };
  }

  const passcode = url.searchParams.get('pwd') || null;

  // Pattern: /j/{id}, /wc/{id}, /w/{id}
  const meetingMatch = MEETING_PATH_PATTERN.exec(url.pathname);
  if (meetingMatch && meetingMatch[1]) {
    const meetingId = meetingMatch[1];
    const directWcUrl = `https://app.zoom.us/wc/${meetingId}/join?prefer=1${passcode ? `&pwd=${encodeURIComponent(passcode)}` : ''}`;
    return {
      success: true,
      meeting: {
        meetingId,
        passcode,
        domain: url.hostname,
        isVanityUrl: false,
        joinUrl: extractedUrl,
        directWcUrl,
        originalUrl: extractedUrl
      }
    };
  }

  // Vanity URL
  const vanityMatch = VANITY_PATH_PATTERN.exec(url.pathname);
  if (vanityMatch && vanityMatch[1]) {
    return {
      success: true,
      meeting: {
        meetingId: vanityMatch[1],
        passcode,
        domain: url.hostname,
        isVanityUrl: true,
        joinUrl: extractedUrl,
        directWcUrl: extractedUrl,
        originalUrl: extractedUrl
      }
    };
  }

  return {
    success: false,
    error: 'Could not extract a valid meeting ID from this Zoom URL.'
  };
}

module.exports = {
  parseMeetingInput,
  isValidMeetingId,
  extractZoomUrl
};
