// server/services/driveService.js
//
// Handles all Google Drive storage logic for a single user:
//   - exchanging/refreshing OAuth tokens
//   - creating the permanent "project folder" in the user's Drive (once, ever)
//   - uploading a recording into that folder (resumable, safe for large files)
//   - streaming a file back down for download
//   - deleting a file
//
// Env vars needed: GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_DRIVE_REDIRECT_URI

const { google } = require('googleapis');
const fs = require('fs');
const path = require('path');

const PERSIST_DIR = process.env.PERSIST_DIR || path.join(__dirname, '..');
const USERS_FILE = path.join(PERSIST_DIR, 'users.json');
const DRIVE_SCOPES = ['https://www.googleapis.com/auth/drive.file'];
const PROJECT_FOLDER_NAME = 'AI Zoom Participant Recordings';

function getOAuthClient() {
  return new google.auth.OAuth2(
    process.env.GOOGLE_CLIENT_ID,
    process.env.GOOGLE_CLIENT_SECRET,
    process.env.GOOGLE_DRIVE_REDIRECT_URI || 'http://localhost:3000/api/drive/callback'
  );
}

function loadUsersData() {
  try {
    if (fs.existsSync(USERS_FILE)) {
      return JSON.parse(fs.readFileSync(USERS_FILE, 'utf8'));
    }
  } catch (e) {}
  return [];
}

function saveUsersData(users) {
  try {
    fs.writeFileSync(USERS_FILE, JSON.stringify(users, null, 2));
  } catch (e) {}
}

function getDriveConsentUrl(userId) {
  const oauth2Client = getOAuthClient();
  return oauth2Client.generateAuthUrl({
    access_type: 'offline',
    prompt: 'consent',
    scope: DRIVE_SCOPES,
    state: String(userId),
  });
}

async function handleDriveOAuthCallback(userId, code) {
  const oauth2Client = getOAuthClient();
  const { tokens } = await oauth2Client.getToken(code);

  if (!tokens.refresh_token) {
    throw new Error(
      'No refresh_token returned. User may need to revoke prior access at myaccount.google.com/permissions and reconnect.'
    );
  }

  const users = loadUsersData();
  const user = users.find(u => u.email === userId || u.userId === userId || u.name === userId) || users[0];
  if (user) {
    user.drive_refresh_token = tokens.refresh_token;
    user.drive_connected_at = new Date().toISOString();
    saveUsersData(users);
  }

  oauth2Client.setCredentials(tokens);
  const folderId = await ensureProjectFolder(userId, oauth2Client);
  return folderId;
}

async function getAuthedClientForUser(userId) {
  const users = loadUsersData();
  const user = users.find(u => u.email === userId || u.userId === userId || u.name === userId) || users[0];
  
  if (!user || !user.drive_refresh_token) {
    throw new Error('User has not connected Google Drive yet.');
  }

  const oauth2Client = getOAuthClient();
  oauth2Client.setCredentials({ refresh_token: user.drive_refresh_token });
  return { oauth2Client, folderId: user.drive_folder_id };
}

async function ensureProjectFolder(userId, oauth2Client) {
  const users = loadUsersData();
  const user = users.find(u => u.email === userId || u.userId === userId || u.name === userId) || users[0];
  
  if (user?.drive_folder_id) {
    return user.drive_folder_id;
  }

  const drive = google.drive({ version: 'v3', auth: oauth2Client });
  const res = await drive.files.create({
    requestBody: {
      name: PROJECT_FOLDER_NAME,
      mimeType: 'application/vnd.google-apps.folder',
    },
    fields: 'id',
  });

  const folderId = res.data.id;
  if (user) {
    user.drive_folder_id = folderId;
    saveUsersData(users);
  }
  return folderId;
}

async function uploadRecording(userId, localFilePath, fileName, mimeType = 'video/mp4') {
  const { oauth2Client, folderId } = await getAuthedClientForUser(userId);
  const drive = google.drive({ version: 'v3', auth: oauth2Client });

  const res = await drive.files.create({
    requestBody: {
      name: fileName,
      parents: [folderId],
    },
    media: {
      mimeType,
      body: fs.createReadStream(localFilePath),
    },
    fields: 'id, size, webViewLink',
  });

  return { driveFileId: res.data.id, size: res.data.size, webViewLink: res.data.webViewLink };
}

async function streamDownload(userId, driveFileId, res) {
  const { oauth2Client } = await getAuthedClientForUser(userId);
  const drive = google.drive({ version: 'v3', auth: oauth2Client });

  const driveRes = await drive.files.get(
    { fileId: driveFileId, alt: 'media' },
    { responseType: 'stream' }
  );
  driveRes.data.pipe(res);
}

async function deleteRecording(userId, driveFileId) {
  const { oauth2Client } = await getAuthedClientForUser(userId);
  const drive = google.drive({ version: 'v3', auth: oauth2Client });
  await drive.files.delete({ fileId: driveFileId });
}

module.exports = {
  getDriveConsentUrl,
  handleDriveOAuthCallback,
  ensureProjectFolder,
  uploadRecording,
  streamDownload,
  deleteRecording,
};
