const express = require('express');
const jwt = require('jsonwebtoken');
const { requireFirebaseAuth } = require('../middleware/auth');

const router = express.Router();

// All zoom routes require Firebase authentication
router.use(requireFirebaseAuth);

/**
 * GET /zoom/sdk-jwt
 *
 * Generates a Zoom Meeting SDK JWT for the Android app to authenticate
 * with the Zoom SDK. The JWT is signed using ZOOM_SDK_SECRET, which
 * never leaves this server.
 *
 * The Android app calls this on SDK initialization and when the token expires.
 */
router.get('/sdk-jwt', (req, res) => {
  const sdkKey = process.env.ZOOM_SDK_KEY;
  const sdkSecret = process.env.ZOOM_SDK_SECRET;

  if (!sdkKey || !sdkSecret) {
    return res.status(500).json({
      error: 'Server configuration error',
      message: 'ZOOM_SDK_KEY and ZOOM_SDK_SECRET must be set in .env',
    });
  }

  const iat = Math.floor(Date.now() / 1000) - 30; // 30 seconds in the past (clock skew buffer)
  const exp = iat + 60 * 60 * 2; // 2 hours from now
  const tokenExp = exp; // Token expiration matches

  const payload = {
    appKey: sdkKey,
    iat,
    exp,
    tokenExp,
  };

  try {
    const sdkJwt = jwt.sign(payload, sdkSecret, { algorithm: 'HS256' });

    console.log(`[zoom/sdk-jwt] Generated JWT for user ${req.user.uid} (${req.user.email})`);

    res.json({
      sdkJwt,
      expiresAt: exp,
    });
  } catch (err) {
    console.error('[zoom/sdk-jwt] JWT generation failed:', err);
    res.status(500).json({
      error: 'JWT generation failed',
      message: err.message,
    });
  }
});

/**
 * POST /zoom/meetings
 *
 * Creates a new Zoom meeting via the Zoom REST API.
 *
 * TODO: Implement this when you have Server-to-Server OAuth credentials.
 * The flow is:
 * 1. This server uses its own Zoom S2S OAuth token to call Zoom's API
 * 2. POST https://api.zoom.us/v2/users/{userId}/meetings
 * 3. Returns the meeting ID, passcode, and join URL to the app
 */
router.post('/meetings', async (req, res) => {
  const { topic, duration } = req.body;

  // TODO: Implement Zoom REST API meeting creation
  // For now, return a stub response
  console.log(`[zoom/meetings] Meeting creation requested by ${req.user.uid}: topic="${topic}", duration=${duration}`);

  res.status(501).json({
    error: 'Not implemented',
    message: 'Meeting creation via Zoom REST API is not yet implemented. ' +
             'See backend/src/routes/zoom.js for the TODO.',
  });
});

module.exports = router;
