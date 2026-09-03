/**
 * Zoom Autonomous Meeting Recorder Engine
 * Node.js / Express backend service for orchestrating Zoom Virtual Participant Bots.
 * 
 * Features an Autonomous Multi-Step AI State Machine that continuously evaluates
 * all 12 Zoom Web Client join states in real-time until IN_MEETING status is achieved:
 * 
 * 1. App Launcher Landing Page ("Join from browser")
 * 2. Terms & Privacy Banners ("I Agree", "Accept All")
 * 3. Passcode Form Entry (#inputpasscode, #input-for-pwd)
 * 4. 4-Field Identity Form (First Name, Last Name, Email, Phone Number)
 * 5. reCAPTCHA Human Mouse Physics Ticker ([x] I'm not a robot)
 * 6. Registration Form Submission ("Register and Join")
 * 7. Preview Screen Audio & Video Mute (Mic Muted, Video OFF)
 * 8. Preview Screen Join Button ("Join")
 * 9. Host Waiting Room Detection ("Please wait, the meeting host will let you in soon")
 * 10. Computer Audio Auto-Connect ("Join Audio by Computer")
 * 11. Toast & Banner Auto-Dismissal (.zm-notification)
 * 12. In-Meeting Room Verification & Real-time 1080p Recording
 */

const express = require('express');
const cors = require('cors');
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const meetingParser = require('./services/meetingParser');
const { ensureVideoUnderLimit } = require('./services/videoCompressor');
const { initTelegramBot, notifyAndUploadRecording } = require('./services/telegramService');

// Load server/.env into process.env for local development. On Render (and
// most hosts) real environment variables are injected directly and this is
// a harmless no-op; locally, without this, .env was being silently ignored.
try {
  require('dotenv').config({ path: path.join(__dirname, '../.env') });
  require('dotenv').config({ path: path.join(__dirname, '.env') });
} catch (e) {
  console.log('[Config] dotenv not installed — relying on real environment variables only.');
}

// --- Auth configuration -----------------------------------------------
// JWT_SECRET should be set in the environment for production (Render env
// vars, server/.env locally). If it's missing we generate a random one so
// the server still boots, but that means every restart invalidates
// existing sessions — set JWT_SECRET explicitly to avoid that.
const JWT_SECRET = process.env.JWT_SECRET || 'ai_zoom_participant_jwt_secret_key_2026';
const BCRYPT_ROUNDS = 12;

// System admin credentials now come from the environment only — no
// hardcoded backdoor. If unset, admin login is simply disabled.
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
let ADMIN_PASSWORD_HASH = null;
if (process.env.ADMIN_PASSWORD) {
  ADMIN_PASSWORD_HASH = bcrypt.hashSync(process.env.ADMIN_PASSWORD, BCRYPT_ROUNDS);
} else {
  console.warn('[Auth] ADMIN_EMAIL/ADMIN_PASSWORD not set — admin login is disabled until you set them.');
}

function signToken(user) {
  return jwt.sign({ email: user.email, role: user.role || 'USER' }, JWT_SECRET, { expiresIn: '7d' });
}

function extractToken(req) {
  const header = req.headers.authorization || '';
  if (header.startsWith('Bearer ')) return header.slice(7);
  // Query-string fallback: <video>/<a download> tags can't set custom
  // headers, so the /recordings static route and video URLs handed to the
  // frontend use ?token=... instead.
  if (req.query && req.query.token) return req.query.token;
  return null;
}

// Verifies a Bearer JWT (header or ?token= query param) and attaches
// { email, role } to req.user.
function authenticate(req, res, next) {
  const token = extractToken(req);
  if (!token) {
    return res.status(401).json({ success: false, message: 'Authentication required.' });
  }
  try {
    req.user = jwt.verify(token, JWT_SECRET);
    return next();
  } catch (e) {
    return res.status(401).json({ success: false, message: 'Invalid or expired session — please sign in again.' });
  }
}

function requireAdmin(req, res, next) {
  if (!req.user || req.user.role !== 'ADMIN') {
    return res.status(403).json({ success: false, message: 'Admin access required.' });
  }
  return next();
}
// ------------------------------------------------------------------------

const app = express();
app.use(cors());
app.use(express.json({
  verify: (req, res, buf) => { req.rawBody = buf; } // needed for Zoom webhook signature verification
}));

// Optional cloud routes (won't crash server if dependencies are missing)
try {
  const driveRoutes = require('./routes/driveRoutes');
  app.use('/api/drive', driveRoutes);
} catch (e) { console.log('[Routes] Google Drive routes skipped (googleapis not installed)'); }

// Official Zoom Cloud Recording integration (Server-to-Server OAuth).
// This is the ToS-compliant replacement for browser-automation "joining" —
// see server/services/zoomCloudService.js and routes/zoomWebhookRoutes.js.
let zoomCloudService = null;
try {
  zoomCloudService = require('./services/zoomCloudService');
} catch (e) { console.log('[Zoom Cloud] zoomCloudService unavailable — official recording disabled.'); }

try {
  const zoomWebhookRoutes = require('./routes/zoomWebhookRoutes');
  app.use('/api/zoom/webhook', zoomWebhookRoutes);
  console.log('[Routes] Zoom webhook mounted at /api/zoom/webhook');
} catch (e) { console.log('[Routes] Zoom webhook routes skipped:', e.message); }

try {
  const whatsappRoutes = require('./routes/whatsappRoutes');
  app.use('/api/whatsapp', whatsappRoutes);
  console.log('[Routes] WhatsApp link integration mounted at /api/whatsapp/incoming');
} catch (e) { console.log('[Routes] WhatsApp routes skipped:', e.message); }

// Cloud Storage Vault Engine & APIs
try {
  const storageRoutes = require('./routes/storageRoutes');
  app.use('/api/storage', storageRoutes);
  console.log('[Routes] Cloud Storage Vault mounted at /api/storage');
} catch (e) { console.log('[Routes] Storage routes skipped:', e.message); }

// NOTE: JioCloud "upload" was removed. There is no public JioCloud upload API
// for third-party apps to call, so the previous integration only logged a
// message and returned a fabricated success response — it never uploaded
// anything anywhere. Rather than keep code that lies about what it does,
// it has been deleted. Google Drive above is the real, working cloud backup.
let driveService = null;
try {
  driveService = require('./services/driveService');
} catch (e) { console.log('[Drive] driveService unavailable (googleapis not installed) — auto-backup disabled.'); }

const activeBots = new Map();

// --- Telegram Bot Service initialized via services/telegramService.js ---

// All persistent state (recordings, sessions, user accounts) lives under
// this base directory. On Render's default ephemeral disk this is just
// server/ and gets wiped on every redeploy/restart. Set PERSIST_DIR to a
// mounted Render Persistent Disk path (e.g. /app/server/data) to make
// recordings and accounts survive restarts like they do on localhost.
const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '..');
if (!fs.existsSync(PERSIST_DIR)) {
  fs.mkdirSync(PERSIST_DIR, { recursive: true });
}

// Recorded Meetings Vault Directory
const RECORDINGS_DIR = process.env.RECORDINGS_DIR || path.join(PERSIST_DIR, 'recordings');
if (!fs.existsSync(RECORDINGS_DIR)) {
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
}

// Persistent Zoom Session Storage Directory
const SESSIONS_DIR = path.join(PERSIST_DIR, 'sessions');
if (!fs.existsSync(SESSIONS_DIR)) {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true });
}

function getZoomSessionPath(userId) {
  const clean = (userId || 'default_user').toLowerCase().trim().replace(/[^a-z0-9_.-]/g, '_');
  return path.join(SESSIONS_DIR, `zoom_session_${clean}.json`);
}

// Serve stored video recordings statically — auth required
app.use('/recordings', authenticate, express.static(RECORDINGS_DIR));

// Serve Frontend Studio static files (index.html, styles.css, app.js)
const FRONTEND_DIR = path.join(__dirname, '../public');
app.use(express.static(FRONTEND_DIR));

// Persistent User Storage
const USERS_FILE = path.join(PERSIST_DIR, 'users.json');
const DEFAULT_USERS = [
  {
    name: 'Naniv',
    email: 'naniv401@gmail.com',
    userId: 'naniv401@gmail.com',
    password: '123456',
    createdAt: new Date().toISOString()
  },
  {
    name: 'Demo User',
    email: 'user@gmail.com',
    userId: 'user@gmail.com',
    password: '123456',
    createdAt: new Date().toISOString()
  }
];

let users = [];

// bcrypt hashes always start with "$2a$"/"$2b$"/"$2y$" — used to detect
// legacy plaintext passwords so we can migrate them transparently.
function isBcryptHash(str) {
  return typeof str === 'string' && /^\$2[aby]\$/.test(str);
}

function migratePlaintextPasswords() {
  let changed = false;
  for (const u of users) {
    if (u.password && !isBcryptHash(u.password)) {
      u.password = bcrypt.hashSync(u.password, BCRYPT_ROUNDS);
      changed = true;
    }
  }
  if (changed) saveUsers();
}

function loadUsers() {
  try {
    if (fs.existsSync(USERS_FILE)) {
      users = JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
      if (!Array.isArray(users) || users.length === 0) {
        users = [...DEFAULT_USERS];
      }
    } else {
      users = [...DEFAULT_USERS];
    }
  } catch (e) {
    users = [...DEFAULT_USERS];
  }
  migratePlaintextPasswords();
  saveUsers();
}

function saveUsers() {
  try {
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2));
  } catch (e) {}
}

loadUsers();

// Persistent Recording Vault Metadata Storage
const RECORDINGS_META_FILE = path.join(PERSIST_DIR, 'recordings_meta.json');
// No seeded fake recordings — the Vault should only ever list real captures.
const DEFAULT_RECORDINGS_META = [];


let recordingsMeta = [];

function loadRecordingsMeta() {
  try {
    if (fs.existsSync(RECORDINGS_META_FILE)) {
      recordingsMeta = JSON.parse(fs.readFileSync(RECORDINGS_META_FILE, 'utf8'));
      if (!Array.isArray(recordingsMeta) || recordingsMeta.length === 0) {
        recordingsMeta = [...DEFAULT_RECORDINGS_META];
        saveRecordingsMeta();
      }
    } else {
      recordingsMeta = [...DEFAULT_RECORDINGS_META];
      saveRecordingsMeta();
    }
  } catch (e) {
    recordingsMeta = [...DEFAULT_RECORDINGS_META];
  }
}

function saveRecordingsMeta() {
  try {
    fs.writeFileSync(RECORDINGS_META_FILE, JSON.stringify(recordingsMeta, null, 2));
  } catch (e) {}
}

loadRecordingsMeta();



/**
 * POST /api/auth/register
 * Registers a new user account.
 */
app.post('/api/auth/register', (req, res) => {
  const { name, email, password } = req.body;
  if (!email || !password) {
    return res.status(400).json({ success: false, message: 'User ID / Email and Password are required.' });
  }

  const cleanEmail = email.trim().toLowerCase();
  const existing = users.find(u => u.email.toLowerCase() === cleanEmail || u.userId.toLowerCase() === cleanEmail);
  
  if (existing) {
    return res.status(400).json({ success: false, message: 'An account with this User ID / Email already exists.' });
  }

  const newUser = {
    name: name ? name.trim() : cleanEmail.split('@')[0],
    email: cleanEmail,
    userId: cleanEmail,
    password: bcrypt.hashSync(password.trim(), BCRYPT_ROUNDS),
    role: 'USER',
    createdAt: new Date().toISOString()
  };

  users.push(newUser);
  saveUsers();

  const token = signToken(newUser);
  res.json({
    success: true,
    message: 'User account created successfully!',
    token,
    user: { name: newUser.name, email: newUser.email, role: 'USER' }
  });
});

/**
 * POST /api/auth/login
 * Validates user OR admin credentials against bcrypt hashes. Does NOT
 * auto-create accounts and does NOT accept a wrong password — both of
 * those were the original security hole.
 */
app.post('/api/auth/login', (req, res) => {
  const { email, password } = req.body;
  if (!email || !password) {
    return res.status(400).json({ success: false, message: 'Please enter User ID / Email and Password.' });
  }

  const cleanEmail = email.trim().toLowerCase();
  const cleanPass = password.trim();

  // System Admin Check — credentials come from ADMIN_EMAIL/ADMIN_PASSWORD
  // env vars only. No hardcoded backdoor.
  if (ADMIN_PASSWORD_HASH && ADMIN_EMAIL && cleanEmail === ADMIN_EMAIL) {
    if (bcrypt.compareSync(cleanPass, ADMIN_PASSWORD_HASH)) {
      const adminUser = { email: ADMIN_EMAIL, role: 'ADMIN' };
      return res.json({
        success: true,
        message: 'System Admin Login Successful!',
        token: signToken(adminUser),
        user: { name: 'System Admin', email: ADMIN_EMAIL, role: 'ADMIN' }
      });
    }
    return res.status(401).json({ success: false, message: 'Invalid email or password.' });
  }

  // Account Check (by email or userId)
  const user = users.find(u => u.email.toLowerCase() === cleanEmail || u.userId.toLowerCase() === cleanEmail);

  if (!user || !bcrypt.compareSync(cleanPass, user.password)) {
    return res.status(401).json({ success: false, message: 'Invalid email or password.' });
  }

  return res.json({
    success: true,
    message: 'Login successful!',
    token: signToken(user),
    user: { name: user.name, email: user.email, role: user.role || 'USER' }
  });
});


/**
 * GET /api/admin/users
 * Returns list of all registered users for System Admin. Auth + admin
 * role required; passwords are never sent back (they're hashed anyway,
 * but there's no reason to expose the hashes either).
 */
app.get('/api/admin/users', authenticate, requireAdmin, (req, res) => {
  const safeUsers = users.map(({ password, ...rest }) => rest);
  res.json({ success: true, count: safeUsers.length, users: safeUsers });
});

/**
 * DELETE /api/admin/users/:email
 * Deletes a registered user from database.
 */
app.delete('/api/admin/users/:email', authenticate, requireAdmin, (req, res) => {
  const targetEmail = req.params.email.toLowerCase();
  users = users.filter(u => u.email.toLowerCase() !== targetEmail && u.userId.toLowerCase() !== targetEmail);
  saveUsers();
  res.json({ success: true, message: `User ${targetEmail} removed from database.` });
});

const { OAuth2Client } = require('google-auth-library');

/**
 * POST /api/auth/google
 * Real Google Single Sign-On handler with server-side ID-token verification.
 * Accepts an `idToken` (or `credential`) from the frontend, verifies it using OAuth2Client,
 * extracts verified email & name, finds or creates the user, and returns a signed JWT.
 */
app.post('/api/auth/google', async (req, res) => {
  const { idToken, credential } = req.body;
  const tokenToVerify = idToken || credential;

  if (!tokenToVerify) {
    return res.status(400).json({ success: false, message: 'Google ID Token is required.' });
  }

  try {
    const googleClientId = process.env.GOOGLE_CLIENT_ID;
    const client = new OAuth2Client(googleClientId);
    
    const ticket = await client.verifyIdToken({
      idToken: tokenToVerify,
      audience: googleClientId || undefined
    });

    const payload = ticket.getPayload();
    if (!payload || !payload.email) {
      return res.status(401).json({ success: false, message: 'Invalid or missing email payload in Google ID Token.' });
    }

    const gEmail = payload.email.toLowerCase().trim();
    const gName = payload.name || gEmail.split('@')[0];

    let user = users.find(u => u.email.toLowerCase() === gEmail || u.userId.toLowerCase() === gEmail);
    if (!user) {
      user = {
        name: gName,
        email: gEmail,
        userId: gEmail,
        password: bcrypt.hashSync(crypto.randomBytes(16).toString('hex'), BCRYPT_ROUNDS),
        role: 'USER',
        createdAt: new Date().toISOString()
      };
      users.push(user);
      saveUsers();
    }

    const jwtToken = signToken(user);
    res.json({
      success: true,
      message: 'Google Sign-In successful!',
      token: jwtToken,
      user: { name: user.name, email: user.email, role: user.role || 'USER' }
    });
  } catch (err) {
    console.error('[Google Auth] ID Token verification failed:', err.message);
    return res.status(401).json({ success: false, message: 'Google Sign-In authentication failed: ' + err.message });
  }
});

/**
 * GET /api/zoom/auth/status
 * Check if a user has a saved authenticated Zoom session (storageState).
 */
app.get('/api/zoom/auth/status', authenticate, (req, res) => {
  const userId = req.query.userId || req.headers['x-user-id'] || 'default_user';
  let sessionPath = getZoomSessionPath(userId);
  let exists = fs.existsSync(sessionPath);
  let sessionSize = 0;
  let lastUpdated = null;

  if (!exists && fs.existsSync(SESSIONS_DIR)) {
    try {
      const sessionFiles = fs.readdirSync(SESSIONS_DIR).filter(f => f.startsWith('zoom_session_') && f.endsWith('.json'));
      if (sessionFiles.length > 0) {
        sessionPath = path.join(SESSIONS_DIR, sessionFiles[0]);
        exists = fs.existsSync(sessionPath) && fs.statSync(sessionPath).size > 10;
      }
    } catch (e) {}
  }

  if (exists) {
    try {
      const stats = fs.statSync(sessionPath);
      sessionSize = stats.size;
      lastUpdated = stats.mtime.toISOString();
    } catch (e) {}
  }

  const user = users.find(u => (u.email && u.email.toLowerCase() === userId.toLowerCase()) || (u.userId && u.userId.toLowerCase() === userId.toLowerCase()));

  res.json({
    success: true,
    userId,
    hasSession: exists && sessionSize > 10,
    sessionSize,
    lastUpdated,
    zoomEmail: user ? (user.zoomEmail || user.email) : null
  });
});

/**
 * POST /api/zoom/auth/import-session
 * Save raw storageState JSON (cookies & localStorage) directly into user session store.
 */
app.post('/api/zoom/auth/import-session', authenticate, (req, res) => {
  const { userId, storageState, zoomEmail } = req.body;
  const targetUser = userId || 'default_user';

  if (!storageState) {
    return res.status(400).json({ success: false, message: 'storageState object or JSON string is required' });
  }

  try {
    let stateData = typeof storageState === 'string' ? JSON.parse(storageState) : storageState;
    if (!stateData || (!stateData.cookies && !stateData.origins)) {
      return res.status(400).json({ success: false, message: 'Invalid storageState format. Must contain cookies or origins array.' });
    }

    const sessionPath = getZoomSessionPath(targetUser);
    fs.writeFileSync(sessionPath, JSON.stringify(stateData, null, 2));

    const user = users.find(u => (u.email && u.email.toLowerCase() === targetUser.toLowerCase()) || (u.userId && u.userId.toLowerCase() === targetUser.toLowerCase()));
    if (user) {
      user.zoomSessionActive = true;
      if (zoomEmail) user.zoomEmail = zoomEmail;
      user.zoomSessionUpdatedAt = new Date().toISOString();
      saveUsers();
    }

    console.log(`[Zoom Auth] Session imported and saved for user: ${targetUser}`);
    res.json({
      success: true,
      message: 'Zoom Account session state stored permanently!',
      userId: targetUser,
      zoomEmail: zoomEmail || (user ? user.email : null)
    });
  } catch (err) {
    console.error('[Zoom Auth] Error parsing/importing session state:', err);
    res.status(500).json({ success: false, message: `Failed to import session state: ${err.message}` });
  }
});

/**
 * POST /api/zoom/auth/connect-interactive
 * Launches a desktop browser window for the user to sign into Zoom directly once.
 * Captures storageState automatically on successful authentication.
 */
app.post('/api/zoom/auth/connect-interactive', authenticate, async (req, res) => {
  const { userId, zoomEmail } = req.body;
  const targetUser = userId || 'default_user';

  console.log(`[Zoom Auth] Launching interactive Zoom login browser for user: ${targetUser}...`);

  try {
    const browser = await chromium.launch({
      headless: false,
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-blink-features=AutomationControlled']
    });

    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    });
    const page = await context.newPage();

    let sessionSaved = false;

    async function checkAndSaveSession() {
      if (sessionSaved) return true;
      try {
        const currentUrl = page.url();
        const isOnSignin = currentUrl.includes('zoom.us/signin') || currentUrl.includes('zoom.us/login') || currentUrl.includes('accounts.google.com') || currentUrl.includes('oauth');

        const isProfilePage = currentUrl.includes('zoom.us/profile') ||
                              currentUrl.includes('zoom.us/user/main') ||
                              currentUrl.includes('zoom.us/start') ||
                              currentUrl.includes('zoom.us/meeting') ||
                              currentUrl.includes('zoom.us/my/');

        const cookies = await context.cookies().catch(() => []);
        // Real authenticated session tokens set by Zoom post-login
        const hasAuthSessionToken = cookies.some(c => 
          (c.name === 'cred' || c.name === '_zm_ssid' || c.name === 'zm_p' || c.name.includes('z_zoom_session')) && c.value && c.value.length > 5
        );

        if (isProfilePage || (!isOnSignin && hasAuthSessionToken && cookies.length >= 8)) {
          const sessionPath = getZoomSessionPath(targetUser);
          await context.storageState({ path: sessionPath });
          sessionSaved = true;

          const user = users.find(u => (u.email && u.email.toLowerCase() === targetUser.toLowerCase()) || (u.userId && u.userId.toLowerCase() === targetUser.toLowerCase()));
          if (user) {
            user.zoomSessionActive = true;
            if (zoomEmail) user.zoomEmail = zoomEmail;
            user.zoomSessionUpdatedAt = new Date().toISOString();
            saveUsers();
          }

          console.log(`[Zoom Auth] Verified logged-in Zoom account! Persistent storageState saved to ${sessionPath}`);
          return true;
        }
      } catch (e) {}
      return false;
    }

    await page.goto('https://zoom.us/signin', { waitUntil: 'domcontentloaded' }).catch(() => {});

    // Poll for up to 3 minutes for user to complete sign-in
    const maxWaitMs = 180000;
    const startTime = Date.now();

    while ((Date.now() - startTime) < maxWaitMs) {
      await new Promise(r => setTimeout(r, 2500));
      if (page.isClosed()) break;

      const isSuccess = await checkAndSaveSession();
      if (isSuccess) {
        // Wait 2 extra seconds so cookies finish writing completely before closing browser
        await new Promise(r => setTimeout(r, 2000));
        await browser.close().catch(() => {});
        break;
      }
    }

    if (sessionSaved) {
      return res.json({
        success: true,
        message: 'Successfully logged into Zoom account! Persistent session stored.',
        userId: targetUser
      });
    } else {
      // Try one final save before closing if browser is still alive
      if (!page.isClosed()) {
        await checkAndSaveSession();
        await browser.close().catch(() => {});
      }

      if (sessionSaved) {
        return res.json({
          success: true,
          message: 'Successfully logged into Zoom account! Persistent session stored.',
          userId: targetUser
        });
      }

      return res.status(408).json({
        success: false,
        message: 'Interactive sign-in was not completed within 3 minutes. Please try again or paste session JSON.'
      });
    }
  } catch (err) {
    console.error('[Zoom Auth] Interactive connect error:', err);
    res.status(500).json({ success: false, message: `Failed to launch sign-in browser: ${err.message}` });
  }
});

/**
 * POST /api/zoom/auth/logout
 * Clears saved Zoom session for user.
 */
app.post('/api/zoom/auth/logout', authenticate, (req, res) => {
  const { userId } = req.body;
  const targetUser = userId || 'default_user';
  const sessionPath = getZoomSessionPath(targetUser);

  if (fs.existsSync(sessionPath)) {
    try {
      fs.unlinkSync(sessionPath);
    } catch (e) {}
  }

  const user = users.find(u => (u.email && u.email.toLowerCase() === targetUser.toLowerCase()) || (u.userId && u.userId.toLowerCase() === targetUser.toLowerCase()));
  if (user) {
    user.zoomSessionActive = false;
    user.zoomSessionUpdatedAt = null;
    saveUsers();
  }

  console.log(`[Zoom Auth] Session logged out & removed for user: ${targetUser}`);
  res.json({ success: true, message: 'Zoom account session logged out permanently.' });
});

function buildZoomUrls(rawUrl, passcodeStr, botDisplayName = 'rycb') {
  let cleanUrl = (rawUrl || '').trim();
  let meetingId = '';
  let manualPasscode = (passcodeStr || '').trim();
  let tk = '';
  let name = (botDisplayName || 'rycb').trim();

  // Try advanced meeting parser first
  const parsed = meetingParser.parseMeetingInput(cleanUrl);
  if (parsed && parsed.success && parsed.meeting) {
    meetingId = parsed.meeting.meetingId;
    if (!manualPasscode && parsed.meeting.passcode) manualPasscode = parsed.meeting.passcode;
  }

  // Domain extraction (e.g. us05web.zoom.us, us06web.zoom.us, us02web.zoom.us, zoom.us)
  const domainMatch = cleanUrl.match(/https?:\/\/([^\/]+)/i);
  let domain = domainMatch ? domainMatch[1] : 'zoom.us';
  if (!domain.includes('zoom.us')) {
    domain = 'zoom.us';
  }

  // Match Meeting or Webinar ID
  const idMatch = cleanUrl.match(/\/(?:j|w|wc|wc\/join|s|meeting\/register|webinar\/register)\/(\d{9,11})/) ||
                  cleanUrl.match(/[?&]confno=(\d{9,11})/i) ||
                  cleanUrl.match(/\b\d{9,11}\b/) ||
                  cleanUrl.replace(/[\s-]/g, '').match(/\d{9,11}/);
  if (idMatch) {
    meetingId = idMatch[1] || idMatch[0];
  }

  let urlPwd = '';
  const pwdMatch = cleanUrl.match(/[?&](?:pwd|password)=([^&]+)/i);
  if (pwdMatch) {
    urlPwd = decodeURIComponent(pwdMatch[1]);
  }

  // If manual passcode wasn't explicitly given, check if urlPwd is a short plaintext passcode
  if (!manualPasscode && urlPwd && urlPwd.length <= 10 && !urlPwd.includes('.')) {
    manualPasscode = urlPwd;
  }

  // Effective password for query string: prefer the URL token if from full link, otherwise manual passcode
  const effectivePwd = urlPwd || manualPasscode;

  const tkMatch = cleanUrl.match(/[?&]tk=([^&]+)/i);
  if (tkMatch) {
    tk = decodeURIComponent(tkMatch[1]);
  }

  if (!meetingId) {
    return null;
  }

  const isWebinar = cleanUrl.includes('/w/') || cleanUrl.includes('webinar');
  const typePath = isWebinar ? 'w' : 'j';

  const queryParts = ['prefer=1'];
  if (effectivePwd) queryParts.push(`pwd=${encodeURIComponent(effectivePwd)}`);
  if (tk) queryParts.push(`tk=${encodeURIComponent(tk)}`);
  if (name) {
    queryParts.push(`un=${encodeURIComponent(name)}`);
    queryParts.push(`uname=${encodeURIComponent(name)}`);
    queryParts.push(`dn=${encodeURIComponent(name)}`);
  }
  const queryString = `?${queryParts.join('&')}`;

  const standardUrl = `https://${domain}/${typePath}/${meetingId}${queryString}`;
  const directWcUrl = `https://app.zoom.us/wc/join/${meetingId}${queryString}`;

  return { meetingId, pwd: effectivePwd, manualPasscode, urlPwd, tk, domain, isWebinar, standardUrl, directWcUrl };
}



function getDimensions(qualityStr, connectionType) {
  const q = (qualityStr || 'auto').toLowerCase();

  if (q === 'auto') {
    // Network-adaptive resolution: Mobile network uses minimum 480p/720p, WiFi maintains 1080p Full HD
    if (connectionType === 'mobile' || connectionType === 'cellular') {
      return { width: 854, height: 480 };
    }
    return { width: 1920, height: 1080 };
  }

  switch (q) {
    case '480p':
      return { width: 854, height: 480 };
    case '720p':
      return { width: 1280, height: 720 };
    case '4k':
      return { width: 3840, height: 2160 };
    case '1080p':
    default:
      return { width: 1920, height: 1080 };
  }
}



/**
 * GET /api/recordings/list
 * Returns list of all finalized meeting videos (.mp4, .mkv, .webm).
 */
app.get('/api/recordings/list', authenticate, (req, res) => {
  try {
    loadRecordingsMeta();

    const localFiles = fs.existsSync(RECORDINGS_DIR) 
      ? fs.readdirSync(RECORDINGS_DIR).filter(f => (f.endsWith('.mp4') || f.endsWith('.mkv') || f.endsWith('.webm')) && !f.startsWith('page@') && !f.endsWith('_raw.webm'))
      : [];

    // Filter out orphaned metadata entries where file no longer exists (keep processing entries intact)
    recordingsMeta = recordingsMeta.filter(m => {
      if (!m || !m.fileName) return false;
      if (m.processing) return true; // Do NOT delete provisional entries that are still converting!
      if (m.videoUrl && m.videoUrl.startsWith('/recordings/')) {
        const filePath = path.join(RECORDINGS_DIR, m.fileName);
        return fs.existsSync(filePath);
      }
      return true;
    });

    localFiles.forEach(file => {
      if (!recordingsMeta.some(m => m.fileName === file)) {
        const stats = fs.statSync(path.join(RECORDINGS_DIR, file));
        recordingsMeta.push({
          id: `rec_${file}`,
          meetingId: file.match(/\d{9,11}/)?.[0] || 'Meeting',
          botName: 'ZoomBot',
          fileName: file,
          sizeBytes: stats.size,
          sizeMb: (stats.size / (1024 * 1024)).toFixed(2) + ' MB',
          createdAt: stats.birthtime,
          status: 'RECORDED 1080P HD • LOCAL STORAGE',
          storageType: 'Local Storage',
          videoUrl: `/recordings/${file}`,
          videoSaved: true
        });
      }
    });

    saveRecordingsMeta();

    // /recordings is now auth-protected, and <video>/<a download> tags
    // can't send an Authorization header — so hand the frontend a URL with
    // the caller's own token attached, generated fresh per-request (never
    // persisted to recordings_meta.json).
    const token = extractToken(req);
    const recordingsForResponse = recordingsMeta.map(r => {
      if (!r.videoUrl || !r.videoUrl.startsWith('/recordings/')) return r;
      const sep = r.videoUrl.includes('?') ? '&' : '?';
      return { ...r, videoUrl: `${r.videoUrl}${sep}token=${encodeURIComponent(token)}` };
    });

    res.json({ success: true, count: recordingsForResponse.length, recordings: recordingsForResponse });
  } catch (err) {
    res.status(500).json({ error: 'Failed to list recordings from database' });
  }
});

const scheduledTasks = [];

/**
 * POST /api/bot/schedule-batch
 * Parses a CSV string (mylist.csv format) of scheduled Zoom meetings and schedules automated bot deployments.
 */
app.post('/api/bot/schedule-batch', authenticate, (req, res) => {
  const { csvText, userId, defaultBotName = 'Ai Participant Bot' } = req.body;

  if (!csvText || typeof csvText !== 'string') {
    return res.status(400).json({ success: false, message: 'Please provide CSV content to schedule meetings.' });
  }

  try {
    const lines = csvText.split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
    if (lines.length < 2) {
      return res.status(400).json({ success: false, message: 'CSV must contain a header row and at least one meeting entry.' });
    }

    const scheduled = [];
    // Skip header row
    for (let i = 1; i < lines.length; i++) {
      const parts = lines[i].split(',').map(p => p.trim());
      const rawUrl = parts[0] || '';
      const dateStr = parts[1] || '';
      const startTimeStr = parts[2] || '';
      const endTimeStr = parts[3] || '';

      if (!rawUrl || !startTimeStr) continue;

      const meetingData = buildZoomUrls(rawUrl, '', defaultBotName);
      if (!meetingData || !meetingData.meetingId) continue;
      const taskId = `sched_${Date.now()}_${i}`;

      const entry = {
        taskId,
        rawUrl,
        meetingId: meetingData.meetingId,
        passcode: meetingData.pwd,
        dateStr,
        startTimeStr,
        endTimeStr,
        botName: defaultBotName,
        userId: userId || 'default_user',
        status: 'SCHEDULED',
        createdAt: new Date().toISOString()
      };

      scheduledTasks.push(entry);
      scheduled.push(entry);
    }

    console.log(`[Batch Scheduler] Successfully parsed & scheduled ${scheduled.length} meetings from CSV!`);
    res.json({
      success: true,
      message: `Successfully scheduled ${scheduled.length} meeting(s) from CSV!`,
      scheduledCount: scheduled.length,
      tasks: scheduled
    });
  } catch (err) {
    console.error('[Batch Scheduler Error]:', err);
    res.status(500).json({ success: false, message: `Failed to process CSV schedule: ${err.message}` });
  }
});

/**
 * GET /api/bot/scheduled
 * Returns all active scheduled batch tasks.
 */
app.get('/api/bot/scheduled', authenticate, (req, res) => {
  res.json({ success: true, count: scheduledTasks.length, tasks: scheduledTasks });
});


/**
 * DELETE /api/recordings/:fileName
 * Deletes a recorded video file.
 */
app.delete('/api/recordings/:fileName', authenticate, (req, res) => {
  try {
    const fileName = req.params.fileName;
    const filePath = path.join(RECORDINGS_DIR, fileName);

    // 1. Delete physical file from server storage disk
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      console.log(`[Bot Engine] Deleted physical recording file: ${fileName}`);
    }

    // 2. Delete record directly from metadata database (recordings_meta.json)
    recordingsMeta = recordingsMeta.filter(r => r.fileName !== fileName && r.id !== fileName && !fileName.includes(r.id));
    saveRecordingsMeta();

    console.log(`[Database] Removed recording record ${fileName} from database.`);
    res.json({ success: true, message: `Recording ${fileName} deleted directly from database and storage.` });
  } catch (err) {
    console.error('[Delete Error]:', err);
    res.status(500).json({ error: 'Failed to delete recording from database' });
  }
});

/**
 * GET /api/bot/screenshot
 * Captures a LIVE screenshot of the bot's actual Playwright browser viewport.
 * Returns the image as JPEG binary for real-time streaming display.
 */
app.get('/api/bot/screenshot', authenticate, async (req, res) => {
  try {
    let activePage = null;
    let activeBot = null;
    for (const [id, bot] of activeBots) {
      if (bot.page && !bot.page.isClosed()) {
        activePage = bot.page;
        activeBot = bot;
        break;
      }
    }

    if (!activePage) {
      if (activeBots.size > 0) {
        const [id, bot] = [...activeBots.entries()][0];
        const realStatus = bot.status || 'JOINING';
        const statusColor = realStatus === 'ERROR' ? '#f87171' : realStatus === 'IN_MEETING' ? '#4ade80' : '#facc15';
        const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
          <rect width="640" height="360" fill="#0f111a"/>
          <circle cx="320" cy="160" r="45" fill="#38bdf8" opacity="0.8"/>
          <text x="320" y="167" font-family="sans-serif" font-size="28" fill="#0f111a" text-anchor="middle" font-weight="bold">${(bot.botName || 'R')[0].toUpperCase()}</text>
          <text x="320" y="240" font-family="sans-serif" font-size="18" fill="#f8fafc" text-anchor="middle" font-weight="600">${bot.botName || 'rycb'} (Zoom Participant)</text>
          <text x="320" y="270" font-family="sans-serif" font-size="14" fill="${statusColor}" text-anchor="middle">STATUS: ${realStatus} (Meeting ${bot.meetingId})</text>
        </svg>`;
        res.set({
          'Content-Type': 'image/svg+xml',
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        });
        return res.send(svg);
      }
      return res.status(404).json({ error: 'No active bot' });
    }

    const screenshotBuffer = await activePage.screenshot({
      type: 'jpeg',
      quality: 75,
      fullPage: false,
      timeout: 3000
    });

    res.set({
      'Content-Type': 'image/jpeg',
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Pragma': 'no-cache',
      'Expires': '0'
    });
    res.send(screenshotBuffer);

  } catch (err) {
    let activeBot = null;
    if (activeBots.size > 0) activeBot = [...activeBots.values()][0];
    const realStatus = activeBot ? activeBot.status : 'JOINING';
    const meetingId = activeBot ? activeBot.meetingId : '';
    const botName = activeBot ? activeBot.botName : 'rycb';
    const statusColor = realStatus === 'ERROR' ? '#f87171' : realStatus === 'IN_MEETING' ? '#4ade80' : '#facc15';
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
      <rect width="640" height="360" fill="#0f111a"/>
      <circle cx="320" cy="160" r="45" fill="#38bdf8" opacity="0.8"/>
      <text x="320" y="167" font-family="sans-serif" font-size="28" fill="#0f111a" text-anchor="middle" font-weight="bold">${(botName || 'R')[0].toUpperCase()}</text>
      <text x="320" y="240" font-family="sans-serif" font-size="18" fill="#f8fafc" text-anchor="middle" font-weight="600">${botName} (Zoom Participant)</text>
      <text x="320" y="270" font-family="sans-serif" font-size="14" fill="${statusColor}" text-anchor="middle">STATUS: ${realStatus} ${meetingId ? '(Meeting ' + meetingId + ')' : ''}</text>
    </svg>`;
    res.set({
      'Content-Type': 'image/svg+xml',
      'Cache-Control': 'no-cache, no-store, must-revalidate'
    });
    res.send(svg);
  }
});

/**
 * GET /api/bot/status
 * Returns the current status of the active bot (if any).
 */
app.get('/api/bot/status', authenticate, (req, res) => {
  if (activeBots.size === 0) {
    return res.json({ active: false, status: 'NO_ACTIVE_BOT' });
  }

  const [id, bot] = [...activeBots.entries()][0];
  let currentUrl = '';
  try {
    if (bot.page && !bot.page.isClosed()) {
      currentUrl = bot.page.url();
    }
  } catch (e) {}

  res.json({
    active: true,
    botId: id,
    status: bot.status,
    statusMessage: bot.statusMessage || null,
    botName: bot.botName,
    meetingId: bot.meetingId,
    quality: bot.quality,
    format: bot.format,
    startTime: bot.startTime,
    controlMode: bot.controlMode || 'BOT',
    controlOwner: bot.controlOwner || null,
    currentUrl,
    hasPage: !!(bot.page && !bot.page.isClosed())
  });
});

/**
 * POST /api/bot/takeover
 * Switches control mode between BOT (AI autonomous) and HUMAN (Manual Remote Control).
 */
app.post('/api/bot/takeover', authenticate, (req, res) => {
  if (activeBots.size === 0) {
    return res.status(404).json({ success: false, message: 'No active bot running' });
  }

  const { botId, mode } = req.body;
  const targetId = botId || [...activeBots.keys()][0];
  const bot = activeBots.get(targetId);
  if (!bot) {
    return res.status(404).json({ success: false, message: 'Specified bot not found' });
  }

  const targetMode = (mode && mode.toUpperCase() === 'HUMAN') ? 'HUMAN' : 'BOT';
  bot.controlMode = targetMode;
  bot.controlOwner = targetMode === 'HUMAN' ? ((req.user && req.user.email) || 'Human Operator') : null;

  console.log(`[Human Takeover] Control Mode changed to "${targetMode}" for bot ${targetId} (Owner: ${bot.controlOwner || 'AI Bot'})`);

  res.json({
    success: true,
    botId: targetId,
    controlMode: targetMode,
    controlOwner: bot.controlOwner,
    message: targetMode === 'HUMAN' ? 'Human Takeover Active — AI state machine clicks paused.' : 'AI Bot Control Restored — Autonomous state machine resumed.'
  });
});

/**
 * POST /api/bot/interact
 * Executes remote human interactive actions (click, type, press, scroll) on active bot browser page.
 */
app.post('/api/bot/interact', authenticate, async (req, res) => {
  if (activeBots.size === 0) {
    return res.status(404).json({ success: false, message: 'No active bot running' });
  }

  const { botId, action, x, y, text, key, deltaX, deltaY } = req.body;
  const targetId = botId || [...activeBots.keys()][0];
  const bot = activeBots.get(targetId);
  if (!bot || !bot.page || bot.page.isClosed()) {
    return res.status(404).json({ success: false, message: 'Active bot page unavailable' });
  }

  if (bot.controlMode !== 'HUMAN') {
    return res.status(403).json({ success: false, message: 'Human Takeover is not active. Click "Take Control" first.' });
  }

  try {
    const page = bot.page;
    if (action === 'click') {
      const clickX = Math.round(Number(x));
      const clickY = Math.round(Number(y));
      console.log(`[Human Takeover] Executing physical remote click at (${clickX}, ${clickY})`);
      await page.mouse.move(clickX, clickY, { steps: 2 }).catch(() => {});
      await page.mouse.click(clickX, clickY).catch(() => {});
    } else if (action === 'type' && text) {
      console.log(`[Human Takeover] Remote typing text: "${text}"`);
      await page.keyboard.type(text, { delay: 25 }).catch(() => {});
    } else if (action === 'press' && key) {
      console.log(`[Human Takeover] Remote pressing key: "${key}"`);
      if (key === ' ' || key === 'Spacebar') {
        await page.keyboard.press('Space').catch(() => {});
      } else if (key.length === 1) {
        await page.keyboard.type(key, { delay: 10 }).catch(() => {});
      } else {
        await page.keyboard.press(key).catch(() => {});
      }
    } else if (action === 'scroll') {
      const dX = Number(deltaX) || 0;
      const dY = Number(deltaY) || 100;
      await page.mouse.wheel(dX, dY).catch(() => {});
    }

    // Refresh screenshot buffer immediately
    await page.screenshot({ type: 'png' }).then(buf => { bot.lastScreenshotBuf = buf; }).catch(() => {});

    res.json({ success: true, actionExecuted: action, message: `Interactive action "${action}" executed cleanly` });
  } catch (err) {
    console.error('[Human Takeover] Action execution failed:', err.message);
    res.status(500).json({ success: false, message: `Failed to execute action: ${err.message}` });
  }
});

/**
 * POST /api/bot/deploy
 */
app.post('/api/bot/deploy', authenticate, async (req, res) => {
  const { meetingUrl, passcode, botName, videoQuality, videoFormat, connectionType, userId, durationMinutes, telegramChatId } = req.body;

  if (!meetingUrl) {
    return res.status(400).json({ success: false, message: 'Meeting URL or ID is required', error: 'Meeting URL or ID is required' });
  }

  const parsedUrls = buildZoomUrls(meetingUrl, passcode);
  if (!parsedUrls || !parsedUrls.meetingId) {
    return res.status(400).json({ success: false, message: 'Invalid Zoom meeting URL or ID. Please check the URL and try again.', error: 'Invalid Zoom meeting URL or ID' });
  }
  const { meetingId, pwd, tk, isWebinar, standardUrl, directWcUrl } = parsedUrls;
  const botId = `bot_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const displayName = botName || 'rycb';
  const targetQuality = videoQuality || 'auto';
  const targetFormat = (videoFormat || 'mp4').toLowerCase();
  const connType = connectionType || 'wifi';

  try {
    console.log(`[Bot Engine] Deploying Autonomous Bot "${displayName}" (ID: ${botId}) [Type: ${isWebinar ? 'WEBINAR' : 'MEETING'}, ID: ${meetingId}, Conn: ${connType}] to Zoom...`);

    const targetDurationMins = (durationMinutes && !isNaN(durationMinutes) && Number(durationMinutes) > 0) ? Number(durationMinutes) : 120;
    const exitMs = Math.round(targetDurationMins * 60 * 1000);

    activeBots.set(botId, {
      id: botId,
      userId: userId || null,
      telegramChatId: telegramChatId || null,
      meetingId,
      botName: displayName,
      quality: targetQuality,
      format: targetFormat,
      connectionType: connType,
      isWebinar,
      standardUrl,
      directWcUrl,
      durationMinutes: targetDurationMins,
      status: 'JOINING',
      controlMode: 'BOT',
      controlOwner: null,
      startTime: new Date().toISOString()
    });

    setTimeout(() => {
      launchZoomBotContainer(botId, standardUrl, directWcUrl, displayName, pwd, targetQuality, targetFormat, connType);
    }, 50);

    console.log(`[Bot Engine] Setting 2-hour long-duration session timer for bot ${botId} (${targetDurationMins} minutes)...`);
    setTimeout(() => {
      console.log(`[Bot Engine] Long-duration session timer expired (${targetDurationMins} mins) for bot ${botId}. Stopping bot and saving recording...`);
      stopBotAndSaveRecording(botId, targetFormat);
    }, exitMs);


    res.json({
      success: true,
      message: `Autonomous Bot "${displayName}" deployed to ${isWebinar ? 'Webinar' : 'Meeting'} ${meetingId}!`,
      botId,
      meetingId,
      joinUrl: standardUrl,
      quality: targetQuality,
      format: targetFormat,
      status: 'JOINING'
    });

  } catch (err) {
    console.error('[Bot Engine] Error deploying bot:', err);
    res.status(500).json({ success: false, message: `Failed to deploy Zoom bot: ${err.message}`, error: err.message });
  }
});

async function stopBotAndSaveRecording(botId, formatOverride = null) {
  let bot = activeBots.get(botId);

  // Fallback: If exact botId not found, use any currently active bot
  if (!bot && activeBots.size > 0) {
    bot = [...activeBots.values()][0];
  }

  const meetingId = bot ? bot.meetingId : 'Meeting';
  const botName = bot ? bot.botName : 'rycb';
  const targetExt = formatOverride || (bot && bot.format ? bot.format : 'mp4');
  const quality = (bot && bot.quality) ? bot.quality : '1080p';
  const botOwnerId = bot ? bot.userId : null;
  const telegramChatId = bot ? bot.telegramChatId : null;

  console.log(`[Bot Engine] Stopping bot ${bot ? bot.id : botId || 'session'} and saving ${quality} .${targetExt} video recording...`);

  const rawWebmName = `ZoomMeeting_${meetingId}_${Date.now()}_raw.webm`;
  const rawWebmPath = path.join(RECORDINGS_DIR, rawWebmName);
  let fileName = `ZoomMeeting_${meetingId}_${Date.now()}.${targetExt}`;
  let filePath = path.join(RECORDINGS_DIR, fileName);

  let rawVideoSaved = false;

  let rawAudioPath = (bot && bot.rawAudioPath) || path.join(RECORDINGS_DIR, `raw_audio_${bot ? bot.id : botId || 'session'}.webm`);
  if (bot) {
    bot.status = 'STOPPED';
    try {
      if (bot.audioWriteStream) {
        try { bot.audioWriteStream.end(); } catch (e) {}
      }
      if (bot.stopLoop) {
        bot.stopLoop();
      }
      if (bot.autonomousInterval) {
        clearInterval(bot.autonomousInterval);
      }

      if (bot.page) {
        const video = bot.page.video();
        await bot.page.close().catch(() => {});
        if (bot.context) await bot.context.close().catch(() => {});
        if (bot.browser) await bot.browser.close().catch(() => {});

        if (video) {
          try {
            await video.saveAs(rawWebmPath);
            rawVideoSaved = fs.existsSync(rawWebmPath) && fs.statSync(rawWebmPath).size > 0;
            console.log(`[Bot Engine] Raw webm capture saved: ${rawWebmPath} (${rawVideoSaved})`);
          } catch (saveErr) {
            try {
              const tempPath = await video.path().catch(() => null);
              if (tempPath && fs.existsSync(tempPath)) {
                fs.copyFileSync(tempPath, rawWebmPath);
                rawVideoSaved = fs.statSync(rawWebmPath).size > 0;
              }
            } catch (pathErr) {}
          }
        }
      } else {
        if (bot.context) await bot.context.close().catch(() => {});
        if (bot.browser) await bot.browser.close().catch(() => {});
      }
    } catch (e) {
      console.error('[Bot Engine] Error closing recording video:', e);
    }
    activeBots.delete(bot.id);
  }

  if (!rawVideoSaved) {
    try {
      const recFiles = fs.readdirSync(RECORDINGS_DIR).filter(f => f.endsWith('.webm'));
      const sorted = recFiles.map(f => {
        const fp = path.join(RECORDINGS_DIR, f);
        const st = fs.statSync(fp);
        return { name: f, path: fp, mtime: st.mtimeMs, size: st.size };
      }).filter(item => item.size > 0 && (Date.now() - item.mtime) < 10 * 60 * 1000)
        .sort((a, b) => b.mtime - a.mtime);

      if (sorted.length > 0) {
        fs.copyFileSync(sorted[0].path, rawWebmPath);
        rawVideoSaved = true;
        console.log(`[Bot Engine] Recovered recent webm capture: ${sorted[0].name} (${sorted[0].size} bytes)`);
      }
    } catch (recErr) {
      console.error('[Bot Engine] Error searching fallback video files:', recErr);
    }
  }

  if (!rawVideoSaved) {
    console.error(`[Bot Engine] No recording was captured for meeting ${meetingId} — reporting failure, not faking a video.`);
    return {
      success: false,
      error: 'RECORDING_FAILED',
      message: 'The bot did not capture any meeting video. No file was saved.'
    };
  }

  const processingId = `rec_${Date.now()}`;
  const provisionalEntry = {
    id: processingId,
    meetingId,
    botName,
    fileName,
    sizeBytes: 0,
    sizeMb: '—',
    createdAt: new Date().toISOString(),
    status: targetExt === 'webm'
      ? `RECORDED ${quality.toUpperCase()} HD • LOCAL STORAGE`
      : `PROCESSING ${quality.toUpperCase()} — converting to .${targetExt}...`,
    storageType: 'Local Storage',
    videoUrl: null,
    videoSaved: false,
    processing: targetExt !== 'webm'
  };
  recordingsMeta = recordingsMeta.filter(r => r.fileName !== fileName);
  recordingsMeta.unshift(provisionalEntry);
  saveRecordingsMeta();

  finishRecordingProcessing({
    rawWebmPath, rawAudioPath, filePath, fileName, targetExt, quality, meetingId, botName,
    processingId, botOwnerId, telegramChatId, alreadySavedAsWebm: targetExt === 'webm'
  }).catch(err => console.error('[Bot Engine] Background recording processing failed:', err));

  return {
    success: true,
    processing: targetExt !== 'webm',
    message: targetExt === 'webm'
      ? `Bot stopped and ${quality} .webm video recording saved.`
      : `Bot stopped. Recording captured — finishing ${quality} .${targetExt} conversion in background.`,
    fileName,
    videoUrl: targetExt === 'webm' ? `/recordings/${fileName}` : null,
    videoSaved: targetExt === 'webm',
    storageType: 'Local Storage'
  };
}

/**
 * POST /api/bot/stop
 * Stops bot, closes browser gracefully, converts/saves 1080p MP4/MKV video.
 */
app.post('/api/bot/stop', async (req, res) => {
  const botTarget = req.body.botId || req.body.meetingId;
  const result = await stopBotAndSaveRecording(botTarget);
  if (!result.success) {
    return res.status(500).json(result);
  }
  res.json(result);
});

// Finishes what used to happen inline in /api/bot/stop: transcodes the raw
// webm to the requested format (or just renames it for webm), then updates
// the recordingsMeta entry from "PROCESSING" to its final state. Runs after
// the HTTP response has already gone out, so ffmpeg time no longer blocks
// the UI.
async function finishRecordingProcessing({ rawWebmPath, rawAudioPath, filePath, fileName, targetExt, quality, meetingId, botName, processingId, botOwnerId, telegramChatId, alreadySavedAsWebm }) {
  let videoExists = false;
  let usedFallbackExt = false;

  if (alreadySavedAsWebm) {
    fs.renameSync(rawWebmPath, filePath);
    videoExists = fs.existsSync(filePath);
  } else {
    const transcodeOk = await convertVideoWithFFmpeg(rawWebmPath, rawAudioPath, filePath, targetExt);
    if (transcodeOk && fs.existsSync(filePath) && fs.statSync(filePath).size > 0) {
      videoExists = true;
      try { fs.unlinkSync(rawWebmPath); } catch (e) {}
      if (rawAudioPath && fs.existsSync(rawAudioPath)) {
        try { fs.unlinkSync(rawAudioPath); } catch (e) {}
      }
    } else {
      // Fallback if ffmpeg is missing or failed: copy raw webm capture directly to target filePath (.mp4)
      if (fs.existsSync(rawWebmPath) && fs.statSync(rawWebmPath).size > 0) {
        fs.copyFileSync(rawWebmPath, filePath);
        videoExists = fs.existsSync(filePath) && fs.statSync(filePath).size > 0;
        try { fs.unlinkSync(rawWebmPath); } catch (e) {}
        console.log(`[Bot Engine] Direct video container save used for ${fileName} (${fs.statSync(filePath).size} bytes).`);
      }
    }
  }

  const fileSize = videoExists ? fs.statSync(filePath).size : 0;
  console.log(`[Bot Engine] Final video file at ${filePath}: exists=${videoExists} size=${fileSize} bytes`);

  const entry = recordingsMeta.find(r => r.id === processingId || r.fileName === fileName);
  if (entry) {
    entry.fileName = fileName;
    entry.sizeBytes = fileSize;
    entry.sizeMb = (fileSize / (1024 * 1024)).toFixed(2) + ' MB';
    entry.status = usedFallbackExt
      ? `RECORDED ${quality.toUpperCase()} (.webm — mp4 conversion unavailable on server)`
      : `RECORDED ${quality.toUpperCase()} HD • LOCAL STORAGE`;
    entry.videoUrl = `/recordings/${fileName}`;
    entry.videoSaved = videoExists;
    entry.processing = false;
  }
  saveRecordingsMeta();

  if (videoExists) {
    backupRecordingToDriveIfConnected(fileName, filePath, botOwnerId).catch(() => {});

    if (telegramChatId) {
      notifyAndUploadRecording({ chatId: telegramChatId, fileName, filePath, sizeBytes: fileSize }).catch(e => {
        console.error('[Telegram Notify Error]', e);
      });
    }
  }
}

const DRIVE_CONFIGURED = () => !!(
  process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_ID !== 'your-google-client-id' &&
  process.env.GOOGLE_CLIENT_SECRET && process.env.GOOGLE_CLIENT_SECRET !== 'your-google-client-secret'
);

async function backupRecordingToDriveIfConnected(fileName, filePath, userId) {
  if (!driveService || !DRIVE_CONFIGURED()) return;

  const targetUser = userId || (users[0] && users[0].email);
  if (!targetUser) return;

  const user = users.find(u => u.email === targetUser || u.userId === targetUser);
  if (!user || !user.drive_refresh_token) return; // not connected — do nothing, do not fake it

  try {
    console.log(`[Drive] Backing up ${fileName} to Google Drive for ${targetUser}...`);
    const result = await driveService.uploadRecording(targetUser, filePath, fileName, 'video/mp4');

    const item = recordingsMeta.find(r => r.fileName === fileName);
    if (item) {
      item.driveFileId = result.driveFileId;
      item.driveWebViewLink = result.webViewLink;
      item.cloudStorage = 'Google Drive';
      item.status = `${item.status} • BACKED UP TO DRIVE`;
      saveRecordingsMeta();
    }
    console.log(`[Drive] Backup complete for ${fileName}: ${result.webViewLink}`);
  } catch (err) {
    console.error(`[Drive] Backup FAILED for ${fileName}:`, err.message);
    // Do not touch metadata on failure — a missing driveFileId already
    // truthfully communicates "not backed up" to the frontend.
  }
}

// Transcodes real Playwright video webm + captured digital audio webm into the final container.
function convertVideoWithFFmpeg(inputPath, audioPath, outputPath, format) {
  return new Promise((resolve) => {
    let ffmpegPath = 'ffmpeg';
    try {
      ffmpegPath = require('ffmpeg-static') || 'ffmpeg';
    } catch (e) {}

    const hasAudio = audioPath && fs.existsSync(audioPath) && fs.statSync(audioPath).size > 500;
    let args;
    if (hasAudio) {
      console.log(`[FFmpeg] Merging video with digital audio stream (${fs.statSync(audioPath).size} bytes audio) -> ${outputPath}`);
      args = format === 'mkv'
        ? ['-y', '-i', inputPath, '-i', audioPath, '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k', '-shortest', outputPath]
        : ['-y', '-i', inputPath, '-i', audioPath, '-c:v', 'libx264', '-preset', 'ultrafast', '-threads', '0', '-tune', 'zerolatency', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', '-shortest', outputPath];
    } else {
      console.log(`[FFmpeg] Encoding video with synthesized silent audio track -> ${outputPath}`);
      args = format === 'mkv'
        ? ['-y', '-i', inputPath, '-c', 'copy', outputPath]
        : ['-y', '-i', inputPath, '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000', '-c:v', 'libx264', '-preset', 'ultrafast', '-threads', '0', '-tune', 'zerolatency', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '128k', '-shortest', outputPath];
    }

    let ff;
    try {
      ff = spawn(ffmpegPath, args);
    } catch (e) {
      console.error('[FFmpeg Spawn Error]', e);
      return resolve(false);
    }
    ff.on('close', (code) => {
      resolve(code === 0 && fs.existsSync(outputPath) && fs.statSync(outputPath).size > 0);
    });
    ff.on('error', (err) => {
      console.error('[FFmpeg Execution Error]', err);
      resolve(false);
    });
  });
}

async function fillReactInput(page, selector, text) {
  try {
    const el = await page.$(selector);
    if (el) {
      await el.click().catch(() => {});
      await el.focus().catch(() => {});
      await page.keyboard.press('Control+A').catch(() => {});
      await page.keyboard.press('Backspace').catch(() => {});
      await page.keyboard.type(text, { delay: 40 }).catch(() => {});

      await page.evaluate(({ sel, val }) => {
        const input = document.querySelector(sel);
        if (input) {
          const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
          nativeSetter.call(input, val);
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }, { sel: selector, val: text }).catch(() => {});
    }
  } catch (e) {}
}

async function handleRecaptchaAndRegister(page, botId, firstName = 'Zoom', lastName = 'Bot', botEmail = 'zoom.participant.bot@gmail.com', directWcUrl = '') {
  try {
    // 1. Dynamic DOM Inspection & Intelligent Input Filling
    const inputsInfo = await page.evaluate(() => {
      const inputs = Array.from(document.querySelectorAll('input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="submit"])'));
      return inputs.map((input, idx) => {
        const id = (input.id || '').toLowerCase();
        const name = (input.name || '').toLowerCase();
        const placeholder = (input.placeholder || '').toLowerCase();
        const label = (input.getAttribute('aria-label') || '').toLowerCase();
        const autocomplete = (input.getAttribute('autocomplete') || '').toLowerCase();
        const val = input.value || '';
        return { idx, id, name, placeholder, label, autocomplete, val };
      });
    }).catch(() => []);

    for (const info of inputsInfo) {
      const { idx, id, name, placeholder, label, autocomplete, val } = info;
      const combinedStr = `${id} ${name} ${placeholder} ${label} ${autocomplete}`;

      let fillVal = null;
      if (combinedStr.includes('first') || combinedStr.includes('given')) {
        fillVal = firstName;
      } else if (combinedStr.includes('last') || combinedStr.includes('family') || combinedStr.includes('sur')) {
        fillVal = lastName;
      } else if (combinedStr.includes('email') || combinedStr.includes('mail')) {
        fillVal = botEmail;
      } else if (idx === 0 && !val) {
        fillVal = firstName;
      } else if (idx === 1 && !val) {
        fillVal = lastName;
      } else if (idx === 2 && !val) {
        fillVal = botEmail;
      }

      if (fillVal && (!val || !val.trim())) {
        console.log(`[Bot ${botId}] Auto-filling registration field #${idx + 1} (${id || name || 'input'}) -> "${fillVal}"`);
        const inputLocator = page.locator('input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="submit"])').nth(idx);
        await inputLocator.focus().catch(() => {});
        await inputLocator.fill(fillVal).catch(() => {});
        await page.waitForTimeout(100);
      }
    }

    // 2. Find reCAPTCHA iframe and handle checkbox / Image Challenge Modal cleanly
    const frames = page.frames();
    let hasImageChallenge = false;

    // Check if reCAPTCHA checkbox was already clicked on this page context
    const alreadyTicked = await page.evaluate(() => window._hasClickedRecaptchaCheckbox).catch(() => false);
    if (!alreadyTicked) {
      for (const frame of frames) {
        if (frame.url().includes('recaptcha')) {
          const checkbox = await frame.$('.recaptcha-checkbox-border, #recaptcha-anchor, div.recaptcha-checkbox');
          if (checkbox) {
            const ariaChecked = await checkbox.getAttribute('aria-checked').catch(() => 'false');
            if (ariaChecked !== 'true') {
              console.log(`[Bot ${botId}] Autonomous AI: Found reCAPTCHA checkbox! Ticking once with human physics...`);
              await page.evaluate(() => { window._hasClickedRecaptchaCheckbox = true; }).catch(() => {});
              
              const box = await checkbox.boundingBox().catch(() => null);
              if (box) {
                await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 10 }).catch(() => {});
                await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2).catch(() => {});
              } else {
                await checkbox.click({ force: true }).catch(() => {});
              }
              await page.waitForTimeout(1500);
              break;
            }
          }
        }
      }
    }

    // Detect if reCAPTCHA Image Challenge modal ("Select all images with a fire hydrant") is open
    for (const frame of frames) {
      if (frame.url().includes('bframe') || frame.url().includes('recaptcha')) {
        const isChallengeVisible = await frame.evaluate(() => {
          const modal = document.querySelector('#rc-imageselect, .rc-imageselect-instructions, .rc-imageselect-payload, [class*="rc-imageselect"]');
          return !!(modal && (modal.offsetWidth > 0 || modal.offsetHeight > 0));
        }).catch(() => false);

        if (isChallengeVisible) {
          hasImageChallenge = true;
          console.log(`[Bot ${botId}] Autonomous AI: reCAPTCHA Image Challenge popup detected in frame!`);

          // Attempt Audio Challenge button click
          const audioBtn = await frame.$('#recaptcha-audio-button, .rc-button-audio').catch(() => null);
          if (audioBtn) {
            console.log(`[Bot ${botId}] Autonomous AI: Clicking Audio Challenge fallback button...`);
            await audioBtn.click({ force: true }).catch(() => {});
            await page.waitForTimeout(1000);
          }
          break;
        }
      }
    }

    // 3. If reCAPTCHA Image Challenge modal is blocking registration, bypass directly to Zoom Web Client URL
    if (hasImageChallenge && directWcUrl) {
      console.log(`[Bot ${botId}] Autonomous AI: Image Challenge active — Bypassing CAPTCHA wall via Direct Web Client URL: ${directWcUrl}`);
      await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
      return;
    }

    const targetEmail = botEmail || process.env.ZOOM_BOT_EMAIL || 'hvlewvuwe@gmail.com';

    // 4. Fill all registration form fields (First Name, Last Name, Email, Confirm Email, Phone) via React native setters & Playwright locators
    const registrationFields = [
      {
        selectors: [
          '#first_name', '#firstname', '#inputfirstname', '#input-for-firstname',
          'input[name="first_name"]', 'input[name="firstname"]',
          'input[placeholder*="First Name"]', 'input[placeholder*="first name"]',
          'input[aria-label*="First Name"]', 'input[aria-label*="first name"]'
        ],
        val: firstName
      },
      {
        selectors: [
          '#last_name', '#lastname', '#inputlastname', '#input-for-lastname',
          'input[name="lastname"]', 'input[name="last_name"]',
          'input[placeholder*="Last Name"]', 'input[placeholder*="last name"]',
          'input[aria-label*="Last Name"]', 'input[aria-label*="last name"]'
        ],
        val: lastName
      },
      {
        selectors: [
          '#email', '#email_address', '#inputemail', '#input-for-email',
          'input[name="email"]', 'input[name="email_address"]', 'input[type="email"]',
          'input[placeholder*="Email"]', 'input[placeholder*="email"]', 'input[placeholder*="company.com"]',
          'input[aria-label*="Email"]'
        ],
        val: targetEmail
      },
      {
        selectors: [
          '#email2', '#confirm_email', '#confirm_email_address', '#inputemail2',
          'input[name="email2"]', 'input[name="confirm_email"]', 'input[name="confirm_email_address"]',
          'input[placeholder*="Confirm Email"]', 'input[placeholder*="confirm email"]',
          'input[aria-label*="Confirm Email"]'
        ],
        val: targetEmail
      },
      {
        selectors: [
          '#phone', '#inputphone', '#input-for-phone',
          'input[name="phone"]', 'input[type="tel"]', 'input[placeholder*="Phone"]'
        ],
        val: '9876543210'
      }
    ];

    // Dedicated physical keyboard typing for all email fields to clear Zoom's "This field is required" error
    try {
      const emailSelectors = [
        '#email', '#email_address', '#inputemail', '#input-for-email',
        '#email2', '#confirm_email', '#confirm_email_address',
        'input[type="email"]', 'input[name*="email"]',
        'input[placeholder*="company.com"]', 'input[placeholder*="Email"]'
      ];

      for (const sel of emailSelectors) {
        const emailEl = await page.$(sel).catch(() => null);
        if (emailEl) {
          await emailEl.click({ force: true }).catch(() => {});
          await emailEl.focus().catch(() => {});
          await page.keyboard.press('Control+A').catch(() => {});
          await page.keyboard.press('Backspace').catch(() => {});
          await page.keyboard.type(targetEmail, { delay: 30 }).catch(() => {});

          await page.evaluate((s, val) => {
            const el = document.querySelector(s);
            if (el) {
              const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
              setter.call(el, val);
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              el.dispatchEvent(new Event('blur', { bubbles: true }));
            }
          }, sel, targetEmail).catch(() => {});
        }
      }
    } catch (e) {}

    // Native Playwright field filling for remaining fields
    for (const item of registrationFields) {
      for (const sel of item.selectors) {
        const input = await page.$(sel).catch(() => null);
        if (input) {
          const val = await input.inputValue().catch(() => '');
          if (!val || val !== item.val) {
            await input.focus().catch(() => {});
            await input.fill(item.val).catch(() => {});
          }
        }
      }
    }

    // DOM React Event Dispatcher Fallback
    await page.evaluate(({ fields }) => {
      const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      fields.forEach(item => {
        item.selectors.forEach(sel => {
          const el = document.querySelector(sel);
          if (el && (!el.value || !el.value.trim())) {
            try {
              nativeSetter.call(el, item.val);
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              el.dispatchEvent(new Event('blur', { bubbles: true }));
            } catch(e) {}
          }
        });
      });
    }, { fields: registrationFields }).catch(() => {});

    // 5. Multi-Strategy "Register and Join" Button Click Execution
    const regBtnLoc = page.locator('button:has-text("Register and Join"), button:has-text("Register"), input[type="submit"][value*="Register"], button.btn-primary').first();
    const isRegVisible = await regBtnLoc.isVisible().catch(() => false);

    if (isRegVisible) {
      console.log(`[Bot ${botId}] Autonomous AI: Found 'Register and Join' button! Executing click...`);
      const box = await regBtnLoc.boundingBox().catch(() => null);
      if (box && box.width > 0 && box.height > 0) {
        await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2).catch(() => {});
      }
      await regBtnLoc.click({ force: true }).catch(() => {});
    }

    const clicked = await page.evaluate(() => {
      if (window._lastRegisterClickTime && (Date.now() - window._lastRegisterClickTime < 3000)) {
        return false;
      }
      const btns = Array.from(document.querySelectorAll('button, input[type="submit"], a.btn'));
      const regBtn = btns.find(b => {
        const txt = (b.textContent || b.value || '').trim().toLowerCase();
        const id = (b.id || '').toLowerCase();
        const cls = (b.className || '').toLowerCase();
        return (txt.includes('register') || txt.includes('join') || id === 'joinbtn' || cls.includes('register'));
      });
      if (regBtn) {
        window._lastRegisterClickTime = Date.now();
        regBtn.removeAttribute('disabled');
        regBtn.disabled = false;
        regBtn.click();
        const form = regBtn.closest('form');
        if (form) { try { form.requestSubmit(); } catch (e) { try { form.submit(); } catch (err) {} } }
        return true;
      }
      return false;
    }).catch(() => false);

    if (clicked || isRegVisible) {
      console.log(`[Bot ${botId}] Autonomous AI: Clicked "Register and Join" button cleanly! Waiting for Zoom meeting entry...`);
      await page.waitForTimeout(2000);
    }
  } catch (e) {}
}

async function handleGoogleSignInStep(page, botId, directWcUrl = '') {
  try {
    const url = page.url();
    if (url.includes('accounts.google.com') && directWcUrl) {
      console.log(`[Bot ${botId}] On accounts.google.com — Redirecting back to Zoom Web Client URL: ${directWcUrl}`);
      await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
    }
  } catch (e) {}
}

async function handleZoomSignInStep(page, botId, directWcUrl, userSessionPath, context) {
  try {
    const url = page.url();
    const isSigninUrl = url.includes('/signin') || url.includes('zoom.us/login');

    const botObj = activeBots.get(botId);
    const defaultAccountEmail = (botObj && botObj.zoomEmail) || process.env.ZOOM_ACCOUNT_EMAIL || '228a1a4255@risekrishnasaiprakasam.edu.in';
    const defaultAccountPass  = (botObj && botObj.zoomPassword) || process.env.ZOOM_ACCOUNT_PASSWORD || 'naniv401';

    // Step 0: Check for form inputs regardless of current URL
    const emailInput = await page.$('#email, input[type="email"], input[name="email"], #email-input, input[placeholder*="email" i], input[placeholder*="Email"]').catch(() => null);
    const passInput = await page.$('#password, input[type="password"], input[name="password"]').catch(() => null);

    // If neither URL indicates signin nor form fields exist on screen, search for inline "Sign In" link/button
    if (!isSigninUrl && !emailInput && !passInput) {
      const clickedInline = await page.evaluate(() => {
        const els = Array.from(document.querySelectorAll('a, button, span, div[role="button"]'));
        const signinEl = els.find(el => {
          const txt = (el.textContent || '').trim().toLowerCase();
          const href = (el.getAttribute('href') || '').toLowerCase();
          return (txt === 'sign in' || txt === 'signin' || txt.includes('sign in to join') || href.includes('/signin') || href.includes('zoom.us/login')) && el.offsetWidth > 0 && el.offsetHeight > 0;
        });
        if (signinEl) {
          signinEl.click();
          return true;
        }
        return false;
      }).catch(() => false);

      if (clickedInline) {
        console.log(`[Bot ${botId}] Clicked inline "Sign In" link — waiting for sign-in form...`);
        await page.waitForTimeout(1500);
        return true;
      }
      return false;
    }

    console.log(`[Bot ${botId}] Detected Zoom Sign-In form (${url}) — Processing authentication requirement...`);

    // Step 1: Handle Email input (2-step sign-in screen 1)
    if (emailInput && defaultAccountEmail) {
      const currentEmailVal = await emailInput.inputValue().catch(() => '');
      if (!currentEmailVal || currentEmailVal !== defaultAccountEmail) {
        console.log(`[Bot ${botId}] Auto-entering Zoom account email: ${defaultAccountEmail}`);
        await emailInput.focus().catch(() => {});
        await emailInput.fill(defaultAccountEmail).catch(() => {});
      }

      const nextBtn = await page.$('button:has-text("Next"), #btn-next, button.btn-primary').catch(() => null);
      if (nextBtn) {
        console.log(`[Bot ${botId}] Clicking 'Next' button on Zoom login step 1...`);
        await nextBtn.click({ force: true }).catch(() => {});
        await page.waitForTimeout(1500);
      }
    }

    // Step 2: Handle Password input (2-step sign-in screen 2)
    if (passInput && defaultAccountPass) {
      const currentPassVal = await passInput.inputValue().catch(() => '');
      if (!currentPassVal) {
        console.log(`[Bot ${botId}] Auto-entering Zoom account password...`);
        await passInput.focus().catch(() => {});
        await passInput.fill(defaultAccountPass).catch(() => {});
      }

      const signinBtn = await page.$('button:has-text("Sign In"), button.btn-primary, button[type="submit"]').catch(() => null);
      if (signinBtn) {
        console.log(`[Bot ${botId}] Clicking 'Sign In' button on Zoom login step 2...`);
        await signinBtn.click({ force: true }).catch(() => {});
        await page.waitForTimeout(2500);
        if (userSessionPath && context) {
          await context.storageState({ path: userSessionPath }).catch(() => {});
          console.log(`[Bot ${botId}] Saved authenticated Zoom session state to ${path.basename(userSessionPath)}.`);
        }
      }
    }

    // Step 3: If no account password set in env and no inputs active, auto-bypass sign-in by navigating directly to Web Client URL
    if (directWcUrl && !defaultAccountPass && !passInput && !emailInput) {
      console.log(`[Bot ${botId}] Bypassing Zoom login requirement — navigating directly to Web Client URL: ${directWcUrl}`);
      await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
      await page.waitForTimeout(1500);
    }
    return true;
  } catch (e) {
    return false;
  }
}

async function launchZoomBotContainer(botId, standardUrl, directWcUrl, displayName, pwd, qualityStr, formatStr, connectionType = 'wifi') {
  try {
    // Clean up any old active bot instances before spawning a new browser to prevent RAM accumulation
    for (const [existingId, existingBot] of activeBots) {
      if (existingId !== botId && existingBot.browser) {
        try {
          if (existingBot.page) await existingBot.page.close().catch(() => {});
          if (existingBot.context) await existingBot.context.close().catch(() => {});
          if (existingBot.browser) await existingBot.browser.close().catch(() => {});
        } catch (e) {}
        activeBots.delete(existingId);
      }
    }

    const dim = getDimensions(qualityStr, connectionType);

    console.log(`[Bot Container] Launching Autonomous Chromium Browser for "${displayName}" [Resolution: ${dim.width}x${dim.height}]...`);
    
    let browser;
    let lastLaunchError = null;
    const launchArgs = [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--disable-software-rasterizer',
      '--disable-blink-features=AutomationControlled',
      '--disable-features=AutomationControlled,Translate,BackForwardCache,AcceptCHFrame,MediaRouter,OptimizationHints',
      '--disable-background-networking',
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-breakpad',
      '--disable-component-extensions-with-background-pages',
      '--disable-extensions',
      '--disable-ipc-flooding-protection',
      '--disable-renderer-backgrounding',
      '--no-first-run',
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      '--allow-file-access-from-files',
      '--autoplay-policy=no-user-gesture-required',
      `--window-size=${dim.width},${dim.height}`
    ];

    // Find best browser executable on system (Supports Linux / Docker / Windows / macOS)
    const possiblePaths = [
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
      process.env.PUPPETEER_EXECUTABLE_PATH,
      process.env.CHROME_PATH,
      '/usr/bin/chromium',
      '/usr/bin/chromium-browser',
      '/usr/bin/google-chrome',
      '/usr/bin/google-chrome-stable',
      '/snap/bin/chromium',
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
      'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
    ].filter(Boolean);

    let systemBrowserPath = possiblePaths.find(p => fs.existsSync(p));

    // Try launching default Playwright Chromium first, then fallback to system paths
    try {
      browser = await chromium.launch({
        headless: true,
        args: launchArgs
      });
    } catch (defaultLaunchErr) {
      lastLaunchError = defaultLaunchErr.message;
      console.log(`[Bot ${botId}] Default Playwright launch failed (${defaultLaunchErr.message}), trying system executable...`);

      if (systemBrowserPath) {
        try {
          console.log(`[Bot ${botId}] Using system browser at: ${systemBrowserPath}`);
          browser = await chromium.launch({
            executablePath: systemBrowserPath,
            headless: true,
            args: launchArgs
          });
        } catch (sysErr) {
          lastLaunchError = sysErr.message;
          console.error(`[Bot ${botId}] System browser launch failed:`, sysErr.message);
        }
      }

      if (!browser) {
        for (const fallbackPath of ['/usr/bin/chromium', '/usr/bin/google-chrome', '/usr/bin/chromium-browser']) {
          if (fs.existsSync(fallbackPath)) {
            try {
              browser = await chromium.launch({ executablePath: fallbackPath, headless: true, args: launchArgs });
              if (browser) break;
            } catch (fbErr) {
              lastLaunchError = fbErr.message;
            }
          }
        }
      }

      if (!browser) {
        try {
          browser = await chromium.launch({ channel: 'chrome', headless: true, args: launchArgs });
        } catch (e2) {
          try {
            browser = await chromium.launch({ channel: 'msedge', headless: true, args: launchArgs });
          } catch (e3) {
            lastLaunchError = e3.message;
          }
        }
      }
    }

    if (!browser) {
      console.error(`[Bot ${botId}] Browser instance unavailable on server host. Last error: ${lastLaunchError}`);
      const bot = activeBots.get(botId);
      if (bot) {
        bot.status = 'ERROR';
        bot.statusMessage = `Browser unavailable: ${lastLaunchError || 'Could not launch Chromium'}`;
        bot.error = lastLaunchError;
      }
      return;
    }

    const currentBotObj = activeBots.get(botId);
    let botUserId = (currentBotObj && currentBotObj.userId) || 'default_user';
    let userSessionPath = getZoomSessionPath(botUserId);
    let hasPersistentSession = fs.existsSync(userSessionPath);

    // Fallback: If exact userId session file doesn't exist, search SESSIONS_DIR for any active Zoom session file
    if (!hasPersistentSession && fs.existsSync(SESSIONS_DIR)) {
      try {
        const sessionFiles = fs.readdirSync(SESSIONS_DIR).filter(f => f.startsWith('zoom_session_') && f.endsWith('.json'));
        if (sessionFiles.length > 0) {
          userSessionPath = path.join(SESSIONS_DIR, sessionFiles[0]);
          hasPersistentSession = fs.existsSync(userSessionPath) && fs.statSync(userSessionPath).size > 10;
          if (hasPersistentSession) {
            console.log(`[Bot ${botId}] Found active Zoom session fallback file: ${sessionFiles[0]}`);
          }
        }
      } catch (e) {}
    }

    if (hasPersistentSession) {
      console.log(`[Bot ${botId}] Persistent Authenticated Zoom session active! Loading storageState from ${path.basename(userSessionPath)}...`);
    }

    const contextOptions = {
      viewport: { width: dim.width, height: dim.height },
      recordVideo: {
        dir: RECORDINGS_DIR,
        size: { width: dim.width, height: dim.height }
      },
      permissions: ['microphone', 'camera'],
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
    };

    if (hasPersistentSession) {
      contextOptions.storageState = userSessionPath;
    }

    let context = await browser.newContext(contextOptions);

    // Anti-webdriver evasion & Preset Zoom display name in Storage & Cookies before page loads
    await context.addInitScript((name) => {
      try {
        // Core webdriver flag removal
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        delete window.cdc_adoQpoasndfma76_Array;
        delete window.cdc_adoQpoasndfma76_Promise;
        delete window.cdc_adoQpoasndfma76_Symbol;

        // Realistic navigator.plugins (PluginArray-like with actual entries)
        const makePlugin = (name, desc, filename) => ({ name, description: desc, filename, length: 1, item: () => null, namedItem: () => null });
        const fakePlugins = [
          makePlugin('Chrome PDF Plugin', 'Portable Document Format', 'internal-pdf-viewer'),
          makePlugin('Chrome PDF Viewer', '', 'mhjfbmdgcfjbbpaeojofohoefgiehjai'),
          makePlugin('Native Client', '', 'internal-nacl-plugin')
        ];
        fakePlugins.item = (i) => fakePlugins[i] || null;
        fakePlugins.namedItem = (n) => fakePlugins.find(p => p.name === n) || null;
        fakePlugins.refresh = () => {};
        Object.defineProperty(navigator, 'plugins', { get: () => fakePlugins });
        Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
        Object.defineProperty(navigator, 'mimeTypes', { get: () => ({ length: 2, item: () => null, namedItem: () => null }) });

        // Chrome runtime stubs (headless Chromium lacks these)
        window.chrome = {
          runtime: {},
          csi: () => ({ startE: Date.now(), onloadT: Date.now() + 100, pageT: 300, tran: 15 }),
          loadTimes: () => ({ commitLoadTime: Date.now() / 1000, connectionInfo: 'h2', finishDocumentLoadTime: Date.now() / 1000 + 0.1, finishLoadTime: Date.now() / 1000 + 0.2, firstPaintAfterLoadTime: 0, firstPaintTime: Date.now() / 1000 + 0.05, navigationType: 'Other', npnNegotiatedProtocol: 'h2', requestTime: Date.now() / 1000 - 0.3, startLoadTime: Date.now() / 1000 - 0.2, wasAlternateProtocolAvailable: false, wasFetchedViaSpdy: true, wasNpnNegotiated: true })
        };

        // Permissions API override (headless returns 'denied' for notifications)
        if (navigator.permissions) {
          const origQuery = navigator.permissions.query.bind(navigator.permissions);
          navigator.permissions.query = (params) => {
            if (params.name === 'notifications') return Promise.resolve({ state: 'prompt', onchange: null });
            return origQuery(params);
          };
        }

        // WebGL renderer/vendor spoofing
        const getParameterOrig = WebGLRenderingContext.prototype.getParameter;
        WebGLRenderingContext.prototype.getParameter = function(param) {
          if (param === 37445) return 'Google Inc. (NVIDIA)';
          if (param === 37446) return 'ANGLE (NVIDIA, NVIDIA GeForce GTX 1050 Direct3D11 vs_5_0 ps_5_0, D3D11)';
          return getParameterOrig.call(this, param);
        };

        // Zoom display name & AV presets
        localStorage.setItem('zm_display_name', name);
        localStorage.setItem('display_name', name);
        localStorage.setItem('zm_camera_off', 'true');
        localStorage.setItem('zm_mic_off', 'true');
        localStorage.setItem('zm_video_mute', 'true');
        localStorage.setItem('preview_video_off', 'true');
        localStorage.setItem('preview_audio_off', 'true');
        sessionStorage.setItem('zm_display_name', name);
        sessionStorage.setItem('display_name', name);
        sessionStorage.setItem('zm_camera_off', 'true');
        sessionStorage.setItem('zm_mic_off', 'true');
      } catch (e) {}
    }, displayName);

    // ── In-Page Digital Audio Tap for Chromium ───────────────────────────
    const rawAudioPath = path.join(RECORDINGS_DIR, `raw_audio_${botId}.webm`);
    const audioWriteStream = fs.createWriteStream(rawAudioPath, { flags: 'a' });

    await context.addInitScript(() => {
      window.__setupAudioTap = function() {
        if (window.__audioTapRunning) return;
        try {
          const AudioCtx = window.AudioContext || window.webkitAudioContext;
          if (!AudioCtx) return;
          const ctx = new AudioCtx();
          const dest = ctx.createMediaStreamDestination();
          window.__audioTapDest = dest;
          window.__audioTapCtx = ctx;

          // 1. Intercept all <audio> and <video> elements
          const origPlay = HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play = function() {
            try {
              if (!this.__tapped && dest && ctx) {
                this.__tapped = true;
                const src = ctx.createMediaElementSource(this);
                src.connect(dest);
                src.connect(ctx.destination);
              }
            } catch (err) {}
            return origPlay.apply(this, arguments);
          };

          // 2. Intercept incoming WebRTC remote audio tracks
          if (window.RTCPeerConnection) {
            const origSetRemote = RTCPeerConnection.prototype.setRemoteDescription;
            RTCPeerConnection.prototype.setRemoteDescription = function() {
              this.addEventListener('track', (e) => {
                try {
                  if (e.track && e.track.kind === 'audio' && ctx && dest) {
                    const stream = e.streams && e.streams[0] ? e.streams[0] : new MediaStream([e.track]);
                    const src = ctx.createMediaStreamSource(stream);
                    src.connect(dest);
                  }
                } catch (e) {}
              });
              return origSetRemote.apply(this, arguments);
            };
          }

          // 3. Start MediaRecorder on the destination stream
          if (dest && dest.stream) {
            const rec = new MediaRecorder(dest.stream, { mimeType: 'audio/webm;codecs=opus' });
            rec.ondataavailable = async (ev) => {
              if (ev.data && ev.data.size > 0 && window.onZoomAudioChunk) {
                const reader = new FileReader();
                reader.onloadend = () => {
                  const b64 = (reader.result || '').split(',')[1];
                  if (b64) window.onZoomAudioChunk(b64);
                };
                reader.readAsDataURL(ev.data);
              }
            };
            rec.start(1000);
            window.__audioTapRunning = true;
          }
        } catch (e) {}
      };

      window.addEventListener('DOMContentLoaded', () => window.__setupAudioTap());
      setTimeout(() => window.__setupAudioTap(), 1000);
    });

    const page = await context.newPage();
    await page.bringToFront().catch(() => {});

    await page.exposeFunction('onZoomAudioChunk', (base64Chunk) => {
      try {
        if (base64Chunk && audioWriteStream && !audioWriteStream.destroyed) {
          audioWriteStream.write(Buffer.from(base64Chunk, 'base64'));
        }
      } catch (e) {}
    }).catch(() => {});

    const botInfo = activeBots.get(botId);
    if (botInfo) {
      botInfo.browser = browser;
      botInfo.context = context;
      botInfo.page = page;
      botInfo.rawAudioPath = rawAudioPath;
      botInfo.audioWriteStream = audioWriteStream;
    }

    const hasFullInviteToken = standardUrl && standardUrl.includes('pwd=');
    const primaryNavUrl = hasFullInviteToken ? standardUrl : directWcUrl;
    const secondaryNavUrl = hasFullInviteToken ? directWcUrl : standardUrl;

    console.log(`[Bot ${botId}] Navigating to Zoom meeting URL (${hasFullInviteToken ? 'Invite Link with Token' : 'Direct WC'}): ${primaryNavUrl}`);
    await page.goto(primaryNavUrl, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(async () => {
      console.log(`[Bot ${botId}] Fallback to secondary Zoom URL: ${secondaryNavUrl}`);
      await page.goto(secondaryNavUrl, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    });

    await page.waitForTimeout(1500);

    // Identity data
    const nameParts = displayName.trim().split(/\s+/);
    const firstName = nameParts[0] || 'Zoom';
    const lastName = nameParts.slice(1).join(' ') || 'Bot';
    const botEmail = process.env.ZOOM_BOT_EMAIL || 'hvlewvuwe@gmail.com';
    const botPhone = '9876543210';

    const nameSelectors = [
      '#inputname',
      '#input-for-name',
      'input[name="inputname"]',
      'input[name="display_name"]',
      '#inputfirstname',
      '#input-for-firstname',
      'input[placeholder*="Name"]',
      'input[placeholder*="name"]'
    ];

    const passSelectors = [
      '#inputpasscode',
      '#input-for-pwd',
      'input[name="inputpasscode"]',
      'input[placeholder*="Passcode"]',
      'input[placeholder*="passcode"]'
    ];

    // AUTONOMOUS SEQUENTIAL STATE MACHINE LOOP
    let loopRunning = true;
    let loopTimeout = null;
    let lastNavRecoveryTime = 0; // Crash recovery cooldown

    async function autonomousLoop() {
      if (!loopRunning) return;
      try {
        if (page.isClosed()) {
          loopRunning = false;
          return;
        }

        const currentUrl = page.url();

        // Fix #3: Navigation Crash Recovery — detect blank/error pages and re-navigate
        if ((currentUrl === 'about:blank' || currentUrl.startsWith('chrome-error://') || currentUrl === '') && directWcUrl) {
          const now = Date.now();
          if (now - lastNavRecoveryTime > 5000) {
            lastNavRecoveryTime = now;
            console.log(`[Bot ${botId}] Dead/blank page detected (${currentUrl || 'empty'}) — Auto-recovering to: ${directWcUrl}`);
            await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
            await page.waitForTimeout(2000);
          }
          return;
        }

        const isOnWebClient = currentUrl.includes('/wc/');
        const isOnLandingPage = !isOnWebClient && !currentUrl.includes('/meeting/register') && (currentUrl.includes('/j/') || currentUrl.includes('/w/') || currentUrl.includes('/s/'));

        // Auto-detect offsite OAuth redirects (facebook.com, google.com, apple.com) during meeting join
        if ((currentUrl.includes('facebook.com') || currentUrl.includes('accounts.google.com') || currentUrl.includes('apple.com')) && directWcUrl) {
          console.log(`[Bot ${botId}] Off-site OAuth redirect detected (${currentUrl}) — Redirecting back to Zoom Web Client URL: ${directWcUrl}`);
          await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
          await page.waitForTimeout(2000);
          return;
        }

        // Auto-detect reCAPTCHA IP Block ("Try again later / sending automated queries")
        const isRecaptchaBlocked = await page.evaluate(() => {
          const text = (document.body.innerText || '').toLowerCase();
          return text.includes('try again later') || text.includes('automated queries') || text.includes("can't process your request");
        }).catch(() => false);

        if (isRecaptchaBlocked && !isOnWebClient && directWcUrl) {
          console.log(`[Bot ${botId}] reCAPTCHA Block detected ("Try again later") — Auto-bypassing via Direct Web Client URL: ${directWcUrl}`);
          await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
          await page.waitForTimeout(2000);
          return;
        }

        // Declare at function scope so all downstream steps can reference safely
        const botRef = activeBots.get(botId);
        if (!botRef) {
          // Bot was removed (e.g. stopped by another request) — exit loop cleanly
          loopRunning = false;
          return;
        }

        if (botRef.controlMode === 'HUMAN') {
          botRef.status = 'HUMAN_TAKEOVER';
          botRef.statusMessage = 'HUMAN TAKEOVER ACTIVE — Remote Control Operating';
          // Still refresh screenshot for live monitor feed
          await page.screenshot({ type: 'png' }).then(buf => { botRef.lastScreenshotBuf = buf; }).catch(() => {});
          return;
        }

        let pageState = { hasMeetingUI: false, inWaitingRoom: false, hasCaptchaText: false, needsRegistration: false, needsAuth: false, hasError: false };

        // --- DOM State Evaluation ---
        pageState = await page.evaluate(() => {
            const text = (document.body.innerText || '').toLowerCase();

            // 1. Verification of Active Meeting UI (must NOT be on preview screen)
            const isPreviewScreen = !!document.querySelector(
              'button.preview-join-button, button#joinBtn, #inputname, input[name="inputname"], input[name="inputpasscode"]'
            );

            const hasLeaveBtn = !!Array.from(document.querySelectorAll('button, a')).find(el => {
              const t = (el.textContent || '').trim().toLowerCase();
              const aria = (el.getAttribute('aria-label') || '').toLowerCase();
              return t === 'leave' || t === 'leave meeting' || t === 'end' || aria.includes('leave meeting') || aria.includes('leave');
            });

            const hasMeetingUI = !isPreviewScreen && (
              hasLeaveBtn ||
              !!document.querySelector('.footer__leave-btn, [aria-label*="leave meeting"], .participants-header, .speaker-bar, .sharer-controlbar, #wc-footer, .meeting-client, #meeting-app, #full-screen-video, .video-avatar__avatar-name') ||
              text.includes('leave meeting') ||
              text.includes('recording in progress') ||
              text.includes('mute my audio') ||
              text.includes('start video') ||
              text.includes('stop video') ||
              (text.includes('participants') && !text.includes('enter meeting id'))
            );

            // 2. Verification of Waiting Room
            const inWaitingRoom = text.includes('waiting room') || text.includes('will let you in') || text.includes('please wait');

            // 3. Verification of CAPTCHA challenge (text-only signals from main document;
            //    cross-origin iframe detection is handled below via page.frames())
            const hasCaptchaText = text.includes("select all images with") ||
                               text.includes("solve the puzzle");

            // 4. Verification of Registration Required
            const needsRegistration = (text.includes('register for this meeting') || text.includes('meeting registration')) &&
                                      !hasMeetingUI;

            // 4.5 Verification of Passcode Required
            const passEl = document.querySelector('#inputpasscode, input[name="inputpasscode"], input[placeholder*="passcode"]');
            const needsPasscode = !!passEl && !(passEl.value || '').trim();

            // 5. Verification of Authentication / Sign-in Restriction
            const needsAuth = (text.includes('sign in to join') ||
                              text.includes('only authenticated users can join') ||
                              text.includes('requires authentication') ||
                              text.includes("couldn't sign you in") ||
                              text.includes('sign in to zoom') ||
                              text.includes('log in to join') ||
                              text.includes('login to join') ||
                              text.includes('sign in required')) &&
                              !hasMeetingUI;

            // 6. Verification of Error / Meeting Unavailable
            const isBotBlocked = text.includes("automated bots aren't allowed") ||
                                 text.includes("bots aren't allowed") ||
                                 text.includes("browser or app may not be secure");

            const hasError = text.includes('invalid meeting') ||
                             text.includes('meeting has ended') ||
                             text.includes('this meeting could not be found') ||
                             isBotBlocked;

            return { hasMeetingUI, inWaitingRoom, hasCaptchaText, needsRegistration, needsPasscode, needsAuth, hasError, isBotBlocked };
          }).catch(() => ({ hasMeetingUI: false, inWaitingRoom: false, hasCaptchaText: false, needsRegistration: false, needsPasscode: false, needsAuth: false, hasError: false, isBotBlocked: false }));

          // Frame-based reCAPTCHA detection (correct cross-origin approach, same as handleRecaptchaAndRegister)
          let hasRecaptchaFrame = false;
          try {
            for (const frame of page.frames()) {
              const frameUrl = frame.url();
              if (frameUrl.includes('recaptcha')) {
                // Check for unchecked checkbox inside the anchor iframe
                if (frameUrl.includes('anchor')) {
                  const unchecked = await frame.$('.recaptcha-checkbox-border, #recaptcha-anchor[aria-checked="false"], div.recaptcha-checkbox').catch(() => null);
                  if (unchecked) {
                    const ariaChecked = await unchecked.getAttribute('aria-checked').catch(() => 'false');
                    if (ariaChecked !== 'true') {
                      hasRecaptchaFrame = true;
                      break;
                    }
                  }
                }
                // Check for image challenge (bframe)
                if (frameUrl.includes('bframe')) {
                  hasRecaptchaFrame = true;
                  break;
                }
              }
            }
          } catch (e) {}

          const hasCaptcha = pageState.hasCaptchaText || hasRecaptchaFrame;
          // Merge into a unified pageState for downstream use
          pageState.hasCaptcha = hasCaptcha;

          let nextStatus = botRef.status;
          if (pageState.isBotBlocked) {
            console.warn(`[Bot ${botId}] Zoom Bot-Detection Blocked ("Automated bots aren't allowed"). Stopping retries immediately.`);
            botRef.statusMessage = "Zoom blocked automated bot joining. Try interactive sign-in or manual session import.";
            nextStatus = 'ERROR';
            loopRunning = false;
          } else if (pageState.hasError) {
            const sessionAgeMins = (Date.now() - new Date(botRef.startTime).getTime()) / (60 * 1000);
            const targetDurationMins = botRef.durationMinutes || 120;

            if (sessionAgeMins < targetDurationMins && (botRef._rejoinCount || 0) < 5) {
              botRef._rejoinCount = (botRef._rejoinCount || 0) + 1;
              console.log(`[Bot ${botId}] Meeting disconnect / 40-min free tier limit detected (${sessionAgeMins.toFixed(1)} mins in). Auto-rejoining in 5s (Attempt ${botRef._rejoinCount}/5)...`);
              botRef.status = 'REJOINING';
              await page.waitForTimeout(5000);
              await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
              return;
            } else {
              nextStatus = 'ERROR';
            }
          } else if (pageState.hasMeetingUI) {
            botRef.authStartTime = null;
            botRef.authAttempts = 0;
            nextStatus = 'IN_MEETING';
          } else if (pageState.inWaitingRoom) {
            botRef.authStartTime = null;
            botRef.authAttempts = 0;
            nextStatus = 'IN_WAITING_ROOM';
          } else if (pageState.needsAuth) {
            botRef.authStartTime = botRef.authStartTime || Date.now();
            const elapsedMs = Date.now() - botRef.authStartTime;
            nextStatus = 'AUTHENTICATION_REQUIRED';
            if (elapsedMs > 45000) {
              console.warn(`[Bot ${botId}] Zoom session expired — sign-in window timed out (${(elapsedMs / 1000).toFixed(1)}s).`);
              botRef.statusMessage = 'Zoom session expired — reconnect via Settings or sign in to join';
              loopRunning = false;
            }
          } else {
            botRef.authStartTime = null;
            botRef.authAttempts = 0;
            if (hasCaptcha) {
              nextStatus = 'CAPTCHA_REQUIRED';
            } else if (pageState.needsRegistration) {
              nextStatus = 'REGISTRATION_REQUIRED';
            } else if (pageState.needsPasscode && !pwd) {
              nextStatus = 'PASSCODE_REQUIRED';
              botRef.statusMessage = 'Meeting requires a passcode. Please enter the passcode or use full invite link.';
            } else {
              nextStatus = 'JOINING';
            }
          }

          if (botRef.status !== nextStatus) {
            botRef._lastStateChangeTime = Date.now();
            botRef.status = nextStatus;
            console.log(`[Bot ${botId}] Join Engine State Machine -> ${nextStatus} (DOM-verified)`);
          }

          // Fix #4: Stale Page / Frozen Tab Recovery — if stuck for 60s while not in meeting/waiting room, force reload
          if (!botRef._lastStateChangeTime) botRef._lastStateChangeTime = Date.now();
          const stuckMs = Date.now() - botRef._lastStateChangeTime;
          if (stuckMs > 60000 && nextStatus !== 'IN_MEETING' && nextStatus !== 'IN_WAITING_ROOM' && nextStatus !== 'PASSCODE_REQUIRED' && nextStatus !== 'ERROR') {
            console.warn(`[Bot ${botId}] State machine stuck in ${nextStatus} for ${(stuckMs / 1000).toFixed(0)}s — forcing page reload.`);
            botRef._lastStateChangeTime = Date.now();
            await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
            await page.waitForTimeout(2000);
            return;
          }

          // Fix #6: Waiting Room Persistence — skip all action steps, only refresh screenshot
          if (pageState.inWaitingRoom) {
            await page.screenshot({ type: 'png' }).then(buf => { botRef.lastScreenshotBuf = buf; }).catch(() => {});
            return;
          }

        // STEP 0: Dismiss cookie consent banners ONLY (not generic modals/dialogs)
        await page.evaluate(() => {
          const cookieEls = document.querySelectorAll(
            '#onetrust-banner-sdk, .onetrust-pc-dark-filter, #onetrust-consent-sdk, [id*="cookie-banner"], [class*="cookie-banner"], [class*="cookie-consent"]'
          );
          cookieEls.forEach(el => {
            try { el.remove(); } catch (e) {}
          });
          const acceptBtn = document.querySelector('#onetrust-accept-btn-handler');
          if (acceptBtn) { try { acceptBtn.click(); } catch (e) {} }
        }).catch(() => {});

        // STEP 0.5: Guarded Auto-Recovery for Stale Sessions when Zoom requires Authentication
        if (pageState.needsAuth || page.url().includes('/signin') || page.url().includes('zoom.us/login')) {
          const handledSignIn = await handleZoomSignInStep(page, botId, directWcUrl, userSessionPath, context).catch(() => false);
          if (handledSignIn) {
            await page.waitForTimeout(1000);
            return;
          }
        }



        // STEP 1: On landing page — click "Launch Meeting" and "Join from browser" link
        if (isOnLandingPage) {
          // If Zoom shows "Launch Meeting", click it to trigger the "Join from your browser" link
          const launchBtn = await page.$(
            'button:has-text("Launch Meeting"), #btn-download, a:has-text("Launch Meeting"), button.btn-primary'
          ).catch(() => null);
          if (launchBtn) {
            await launchBtn.click().catch(() => {});
            await page.waitForTimeout(600);
          }

          // Try native Playwright click on "Join from your browser"
          const joinBrowserLink = await page.$(
            'a[href*="/wc/"], a:has-text("Join from Your Browser"), a:has-text("Join from your browser"), a:has-text("Join from browser")'
          ).catch(() => null);

          if (joinBrowserLink) {
            console.log(`[Bot ${botId}] Found 'Join from browser' link, clicking...`);
            await joinBrowserLink.click({ force: true }).catch(() => {});
            await page.waitForTimeout(1000);
          } else {
            // DOM fallback: find and click any link/button with matching text
            const clicked = await page.evaluate(() => {
              const all = Array.from(document.querySelectorAll('a, button, span'));
              const btn = all.find(el => {
                const txt = (el.textContent || '').trim().toLowerCase();
                const href = (el.getAttribute('href') || '').toLowerCase();
                return (txt.includes('join from') && txt.includes('browser')) || href.includes('/wc/');
              });
              if (btn) { btn.click(); return true; }
              return false;
            }).catch(() => false);

            if (!clicked) {
              // Last resort: navigate directly to /wc/join/ URL
              console.log(`[Bot ${botId}] No 'Join from browser' found, navigating to WC URL: ${directWcUrl}`);
              await page.goto(directWcUrl, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
              await page.waitForTimeout(2000);
            }
          }
        }

        // STEP 2: Fill name inputs with React state event dispatchers
        for (const sel of nameSelectors) {
          const input = await page.$(sel).catch(() => null);
          if (input) {
            await input.click({ force: true }).catch(() => {});
            await input.focus().catch(() => {});
            await input.fill(displayName).catch(() => {});

            await page.evaluate(({ s, name }) => {
              const el = document.querySelector(s);
              if (el) {
                const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                nativeSetter.call(el, name);
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new Event('blur', { bubbles: true }));
              }
            }, { s: sel, name: displayName }).catch(() => {});
          }
        }

        // STEP 3: Fill passcode inputs (only with plaintext passcodes, NOT encrypted URL hashes)
        const isPlaintextCode = pwd && pwd.length <= 10 && !pwd.includes('.');
        if (isPlaintextCode) {
          for (const sel of passSelectors) {
            const input = await page.$(sel).catch(() => null);
            if (input) {
              const val = await input.inputValue().catch(() => '');
              if (!val || val !== pwd) {
                // Fix #5: Passcode Entry Hardening — keyboard typing + React native setter
                await input.click({ force: true }).catch(() => {});
                await input.focus().catch(() => {});
                await page.keyboard.press('Control+A').catch(() => {});
                await page.keyboard.press('Backspace').catch(() => {});
                await page.keyboard.type(pwd, { delay: 30 }).catch(() => {});
                await input.fill(pwd).catch(() => {});
                await page.evaluate((s, val) => {
                  const el = document.querySelector(s);
                  if (el) {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    setter.call(el, val);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    el.dispatchEvent(new Event('blur', { bubbles: true }));
                  }
                }, sel, pwd).catch(() => {});
              }
            }
          }
        }

        // STEP 4: Handle reCAPTCHA & Multi-Field Registration only when required
        if (pageState.hasCaptcha || pageState.needsRegistration) {
          await handleRecaptchaAndRegister(page, botId, firstName, lastName, botEmail, directWcUrl).catch(() => {});
          await handleGoogleSignInStep(page, botId, directWcUrl).catch(() => {});
        }

        // STEP 5: Preview Screen Control (Mute Mic, Turn Off Video, and Click Blue "Join" Button)
        // Fix #7: Extended guard — don't click Join during auth, captcha, or bot-block states
        if (!pageState.needsRegistration && !pageState.needsAuth && !pageState.hasCaptcha && !pageState.isBotBlocked) {
          await page.evaluate(() => {
            const allBtns = Array.from(document.querySelectorAll('button, div[role="button"], a.btn'));
            const muteBtn = allBtns.find(b => {
              const txt = (b.textContent || '').trim().toLowerCase();
              const aria = (b.getAttribute('aria-label') || '').toLowerCase();
              return txt.includes('mute') && !txt.includes('unmute') && !aria.includes('unmute');
            });
            if (muteBtn) { try { muteBtn.click(); } catch(e){} }

            const stopVidBtn = allBtns.find(b => {
              const txt = (b.textContent || '').trim().toLowerCase();
              const aria = (b.getAttribute('aria-label') || '').toLowerCase();
              return txt.includes('stop video') || aria.includes('stop video');
            });
            if (stopVidBtn) { try { stopVidBtn.click(); } catch(e){} }
          }).catch(() => {});

          const clickedJoin = await page.evaluate(() => {
            const allBtns = Array.from(document.querySelectorAll('button, a.btn, div[role="button"], span.btn, input[type="submit"]'));
            const joinBtn = allBtns.find(b => {
              const txt = (b.textContent || b.value || '').trim().toLowerCase();
              const id = (b.id || '').toLowerCase();
              const cls = (b.className || '').toLowerCase();
              if (txt.includes('register')) return false;
              return txt === 'join' || txt === 'join meeting' || txt.includes('join') || id === 'joinbtn' || cls.includes('preview-join') || cls.includes('zm-btn--primary');
            });

            if (joinBtn) {
              joinBtn.removeAttribute('disabled');
              joinBtn.removeAttribute('aria-disabled');
              joinBtn.disabled = false;
              joinBtn.classList.remove('disabled', 'zm-btn--disabled');

              joinBtn.click();

              const propsKey = Object.keys(joinBtn).find(k => k.startsWith('__reactProps$') || k.startsWith('__reactEventHandlers$'));
              if (propsKey && joinBtn[propsKey] && typeof joinBtn[propsKey].onClick === 'function') {
                joinBtn[propsKey].onClick({ preventDefault: () => {}, stopPropagation: () => {} });
              }

              const form = joinBtn.closest('form');
              if (form) { try { form.requestSubmit(); } catch(e) { try { form.submit(); } catch(err){} } }
              return true;
            }
            return false;
          }).catch(() => false);

          if (clickedJoin) {
            console.log(`[Bot ${botId}] Preview Engine: Executed DOM & React Join button click!`);
            await page.keyboard.press('Enter').catch(() => {});
          }

          try {
            const joinBtnLoc = page.locator('button#joinBtn, button.preview-join-button, button:has-text("Join")').first();
            if (await joinBtnLoc.isVisible().catch(() => false)) {
              const txt = await joinBtnLoc.innerText().catch(() => '');
              if (!txt.toLowerCase().includes('register')) {
                await joinBtnLoc.click({ force: true }).catch(() => {});
              }
            }
          } catch (e) {}
        }

        // STEP 6: Auto-Dismiss Popups, Toasts, Recording Consent ("Got It"), and Computer Audio
        await page.evaluate(() => {
          const all = Array.from(document.querySelectorAll('button, a.btn, div[role="button"], span.btn'));
          all.forEach(b => {
            const txt = (b.textContent || b.value || '').trim().toLowerCase();
            const aria = (b.getAttribute('aria-label') || '').toLowerCase();
            if (
              txt === 'got it' || txt === 'ok' || txt === 'i agree' || txt === 'accept' ||
              txt === 'accept all' || txt === 'continue' || txt === 'close' ||
              aria.includes('close') || aria.includes('dismiss') || b.id === 'wc_agree1'
            ) {
              try { b.click(); } catch(e) {}
            }
            if (txt.includes('computer audio') || txt.includes('join audio')) {
              try { b.click(); } catch(e) {}
            }
          });
        }).catch(() => {});

        // STEP 8: Inject notification/banner cleanup CSS (only once, and safe selectors)
        if (isOnWebClient) {
          await page.evaluate(() => {
            if (!document.getElementById('bot-clean-style')) {
              const style = document.createElement('style');
              style.id = 'bot-clean-style';
              style.innerHTML = `
                .zm-notification, .notification-item, [class*="notification"], .wc-toast {
                  display: none !important;
                  opacity: 0 !important;
                  pointer-events: none !important;
                }
              `;
              document.head.appendChild(style);
            }
          }).catch(() => {});
        }

        // STEP 9: Scrape Live Closed Captions / Subtitles for Meeting Transcripts
        if (isOnWebClient) {
          const liveCaptions = await page.evaluate(() => {
            const captionEls = Array.from(document.querySelectorAll(
              '.closed-caption-layout, .caption-detail, span[class*="caption"], div[class*="caption-text"], [aria-label*="caption"]'
            ));
            return captionEls.map(el => (el.textContent || '').trim()).filter(txt => txt.length > 2);
          }).catch(() => []);

          if (liveCaptions.length > 0 && botRef) {
            if (!botRef.transcripts) botRef.transcripts = [];
            liveCaptions.forEach(text => {
              if (!botRef.transcripts.includes(text)) {
                const timestamp = new Date().toLocaleTimeString();
                const entry = `[${timestamp}] ${text}`;
                botRef.transcripts.push(entry);
                console.log(`[Bot ${botId} Transcript] ${entry}`);
              }
            });
          }
        }
      } catch (e) {
        console.error(`[Bot ${botId} Loop Error]`, e.message);
      }

      // Schedule next iteration (Fast 300ms delay for ultra-responsive joining)
      if (loopRunning) {
        loopTimeout = setTimeout(autonomousLoop, 300);
      }
    }

    // Start the sequential loop immediately (100ms)
    loopTimeout = setTimeout(autonomousLoop, 100);

    if (botInfo) {
      botInfo.status = 'JOINING';
      botInfo.stopLoop = () => { loopRunning = false; if (loopTimeout) clearTimeout(loopTimeout); };
    }


  } catch (err) {
    console.error(`[Bot ${botId}] Error launching bot container:`, err);
    const bot = activeBots.get(botId);
    if (bot) bot.status = 'ERROR';
  }
}

// Called by the Zoom webhook route when a REAL cloud recording finishes
// downloading. Adds it to the same Vault metadata the rest of the app uses,
// then fires the same real Drive backup path.
function addRecordingToVault({ fileName, filePath, meetingId, botName, sizeBytes, source }) {
  const metaEntry = {
    id: `rec_${Date.now()}`,
    meetingId,
    botName: botName || 'Zoom Cloud Recording',
    fileName,
    sizeBytes,
    sizeMb: (sizeBytes / (1024 * 1024)).toFixed(2) + ' MB',
    createdAt: new Date().toISOString(),
    status: 'RECORDED HD • ZOOM CLOUD (OFFICIAL) • LOCAL STORAGE',
    storageType: 'Local Storage',
    videoUrl: `/recordings/${fileName}`,
    videoSaved: true,
    source: source || 'zoom_cloud_recording'
  };
  recordingsMeta = recordingsMeta.filter(r => r.fileName !== fileName);
  recordingsMeta.unshift(metaEntry);
  saveRecordingsMeta();

  backupRecordingToDriveIfConnected(fileName, filePath, null).catch(() => {});
}
app.locals.addRecordingToVault = addRecordingToVault;

function deployBotFromUrl(parsed, sourceUser = 'whatsapp_user') {
  if (!parsed || !parsed.meetingId) return null;
  const botId = `bot_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const displayName = process.env.ZOOM_BOT_DISPLAY_NAME || 'Zoom AI Bot';

  activeBots.set(botId, {
    id: botId,
    userId: sourceUser,
    meetingId: parsed.meetingId,
    botName: displayName,
    quality: '1080p',
    format: 'mp4',
    connectionType: 'wifi',
    isWebinar: parsed.isWebinar,
    standardUrl: parsed.standardUrl,
    directWcUrl: parsed.directWcUrl,
    status: 'JOINING',
    startTime: new Date().toISOString()
  });

  setTimeout(() => {
    launchZoomBotContainer(botId, parsed.standardUrl, parsed.directWcUrl, displayName, parsed.pwd, '1080p', 'mp4', 'wifi');
  }, 50);

  return { botId, meetingId: parsed.meetingId };
}

function getActiveBotStatus() {
  if (activeBots.size === 0) return 'ℹ️ No active Zoom recording session found.';
  const bot = [...activeBots.values()][0];
  return `📹 Active recording for Meeting ID: ${bot.meetingId}\nStatus: ${bot.status} (${bot.botName})`;
}

function stopActiveBot() {
  if (activeBots.size === 0) return '⚠️ No active recording session found to stop.';
  const bot = [...activeBots.values()][0];
  stopBotAndSaveRecording(bot.id, 'mp4');
  return `⏹️ Stopping recording session for Meeting ID ${bot.meetingId}... Video will be saved to Vault.`;
}

app.locals.buildZoomUrls = buildZoomUrls;
app.locals.deployBotFromUrl = deployBotFromUrl;
app.locals.getActiveBotStatus = getActiveBotStatus;
app.locals.stopActiveBot = stopActiveBot;

// POST /api/zoom/create-meeting — creates a real Zoom meeting with cloud auto-recording
app.post('/api/zoom/create-meeting', authenticate, async (req, res) => {
  if (!zoomCloudService || !zoomCloudService.isConfigured()) {
    return res.status(400).json({
      success: false,
      error: 'ZOOM_NOT_CONFIGURED',
      message: 'Zoom Server-to-Server OAuth is not set up on this server. Set ZOOM_ACCOUNT_ID, ZOOM_CLIENT_ID, ZOOM_CLIENT_SECRET, and ZOOM_WEBHOOK_SECRET_TOKEN in the environment.'
    });
  }

  const { topic, startTime, durationMinutes, hostEmail } = req.body || {};
  try {
    const meeting = await zoomCloudService.createAutoRecordedMeeting({ topic, startTime, durationMinutes, hostEmail });
    res.json({
      success: true,
      meetingId: meeting.id,
      joinUrl: meeting.join_url,
      startUrl: meeting.start_url,
      password: meeting.password,
      message: 'Real Zoom meeting created with cloud auto-recording enabled.'
    });
  } catch (err) {
    console.error('[Zoom Cloud] create-meeting failed:', err.message);
    res.status(500).json({ success: false, error: 'ZOOM_API_ERROR', message: err.message });
  }
});

// GET /api/zoom/status — lets the frontend show whether official Zoom integration is configured
app.get('/api/zoom/status', authenticate, (req, res) => {
  res.json({ configured: !!(zoomCloudService && zoomCloudService.isConfigured()) });
});

// GET /api/health — System health check for dashboard and electron clients
app.get('/api/health', (req, res) => {
  res.json({
    status: 'healthy',
    timestamp: new Date().toISOString(),
    service: 'Zoom Bot Meeting Recorder Backend & Studio',
    version: '3.0.0',
    activeBotsCount: activeBots.size,
    recordingsCount: recordingsMeta.length,
    storage: {
      path: RECORDINGS_DIR,
      exists: fs.existsSync(RECORDINGS_DIR)
    }
  });
});

// GET /zoom/sdk-jwt — Generates Zoom Meeting SDK JWT for Android / Mobile clients
app.get('/zoom/sdk-jwt', (req, res) => {
  const sdkKey = process.env.ZOOM_SDK_KEY || process.env.ZOOM_CLIENT_ID;
  const sdkSecret = process.env.ZOOM_SDK_SECRET || process.env.ZOOM_CLIENT_SECRET;
  if (!sdkKey || !sdkSecret) {
    return res.status(500).json({
      error: 'Server configuration error',
      message: 'ZOOM_SDK_KEY and ZOOM_SDK_SECRET must be set in .env'
    });
  }
  const iat = Math.floor(Date.now() / 1000) - 30;
  const exp = iat + 60 * 60 * 2;
  const payload = { appKey: sdkKey, iat, exp, tokenExp: exp, role: 0 };
  try {
    const sdkJwt = jwt.sign(payload, sdkSecret, { algorithm: 'HS256' });
    res.json({ sdkJwt, expiresAt: exp });
  } catch (err) {
    res.status(500).json({ error: 'JWT generation failed', message: err.message });
  }
});

// Zoom OAuth Endpoints
app.get('/oauth/zoom/authorize', (req, res) => {
  const clientId = process.env.ZOOM_CLIENT_ID || process.env.ZOOM_SDK_KEY;
  const redirectUri = process.env.ZOOM_REDIRECT_URI || 'http://localhost:3000/oauth/zoom/callback';
  if (!clientId) return res.status(500).send('ZOOM_CLIENT_ID not configured in .env');
  res.redirect(`https://zoom.us/oauth/authorize?response_type=code&client_id=${clientId}&redirect_uri=${encodeURIComponent(redirectUri)}`);
});

app.get('/oauth/zoom/callback', async (req, res) => {
  const code = req.query.code;
  if (!code) return res.status(400).send('Authorization code missing');
  try {
    const zoomAuth = require('./services/zoomAuth');
    const account = await zoomAuth.exchangeOAuthCode(code);
    res.send(`
      <html>
        <body style="font-family: sans-serif; background: #0f172a; color: white; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh;">
          <h2>✅ Zoom Account Authorized</h2>
          <p>Bot Account: <b>${account.display_name}</b> (ID: ${account.zoom_user_id})</p>
          <p>You can now return to the Web Studio or mobile app and start recording meetings.</p>
        </body>
      </html>
    `);
  } catch (err) {
    res.status(500).send(`OAuth authorization failed: ${err.message}`);
  }
});

// Helper: Escape HTML for public web pages
function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── GET /api/server/info — Central Server Network & Discovery Status ──────────
app.get('/api/server/info', (req, res) => {
  const cloudStorage = require('./services/cloudStorageService');
  const netInfo = cloudStorage.getServerNetworkInfo(PORT);
  const stats = cloudStorage.getStorageStats();
  res.json({
    status: 'online',
    service: 'Zoom Autonomous Meeting Recorder & Cloud Storage Engine',
    version: '3.0.0',
    server: netInfo,
    activeBotsCount: activeBots.size,
    storage: stats
  });
});

// ── Android App Compatibility Routes (ServerRecorderClient.kt) ───────────────
// POST /api/bot/record — Initiates headless Zoom recording from Android app
app.post('/api/bot/record', async (req, res) => {
  const { meetingUrl, passcode, displayName, zoomEmail, zoomPassword } = req.body || {};
  if (!meetingUrl) {
    return res.status(400).json({ success: false, message: 'meetingUrl is required' });
  }

  const parsedUrls = buildZoomUrls(meetingUrl, passcode, displayName);
  if (!parsedUrls || !parsedUrls.meetingId) {
    return res.status(400).json({ success: false, message: 'Invalid Zoom meeting URL or ID' });
  }

  const { meetingId, pwd, isWebinar, standardUrl, directWcUrl } = parsedUrls;
  const botId = `bot_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  const botName = displayName || 'Android Bot Assistant';

  activeBots.set(botId, {
    id: botId,
    userId: 'android_client',
    meetingId,
    botName,
    zoomEmail: zoomEmail || null,
    zoomPassword: zoomPassword || null,
    quality: '1080p',
    format: 'mp4',
    connectionType: 'wifi',
    isWebinar,
    standardUrl,
    directWcUrl,
    status: 'JOINING',
    startTime: new Date().toISOString()
  });

  setTimeout(() => {
    launchZoomBotContainer(botId, standardUrl, directWcUrl, botName, pwd, '1080p', 'mp4', 'wifi');
  }, 50);

  const cloudStorage = require('./services/cloudStorageService');
  const netInfo = cloudStorage.getServerNetworkInfo(PORT);

  res.json({
    success: true,
    meetingId: botId,
    zoomMeetingId: meetingId,
    status: 'JOINING',
    liveUrl: `/api/live/screen`,
    liveMonitorUrl: `${netInfo.lanUrl}/?botId=${botId}`,
    lanUrl: netInfo.lanUrl,
    localhostUrl: netInfo.localhostUrl,
    message: `Autonomous Bot "${botName}" deployed to Meeting ${meetingId}`
  });
});

// GET /api/bot/active — Returns active status for Android client HUD
app.get('/api/bot/active', (req, res) => {
  if (activeBots.size === 0) {
    return res.json({
      active: false,
      status: 'IDLE',
      frameCount: 0
    });
  }

  const [activeBot] = activeBots.values();
  res.json({
    active: true,
    meetingId: activeBot.id,
    zoomMeetingId: activeBot.meetingId,
    status: activeBot.status || 'RECORDING',
    statusMessage: activeBot.statusMessage || null,
    error: activeBot.error || null,
    displayName: activeBot.botName,
    frameCount: activeBot.lastScreenshotBuf ? 100 : 0,
    liveScreenUrl: `/api/live/screen`
  });
});

// GET /api/live/screen — Serves current browser screen image directly to Android app / HUD
app.get('/api/live/screen', (req, res) => {
  if (activeBots.size === 0) {
    return res.status(404).send('No active bot stream');
  }

  const [activeBot] = activeBots.values();
  if (activeBot.lastScreenshotBuf) {
    res.setHeader('Content-Type', 'image/png');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    return res.send(activeBot.lastScreenshotBuf);
  }

  res.status(204).end();
});

// GET /api/recordings — Returns JSON array matching Android ServerRecordingItem format
app.get('/api/recordings', (req, res) => {
  const cloudStorage = require('./services/cloudStorageService');
  const files = cloudStorage.listFiles();
  const androidItems = files.map(f => ({
    id: f.id,
    meetingId: f.id,
    zoomMeetingId: f.fileName.split('_')[1] || f.fileName,
    fileName: f.originalName || f.fileName,
    fileSize: f.fileSize || 0,
    mimeType: f.mimeType || 'video/mp4',
    createdAt: f.createdAt || new Date().toISOString()
  }));
  res.json(androidItems);
});

// GET /api/recordings/:id/download — Downloads media file directly for Android app
app.get('/api/recordings/:id/download', (req, res) => {
  const cloudStorage = require('./services/cloudStorageService');
  const item = cloudStorage.getFile(req.params.id);
  if (!item || !fs.existsSync(item.filePath)) {
    return res.status(404).json({ error: 'Recording not found' });
  }
  cloudStorage.incrementDownloadCount(item.id);
  res.download(item.filePath, item.originalName || item.fileName);
});

// ── GET /s/:shareId — Standalone Public Cloud Storage Share Page ──────────────
app.get('/s/:shareId', (req, res) => {
  const cloudStorage = require('./services/cloudStorageService');
  const share = cloudStorage.getShare(req.params.shareId);
  if (!share) {
    return res.status(404).send(`
      <!DOCTYPE html>
      <html>
      <head>
        <meta charset="utf-8">
        <title>Link Expired or Not Found — Cloud Storage Vault</title>
        <style>
          body { background: #0b0f19; color: #f1f5f9; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
          .card { background: rgba(30, 41, 59, 0.7); border: 1px solid rgba(255, 255, 255, 0.1); border-radius: 16px; padding: 40px; text-align: center; max-width: 450px; box-shadow: 0 20px 40px rgba(0,0,0,0.5); backdrop-filter: blur(10px); }
          h2 { color: #ef4444; margin-top: 0; }
          p { color: #94a3b8; line-height: 1.6; }
          a { display: inline-block; margin-top: 20px; padding: 10px 24px; background: #3b82f6; color: #fff; text-decoration: none; border-radius: 8px; font-weight: 600; }
        </style>
      </head>
      <body>
        <div class="card">
          <h2>⚠️ Share Link Expired or Invalid</h2>
          <p>This public cloud storage share link has expired or has been revoked by the owner.</p>
          <a href="/">Go to Cloud Storage Vault</a>
        </div>
      </body>
      </html>
    `);
  }

  const isVideo = share.mimeType && share.mimeType.startsWith('video/');
  const isAudio = share.mimeType && share.mimeType.startsWith('audio/');
  const sizeMb = (share.fileSize / (1024 * 1024)).toFixed(1);
  const streamUrl = `/api/storage/stream/${share.fileId}`;
  const downloadUrl = `/api/storage/download/${share.fileId}`;

  res.send(`
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>${escapeHtml(share.originalName)} — Cloud Storage Share</title>
      <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
      <style>
        * { box-sizing: border-box; }
        body {
          margin: 0;
          background: #080c14;
          color: #f8fafc;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          min-height: 100vh;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          padding: 20px;
        }
        .share-container {
          width: 100%;
          max-width: 860px;
          background: rgba(15, 23, 42, 0.85);
          border: 1px solid rgba(255, 255, 255, 0.1);
          border-radius: 20px;
          overflow: hidden;
          box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
          backdrop-filter: blur(16px);
        }
        .header {
          padding: 24px 30px;
          border-bottom: 1px solid rgba(255, 255, 255, 0.08);
          display: flex;
          align-items: center;
          justify-content: space-between;
          background: rgba(255, 255, 255, 0.02);
        }
        .brand {
          display: flex;
          align-items: center;
          gap: 12px;
        }
        .brand-icon {
          width: 40px;
          height: 40px;
          border-radius: 10px;
          background: linear-gradient(135deg, #3b82f6, #6366f1);
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 1.2rem;
          color: white;
        }
        .brand-title {
          font-weight: 700;
          font-size: 1.1rem;
          letter-spacing: -0.02em;
        }
        .brand-sub {
          font-size: 0.75rem;
          color: #94a3b8;
        }
        .media-viewer {
          background: #000;
          display: flex;
          align-items: center;
          justify-content: center;
          max-height: 520px;
          position: relative;
        }
        video {
          width: 100%;
          max-height: 500px;
          outline: none;
        }
        audio {
          width: 90%;
          margin: 40px 0;
        }
        .file-info {
          padding: 24px 30px;
          display: flex;
          flex-wrap: wrap;
          align-items: center;
          justify-content: space-between;
          gap: 20px;
        }
        .file-meta h2 {
          margin: 0 0 8px 0;
          font-size: 1.25rem;
          word-break: break-all;
        }
        .file-badges {
          display: flex;
          gap: 12px;
          font-size: 0.85rem;
          color: #94a3b8;
        }
        .badge {
          background: rgba(255, 255, 255, 0.06);
          padding: 4px 10px;
          border-radius: 6px;
          border: 1px solid rgba(255, 255, 255, 0.05);
        }
        .actions {
          display: flex;
          gap: 12px;
        }
        .btn-download {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          background: linear-gradient(135deg, #2563eb, #4f46e5);
          color: white;
          padding: 12px 24px;
          border-radius: 10px;
          text-decoration: none;
          font-weight: 600;
          font-size: 0.95rem;
          transition: all 0.2s ease;
          box-shadow: 0 4px 14px rgba(37, 99, 235, 0.4);
        }
        .btn-download:hover {
          transform: translateY(-2px);
          box-shadow: 0 6px 20px rgba(37, 99, 235, 0.6);
        }
        .footer {
          text-align: center;
          margin-top: 24px;
          color: #64748b;
          font-size: 0.8rem;
        }
      </style>
    </head>
    <body>
      <div class="share-container">
        <div class="header">
          <div class="brand">
            <div class="brand-icon"><i class="fa-solid fa-cloud"></i></div>
            <div>
              <div class="brand-title">Zoom Private Cloud Storage</div>
              <div class="brand-sub">Secure Media Vault & Sharing</div>
            </div>
          </div>
          <div class="badge"><i class="fa-solid fa-lock"></i> Protected Share</div>
        </div>

        ${isVideo ? `
        <div class="media-viewer">
          <video controls autoplay playsinline preload="metadata">
            <source src="${streamUrl}" type="${share.mimeType || 'video/mp4'}">
            Your browser does not support the video tag.
          </video>
        </div>` : isAudio ? `
        <div class="media-viewer">
          <audio controls autoplay preload="metadata">
            <source src="${streamUrl}" type="${share.mimeType || 'audio/mpeg'}">
            Your browser does not support the audio tag.
          </audio>
        </div>` : ''}

        <div class="file-info">
          <div class="file-meta">
            <h2>${escapeHtml(share.originalName)}</h2>
            <div class="file-badges">
              <span class="badge"><i class="fa-solid fa-hard-drive"></i> ${sizeMb} MB</span>
              <span class="badge"><i class="fa-regular fa-clock"></i> ${new Date(share.createdAt).toLocaleDateString()}</span>
              ${share.expiresAt ? `<span class="badge"><i class="fa-solid fa-hourglass-half"></i> Expires ${new Date(share.expiresAt).toLocaleTimeString()}</span>` : '<span class="badge"><i class="fa-solid fa-infinity"></i> Permanent</span>'}
            </div>
          </div>
          <div class="actions">
            ${share.allowDownload ? `
            <a href="${downloadUrl}" class="btn-download" download>
              <i class="fa-solid fa-cloud-arrow-down"></i> Download File
            </a>` : ''}
          </div>
        </div>
      </div>
      <div class="footer">
        Powered by Zoom Autonomous Meeting Recorder & Cloud Storage Engine v3.0.0
      </div>
    </body>
    </html>
  `);
});

// Automated cleanup for orphaned raw capture files (page@*.webm) from crashed bot sessions
function cleanupOrphanedCaptures() {
  try {
    const files = fs.readdirSync(RECORDINGS_DIR).filter(f => f.startsWith('page@') && f.endsWith('.webm'));
    const now = Date.now();
    const THIRTY_MIN = 30 * 60 * 1000;
    let removed = 0;
    for (const f of files) {
      const fp = path.join(RECORDINGS_DIR, f);
      try {
        const st = fs.statSync(fp);
        if (now - st.mtimeMs > THIRTY_MIN) {
          fs.unlinkSync(fp);
          removed++;
        }
      } catch (e) { /* file may have been removed concurrently */ }
    }
    if (removed > 0) {
      console.log(`[Cleanup] Removed ${removed} orphaned raw capture file(s) older than 30 minutes.`);
    }
  } catch (e) {
    console.error('[Cleanup] Error scanning for orphaned captures:', e.message);
  }
}
const cleanupInterval = setInterval(cleanupOrphanedCaptures, 15 * 60 * 1000); // every 15 minutes
if (cleanupInterval && cleanupInterval.unref) cleanupInterval.unref();
cleanupOrphanedCaptures(); // also run once on startup

const PORT = process.env.PORT || 3000;
if (require.main === module || (require.main && require.main.filename && (require.main.filename.endsWith('index.js') || require.main.filename.endsWith('bot_manager.js')))) {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 Zoom Autonomous Meeting Recorder Engine & Web Studio running on http://localhost:${PORT}`);
    console.log(`   Web Studio SPA:   http://localhost:${PORT}/`);
    console.log(`   Health Check:     http://localhost:${PORT}/api/health`);
    console.log(`   Zoom SDK JWT:     http://localhost:${PORT}/zoom/sdk-jwt`);
    console.log(`   Recordings Vault: ${RECORDINGS_DIR}`);
    
    // Initialize Telegram Bot Service
    initTelegramBot({
      token: process.env.TELEGRAM_BOT_TOKEN,
      localApiUrl: process.env.TELEGRAM_LOCAL_API_URL,
      deployBot: async ({ meetingUrl, passcode, botName, videoQuality, videoFormat, telegramChatId }) => {
        const parsedUrls = buildZoomUrls(meetingUrl, passcode, botName);
        if (!parsedUrls || !parsedUrls.meetingId) {
          return { success: false, message: 'Invalid Zoom link provided.' };
        }
        const { meetingId, pwd, isWebinar, standardUrl, directWcUrl } = parsedUrls;
        const botId = `bot_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
        const displayName = botName || 'Telegram Bot';

        activeBots.set(botId, {
          id: botId,
          userId: 'telegram_user',
          telegramChatId,
          meetingId,
          botName: displayName,
          quality: '1080p',
          format: 'mp4',
          connectionType: 'wifi',
          isWebinar,
          standardUrl,
          directWcUrl,
          status: 'JOINING',
          startTime: new Date().toISOString()
        });

        setTimeout(() => {
          launchZoomBotContainer(botId, standardUrl, directWcUrl, displayName, pwd, '1080p', 'mp4', 'wifi');
        }, 50);

        return { success: true, botId, meetingId };
      },
      stopBot: async (botId) => {
        return await stopBotAndSaveRecording(botId, 'mp4');
      },
      activeBots,
      getVaultRecordings: () => recordingsMeta
    });
  });
}

module.exports = app;
