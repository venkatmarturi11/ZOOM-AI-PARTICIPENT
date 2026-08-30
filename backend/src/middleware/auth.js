const admin = require('firebase-admin');

/**
 * Express middleware that verifies Firebase ID Tokens.
 *
 * Expects: Authorization: Bearer <firebaseIdToken>
 *
 * On success, attaches `req.user` with:
 *   - uid:   Firebase user ID
 *   - email: User's email
 *   - name:  User's display name
 *
 * On failure, returns 401 Unauthorized.
 */
async function requireFirebaseAuth(req, res, next) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Missing or malformed Authorization header. Expected: Bearer <firebaseIdToken>',
    });
  }

  const idToken = authHeader.split('Bearer ')[1];

  try {
    const decodedToken = await admin.auth().verifyIdToken(idToken);
    req.user = {
      uid: decodedToken.uid,
      email: decodedToken.email || null,
      name: decodedToken.name || null,
    };
    next();
  } catch (err) {
    console.error('Firebase token verification failed:', err.message);
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid or expired Firebase ID token.',
    });
  }
}

module.exports = { requireFirebaseAuth };
