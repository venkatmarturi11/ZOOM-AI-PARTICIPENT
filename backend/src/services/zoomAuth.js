const jwt = require('jsonwebtoken');
const axios = require('axios');
const repo = require('../db/repository');

class ZoomAuthService {
  /**
   * Parse Zoom meeting URL or meeting number into clean components
   */
  parseMeetingInput(input) {
    if (!input) return { meetingNumber: '', password: '', joinUrl: '' };

    const cleanInput = input.trim();
    let meetingNumber = '';
    let password = '';

    // Check if full Zoom URL
    const urlMatch = cleanInput.match(/(?:https?:\/\/)?(?:[a-zA-Z0-9.-]+\.)?zoom\.us\/(?:j|w|wc|s)\/(\d{9,11})/i);
    if (urlMatch && urlMatch[1]) {
      meetingNumber = urlMatch[1];
      const pwdMatch = cleanInput.match(/[?&]pwd=([^&#]+)/i);
      if (pwdMatch) {
        password = decodeURIComponent(pwdMatch[1]);
      }
    } else {
      // Direct numeric ID
      const digitsOnly = cleanInput.replace(/\D/g, '');
      if (digitsOnly.length >= 9 && digitsOnly.length <= 11) {
        meetingNumber = digitsOnly;
      }
    }

    const joinUrl = meetingNumber 
      ? `https://app.zoom.us/wc/${meetingNumber}/join?prefer=1${password ? `&pwd=${encodeURIComponent(password)}` : ''}`
      : cleanInput;

    return {
      meetingNumber,
      password,
      joinUrl,
    };
  }

  /**
   * Generates a Meeting SDK JWT signed with ZOOM_SDK_SECRET.
   * Specification: Section 5 of Documentation
   */
  generateMeetingSdkJwt(role = 0) {
    const sdkKey = process.env.ZOOM_SDK_KEY || process.env.ZOOM_CLIENT_ID;
    const sdkSecret = process.env.ZOOM_SDK_SECRET || process.env.ZOOM_CLIENT_SECRET;

    if (!sdkKey || !sdkSecret) {
      console.warn('[ZoomAuth] ZOOM_SDK_KEY/ZOOM_SDK_SECRET not configured. Using fallback local signer.');
      return 'mock_sdk_jwt_for_local_dev';
    }

    const iat = Math.floor(Date.now() / 1000) - 30;
    const exp = iat + 60 * 60 * 2; // 2 hours valid

    const payload = {
      appKey: sdkKey,
      iat,
      exp,
      tokenExp: exp,
      role: role // 0 = attendee, 1 = host
    };

    return jwt.sign(payload, sdkSecret, { algorithm: 'HS256' });
  }

  /**
   * Exchange OAuth authorization code for Access & Refresh tokens.
   * Specification: Section 4.3 of Documentation
   */
  async exchangeOAuthCode(code) {
    const clientId = process.env.ZOOM_CLIENT_ID || process.env.ZOOM_SDK_KEY;
    const clientSecret = process.env.ZOOM_CLIENT_SECRET || process.env.ZOOM_SDK_SECRET;
    const redirectUri = process.env.ZOOM_REDIRECT_URI || 'http://localhost:3000/oauth/zoom/callback';

    if (!clientId || !clientSecret) {
      throw new Error('Zoom Client ID / Secret not configured');
    }

    const authHeader = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');

    const response = await axios.post(
      'https://zoom.us/oauth/token',
      new URLSearchParams({
        grant_type: 'authorization_code',
        code: code,
        redirect_uri: redirectUri,
      }).toString(),
      {
        headers: {
          'Authorization': `Basic ${authHeader}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
      }
    );

    const { access_token, refresh_token, expires_in } = response.data;
    const expiresAt = Math.floor(Date.now() / 1000) + (expires_in || 3600);

    // Fetch user info
    const userRes = await axios.get('https://api.zoom.us/v2/users/me', {
      headers: { 'Authorization': `Bearer ${access_token}` }
    });

    const zoomUser = userRes.data;
    const savedAccount = repo.saveZoomAccount({
      zoomUserId: zoomUser.id || 'me',
      displayName: `${zoomUser.first_name || ''} ${zoomUser.last_name || ''}`.trim() || zoomUser.email || 'Zoom Bot',
      accessToken: access_token,
      refreshToken: refresh_token,
      expiresAt: expiresAt,
    });

    console.log(`[ZoomAuth] Successfully authenticated Zoom account: ${savedAccount.display_name} (${savedAccount.zoom_user_id})`);
    return savedAccount;
  }

  /**
   * Refresh OAuth token if expired
   */
  async getValidOAuthToken() {
    const account = repo.getActiveZoomAccount();
    if (!account || !account.oauth_access_token) {
      return null;
    }

    const now = Math.floor(Date.now() / 1000);
    if (account.token_expires_at && account.token_expires_at > now + 120) {
      return account.oauth_access_token;
    }

    // Need refresh
    if (!account.oauth_refresh_token) return account.oauth_access_token;

    try {
      const clientId = process.env.ZOOM_CLIENT_ID || process.env.ZOOM_SDK_KEY;
      const clientSecret = process.env.ZOOM_CLIENT_SECRET || process.env.ZOOM_SDK_SECRET;
      const authHeader = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');

      const response = await axios.post(
        'https://zoom.us/oauth/token',
        new URLSearchParams({
          grant_type: 'refresh_token',
          refresh_token: account.oauth_refresh_token,
        }).toString(),
        {
          headers: {
            'Authorization': `Basic ${authHeader}`,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
        }
      );

      const { access_token, refresh_token, expires_in } = response.data;
      const expiresAt = now + (expires_in || 3600);

      repo.saveZoomAccount({
        zoomUserId: account.zoom_user_id,
        displayName: account.display_name,
        accessToken: access_token,
        refreshToken: refresh_token || account.oauth_refresh_token,
        expiresAt: expiresAt,
      });

      console.log(`[ZoomAuth] Refreshed OAuth token for user ${account.zoom_user_id}`);
      return access_token;
    } catch (e) {
      console.error('[ZoomAuth] Error refreshing OAuth token:', e.message);
      return account.oauth_access_token;
    }
  }

  /**
   * Retrieve a fresh Zoom Access Key (ZAK) for meeting join authorization.
   * Specification: Section 5 & 6 of Documentation
   */
  async getFreshZAK(userId = 'me') {
    const token = await this.getValidOAuthToken();
    if (!token) {
      console.warn('[ZoomAuth] No OAuth token available for ZAK retrieval. Bot will join as standard participant.');
      return null;
    }

    try {
      const targetUserId = process.env.ZOOM_BOT_USER_ID || userId;
      const response = await axios.get(`https://api.zoom.us/v2/users/${targetUserId}/token?type=zak`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      return response.data.token;
    } catch (err) {
      console.warn(`[ZoomAuth] ZAK retrieval note (${err.message}). Using standard join credentials.`);
      return null;
    }
  }
}

module.exports = new ZoomAuthService();
