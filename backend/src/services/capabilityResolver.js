/**
 * Capability Resolver for Zoom Meetings
 * Decides whether to use Autonomous Playwright Bot, ZAK Token, or RTMS Media.
 */

function resolveCapability(context = {}) {
  const { requestedMode, isHost, hasZak, hasOAuth } = context;

  if (requestedMode === 'RTMS' && hasOAuth) {
    return {
      capability: 'RTMS_MEDIA',
      engine: 'rtms',
      reason: 'User requested direct Realtime Media Stream ingestion via Zoom RTMS.'
    };
  }

  if (isHost && hasZak) {
    return {
      capability: 'ZAK_PARTICIPANT',
      engine: 'browser_bot',
      reason: 'User is host or internal account. Joining with authenticated ZAK token.'
    };
  }

  // Default autonomous browser participant bot
  return {
    capability: 'AUTONOMOUS_BOT',
    engine: 'playwright_bot',
    reason: 'Autonomous 12-state Zoom participant bot with 1080p video recording & audio capture.'
  };
}

module.exports = { resolveCapability };
