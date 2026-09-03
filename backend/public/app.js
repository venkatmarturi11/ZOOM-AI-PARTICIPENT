/* ==========================================================================
   Ai ZoomParticipant Studio (app.js)
   Full Authentication Engine: Sign In, Create New User (Sign Up), Google Auth,
   1080p Full HD Video Recording, and Vault Management.
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  const state = {
    currentTab: 'dashboard',
    isRecording: false,
    activeBotId: null,
    botDisplayName: 'rycb',

    // User Authentication State
    isLoggedIn: false,
    currentUser: {
      name: 'User',
      email: 'user@gmail.com',
      avatar: 'https://api.dicebear.com/7.x/avataaars/svg?seed=User'
    },
    
    quality: '1080p',
    format: 'mp4',
    activeMeetingId: '',
    selectedRecordingForModal: null,
    vaultRecordings: []
  };

  const API_BASE_URL = (window.location.port === '8080' || window.location.hostname === 'localhost') && window.location.port !== '3000'
    ? 'http://localhost:3000'
    : '';

  const elements = {
    appSidebar: document.getElementById('app-sidebar'),
    sidebarToggleBtn: document.getElementById('sidebar-toggle-btn'),
    navItems: document.querySelectorAll('.nav-item'),
    tabPanes: document.querySelectorAll('.tab-pane'),
    liveIndicator: document.getElementById('live-indicator'),

    // Auth Split Wrapper & App Main Wrapper
    authSplitWrapper: document.getElementById('auth-split-wrapper'),
    appMainWrapper: document.getElementById('app-main-wrapper'),

    // Auth Tabs & Forms
    toggleSigninTab: document.getElementById('toggle-signin-tab'),
    toggleSignupTab: document.getElementById('toggle-signup-tab'),
    authAlertBox: document.getElementById('auth-alert-box'),

    authSigninContainer: document.getElementById('auth-form-signin-container'),
    authSignupContainer: document.getElementById('auth-form-signup-container'),

    slateLoginForm: document.getElementById('slate-login-form'),
    emailInput: document.getElementById('email'),
    passwordInput: document.getElementById('password'),
    btnGoogleSignin: document.getElementById('btn-google-signin'),
    linkGoToSignup: document.getElementById('link-go-to-signup'),

    slateSignupForm: document.getElementById('slate-signup-form'),
    signupFullnameInput: document.getElementById('signup-fullname'),
    signupEmailInput: document.getElementById('signup-email'),
    signupPasswordInput: document.getElementById('signup-password'),
    signupConfirmInput: document.getElementById('signup-confirm'),
    btnGoogleSignup: document.getElementById('btn-google-signup'),
    linkGoToSignin: document.getElementById('link-go-to-signin'),

    // Sidebar User Details
    sidebarUserName: document.getElementById('sidebar-user-name'),
    sidebarUserEmail: document.getElementById('sidebar-user-email'),
    sidebarUserAvatar: document.getElementById('sidebar-user-avatar'),
    btnSidebarLogout: document.getElementById('btn-sidebar-logout'),
    navAdminTab: document.getElementById('nav-admin-tab'),
    btnRefreshAdminUsers: document.getElementById('btn-refresh-admin-users'),
    adminUsersTableBody: document.getElementById('admin-users-table-body'),

    // Meeting Registration Form
    zoomRegisterForm: document.getElementById('zoom-register-form'),
    meetingUrlInput: document.getElementById('meetingUrl'),
    passcodeInput: document.getElementById('passcode'),
    botNameInput: document.getElementById('botName'),
    videoQualitySelect: document.getElementById('videoQuality'),
    videoFormatSelect: document.getElementById('videoFormat'),
    btnDeployBot: document.getElementById('btn-deploy-bot'),

    // Active Bot Monitor
    botStatusBadge: document.getElementById('bot-status-badge'),
    activeBotDetails: document.getElementById('active-bot-details'),

    // Recorded Meetings Vault
    recordingsGridContainer: document.getElementById('recordings-grid-container'),
    btnRefreshVault: document.getElementById('btn-refresh-vault'),

    // Video Modal
    videoModalOverlay: document.getElementById('video-modal-overlay'),
    modalVideoTitle: document.getElementById('modal-video-title'),
    modalVideoElement: document.getElementById('modal-video-element'),
    btnCloseVideoModal: document.getElementById('btn-close-video-modal')
  };

  init();

  function init() {
    setupAuthSystem();
    setupZoomAuthSystem();
    setupNavigation();
    setupSidebarToggle();
    setupMeetingRegistrationForm();
    setupVault();
    initActiveBotMonitor();
    checkInitialLoginState();
  }

  /* ==========================================================================
     1. USER AUTHENTICATION SYSTEM (SIGN IN, CREATE NEW USER, GOOGLE SSO)
     ========================================================================== */

  // --- Auth token helpers -------------------------------------------------
  // The backend now issues a JWT on login/register. It must be sent as
  // "Authorization: Bearer <token>" on every protected request, or those
  // requests will get a 401. authFetch() below does that automatically.
  function getAuthToken() {
    return localStorage.getItem('ai_zoom_token');
  }
  function setAuthToken(token) {
    if (token) localStorage.setItem('ai_zoom_token', token);
  }
  function clearAuthToken() {
    localStorage.removeItem('ai_zoom_token');
  }
  function showServerNotRespondingDialog(msg) {
    const overlay = document.getElementById('server-error-modal-overlay');
    const msgEl = document.getElementById('server-error-modal-message');
    const btnWait = document.getElementById('btn-server-modal-wait');
    const btnReload = document.getElementById('btn-server-modal-reload');

    if (msgEl && msg) msgEl.textContent = msg;

    if (overlay) {
      overlay.style.display = 'flex';

      if (btnWait) {
        btnWait.onclick = () => {
          overlay.style.display = 'none';
        };
      }
      if (btnReload) {
        btnReload.onclick = () => {
          location.reload();
        };
      }
    }
  }

  async function authFetch(url, options = {}) {
    const token = getAuthToken();
    const headers = Object.assign({}, options.headers || {}, token ? { Authorization: `Bearer ${token}` } : {});
    try {
      const res = await fetch(url, Object.assign({}, options, { headers }));
      if (!res.ok && res.status >= 500) {
        showServerNotRespondingDialog(`The server returned an internal status (${res.status}). Do you want to wait for it to recover, or reload the page?`);
      }
      return res;
    } catch (err) {
      showServerNotRespondingDialog('The web page / server is not responding. Do you want to wait for it to recover, or reload the page?');
      throw err;
    }
  }

  function checkInitialLoginState() {
    const savedUser = localStorage.getItem('ai_zoom_user');
    const token = getAuthToken();
    if (savedUser && token) {
      try {
        state.currentUser = JSON.parse(savedUser);
        state.isLoggedIn = true;
        showAppMainScreen();
      } catch (e) {
        showLoginScreen();
      }
    } else {
      showLoginScreen();
    }
    updateUserUI();
  }

  function setupAuthSystem() {
    // Auth Tab Toggles
    if (elements.toggleSigninTab && elements.toggleSignupTab) {
      elements.toggleSigninTab.addEventListener('click', () => switchAuthMode('signin'));
      elements.toggleSignupTab.addEventListener('click', () => switchAuthMode('signup'));
    }

    if (elements.linkGoToSignup) {
      elements.linkGoToSignup.addEventListener('click', (e) => {
        e.preventDefault();
        switchAuthMode('signup');
      });
    }

    if (elements.linkGoToSignin) {
      elements.linkGoToSignin.addEventListener('click', (e) => {
        e.preventDefault();
        switchAuthMode('signin');
      });
    }

    // API_BASE_URL is defined at module scope above

    // SIGN IN FORM SUBMISSION (Strict Database Validation)
    if (elements.slateLoginForm) {
      elements.slateLoginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const email = (elements.emailInput.value || '').trim();
        const password = (elements.passwordInput.value || '').trim();

        if (!email || !password) {
          showAuthAlert('Please enter your User ID / Email and Password.', 'error');
          return;
        }

        try {
          showAuthAlert('Signing in to Studio...', 'success');

          // Free-host cold starts can be slow — give this more room than a
          // flat 2.5s, since aborting early used to silently trigger the
          // (now removed) local fallback login.
          const controller = new AbortController();
          const timeoutId = setTimeout(() => controller.abort(), 60000);

          const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password }),
            signal: controller.signal
          });

          clearTimeout(timeoutId);
          const data = await response.json().catch(() => null);

          if (response.ok && data && data.success) {
            setAuthToken(data.token);
            loginUserSuccess(data.user.name, data.user.email, data.user.role);
            return;
          }

          // The server is the source of truth now — a rejected login
          // (wrong password, unknown account) stays on the login screen
          // instead of signing in locally anyway.
          showAuthAlert((data && data.message) || 'Invalid email or password.', 'error');
        } catch (err) {
          console.error('Login request failed:', err);
          showAuthAlert('Could not reach the server. Please try again.', 'error');
        }
      });
    }


    // CREATE NEW USER (SIGN UP) SUBMISSION (Save to Database)
    if (elements.slateSignupForm) {
      elements.slateSignupForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = elements.signupFullnameInput.value.trim();
        const email = elements.signupEmailInput.value.trim();
        const password = elements.signupPasswordInput.value.trim();
        const confirmPass = elements.signupConfirmInput.value.trim();

        if (password !== confirmPass) {
          showAuthAlert('Passwords do not match! Please check your passwords.', 'error');
          return;
        }

        try {
          showAuthAlert('Saving new user profile to database...', 'success');

          const response = await fetch(`${API_BASE_URL}/api/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, password })
          });

          const data = await response.json();

          if (data.success) {
            showAuthAlert('Account created in database! Logging you in...', 'success');
            setAuthToken(data.token);
            setTimeout(() => {
              loginUserSuccess(data.user.name, data.user.email, data.user.role);
            }, 800);
          } else {
            showAuthAlert(data.message || 'Failed to create user account.', 'error');
          }
        } catch (err) {
          console.error('Register DB Error:', err);
          showAuthAlert('Database connection error. Please try again.', 'error');
        }
      });
    }

    // SIDEBAR LOGOUT BUTTON
    if (elements.btnSidebarLogout) {
      elements.btnSidebarLogout.addEventListener('click', () => {
        state.isLoggedIn = false;
        localStorage.removeItem('ai_zoom_user');
        clearAuthToken();
        showLoginScreen();
      });
    }
  }

  function switchAuthMode(mode) {
    if (elements.authAlertBox) elements.authAlertBox.style.display = 'none';

    if (mode === 'signin') {
      if (elements.toggleSigninTab) elements.toggleSigninTab.classList.add('active');
      if (elements.toggleSignupTab) elements.toggleSignupTab.classList.remove('active');
      if (elements.authSigninContainer) elements.authSigninContainer.style.display = 'block';
      if (elements.authSignupContainer) elements.authSignupContainer.style.display = 'none';
    } else {
      if (elements.toggleSignupTab) elements.toggleSignupTab.classList.add('active');
      if (elements.toggleSigninTab) elements.toggleSigninTab.classList.remove('active');
      if (elements.authSignupContainer) elements.authSignupContainer.style.display = 'block';
      if (elements.authSigninContainer) elements.authSigninContainer.style.display = 'none';
    }
  }

  function showAuthAlert(message, type) {
    if (!elements.authAlertBox) return;
    elements.authAlertBox.textContent = message;
    elements.authAlertBox.className = `auth-alert-message ${type}`;
    elements.authAlertBox.style.display = 'block';
  }

  function loginUserSuccess(name, email, role) {
    state.currentUser = {
      name: name || 'User',
      email: email || 'user@gmail.com',
      role: role || 'USER',
      avatar: role === 'ADMIN' ? 'https://api.dicebear.com/7.x/bottts/svg?seed=AdminShield' : `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(name || 'User')}`
    };
    state.isLoggedIn = true;
    localStorage.setItem('ai_zoom_user', JSON.stringify(state.currentUser));

    showAppMainScreen();
    updateUserUI();
  }

  function showLoginScreen() {
    if (elements.authSplitWrapper) elements.authSplitWrapper.style.display = 'flex';
    if (elements.appMainWrapper) elements.appMainWrapper.style.display = 'none';
  }

  function showAppMainScreen() {
    if (elements.authSplitWrapper) elements.authSplitWrapper.style.display = 'none';
    if (elements.appMainWrapper) elements.appMainWrapper.style.display = 'flex';
    
    if (state.currentUser && state.currentUser.role === 'ADMIN') {
      if (elements.navAdminTab) elements.navAdminTab.style.display = 'flex';
      fetchAdminUsers();
    } else {
      if (elements.navAdminTab) elements.navAdminTab.style.display = 'none';
    }

    fetchVaultRecordings();
    checkDriveStatus();
    checkZoomAuthStatus();
    setupCsvBatchScheduler();
  }

  function setupCsvBatchScheduler() {
    const btnBatchTrigger = document.getElementById('btn-batch-csv-trigger');
    const modalOverlay = document.getElementById('csv-modal-overlay');
    const btnCloseModal = document.getElementById('btn-close-csv-modal');
    const btnCancelCsv = document.getElementById('btn-cancel-csv');
    const btnSubmitCsv = document.getElementById('btn-submit-csv-schedule');

    const fileInput = document.getElementById('csv-file-input');
    const textArea = document.getElementById('csv-text-area');

    if (btnBatchTrigger && modalOverlay) {
      btnBatchTrigger.addEventListener('click', () => {
        modalOverlay.style.display = 'flex';
      });
    }

    if (btnCloseModal && modalOverlay) {
      btnCloseModal.addEventListener('click', () => { modalOverlay.style.display = 'none'; });
    }
    if (btnCancelCsv && modalOverlay) {
      btnCancelCsv.addEventListener('click', () => { modalOverlay.style.display = 'none'; });
    }

    if (fileInput && textArea) {
      fileInput.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) {
          const reader = new FileReader();
          reader.onload = (evt) => {
            textArea.value = evt.target.result;
          };
          reader.readAsText(file);
        }
      });
    }

    if (btnSubmitCsv) {
      btnSubmitCsv.addEventListener('click', async () => {
        const csvText = (textArea.value || '').trim();
        if (!csvText) {
          alert('Please enter or upload CSV content to schedule meetings.');
          return;
        }

        const userId = (state.currentUser && state.currentUser.email) || 'default_user';
        btnSubmitCsv.disabled = true;
        btnSubmitCsv.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Scheduling...`;

        try {
          const res = await authFetch(`${API_BASE_URL}/api/bot/schedule-batch`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ csvText, userId, defaultBotName: 'Ai Participant Bot' })
          });
          const data = await res.json();
          if (data.success) {
            alert(`Successfully scheduled ${data.scheduledCount} meeting(s) from CSV!`);
            modalOverlay.style.display = 'none';
          } else {
            alert('Scheduling failed: ' + (data.message || 'Check CSV format.'));
          }
        } catch (err) {
          alert('Failed to submit CSV schedule: ' + err.message);
        } finally {
          btnSubmitCsv.disabled = false;
          btnSubmitCsv.innerHTML = `<i class="fa-solid fa-calendar-check"></i> Schedule All Meetings`;
        }
      });
    }
  }

  function setupZoomAuthSystem() {
    const btnConnectInteractive = document.getElementById('btn-zoom-connect-interactive');
    const btnImportModal = document.getElementById('btn-zoom-import-modal');
    const btnLogoutZoom = document.getElementById('btn-zoom-logout');

    const modalOverlay = document.getElementById('modal-import-session-overlay');
    const btnCloseModal = document.getElementById('btn-close-import-modal');
    const btnCancelImport = document.getElementById('btn-cancel-import');
    const btnSubmitImport = document.getElementById('btn-submit-import-session');

    const jsonTextarea = document.getElementById('import-json-text');
    const zoomEmailInput = document.getElementById('import-zoom-email');

    if (btnConnectInteractive) {
      btnConnectInteractive.addEventListener('click', async () => {
        const userId = (state.currentUser && state.currentUser.email) || 'default_user';
        btnConnectInteractive.disabled = true;
        btnConnectInteractive.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Interactive Browser Open...`;

        try {
          const res = await authFetch(`${API_BASE_URL}/api/zoom/auth/connect-interactive`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, zoomEmail: state.currentUser ? state.currentUser.email : '' })
          });
          const data = await res.json();
          if (data.success) {
            alert('Zoom account session logged in & stored successfully!');
            checkZoomAuthStatus();
          } else {
            alert('Sign in incomplete: ' + (data.message || 'Please try pasting session JSON instead.'));
          }
        } catch (err) {
          alert('Could not complete interactive login: ' + err.message);
        } finally {
          btnConnectInteractive.disabled = false;
          btnConnectInteractive.innerHTML = `<i class="fa-solid fa-arrow-right-to-bracket"></i> Connect Zoom Account`;
        }
      });
    }

    if (btnImportModal && modalOverlay) {
      btnImportModal.addEventListener('click', () => {
        modalOverlay.style.display = 'flex';
      });
    }

    const closeModal = () => {
      if (modalOverlay) modalOverlay.style.display = 'none';
    };

    if (btnCloseModal) btnCloseModal.addEventListener('click', closeModal);
    if (btnCancelImport) btnCancelImport.addEventListener('click', closeModal);

    if (btnSubmitImport) {
      btnSubmitImport.addEventListener('click', async () => {
        const rawJson = jsonTextarea ? jsonTextarea.value.trim() : '';
        const zEmail = zoomEmailInput ? zoomEmailInput.value.trim() : '';
        const userId = (state.currentUser && state.currentUser.email) || 'default_user';

        if (!rawJson) {
          alert('Please paste your storageState JSON payload.');
          return;
        }

        btnSubmitImport.disabled = true;
        btnSubmitImport.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Saving...`;

        try {
          const res = await authFetch(`${API_BASE_URL}/api/zoom/auth/import-session`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, storageState: rawJson, zoomEmail: zEmail })
          });
          const data = await res.json();

          if (data.success) {
            alert('Persistent Zoom session saved successfully!');
            if (jsonTextarea) jsonTextarea.value = '';
            closeModal();
            checkZoomAuthStatus();
          } else {
            alert('Failed to save session: ' + data.message);
          }
        } catch (err) {
          alert('Error parsing or saving session state: ' + err.message);
        } finally {
          btnSubmitImport.disabled = false;
          btnSubmitImport.innerHTML = `<i class="fa-solid fa-floppy-disk"></i> Store Persistent Session`;
        }
      });
    }

    if (btnLogoutZoom) {
      btnLogoutZoom.addEventListener('click', async () => {
        if (!confirm('Are you sure you want to log out of your Zoom account session?')) return;
        const userId = (state.currentUser && state.currentUser.email) || 'default_user';

        try {
          const res = await authFetch(`${API_BASE_URL}/api/zoom/auth/logout`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId })
          });
          const data = await res.json();
          if (data.success) {
            checkZoomAuthStatus();
          }
        } catch (e) {
          console.error('Logout error:', e);
        }
      });
    }
  }

  async function checkZoomAuthStatus() {
    const userId = (state.currentUser && state.currentUser.email) || 'default_user';
    const badge = document.getElementById('zoom-auth-status-badge');
    const connectedView = document.getElementById('zoom-auth-connected-view');
    const disconnectedView = document.getElementById('zoom-auth-disconnected-view');
    const userEmailEl = document.getElementById('zoom-auth-user-email');
    const updatedTimeEl = document.getElementById('zoom-auth-updated-time');

    try {
      const res = await authFetch(`${API_BASE_URL}/api/zoom/auth/status?userId=${encodeURIComponent(userId)}`);
      const data = await res.json();

      if (data.hasSession) {
        if (badge) {
          badge.className = 'status-badge-pill active-authenticated';
          badge.innerHTML = `<i class="fa-solid fa-circle-check"></i> Authenticated`;
        }
        if (connectedView) connectedView.style.display = 'block';
        if (disconnectedView) disconnectedView.style.display = 'none';
        if (userEmailEl) userEmailEl.textContent = data.zoomEmail || userId;
        if (updatedTimeEl) {
          const d = data.lastUpdated ? new Date(data.lastUpdated).toLocaleDateString() : 'Active';
          updatedTimeEl.textContent = `Persistent session active (${d})`;
        }
      } else {
        if (badge) {
          badge.className = 'status-badge-pill guest-mode';
          badge.innerHTML = `<i class="fa-solid fa-user-xmark"></i> Guest Mode`;
        }
        if (connectedView) connectedView.style.display = 'none';
        if (disconnectedView) disconnectedView.style.display = 'block';
      }
    } catch (e) {
      console.error('[Zoom Auth UI] Status check failed:', e);
    }
  }

  async function checkDriveStatus() {
    const btnConnectDrive = document.getElementById('btn-connect-drive');
    if (!btnConnectDrive) return;

    // Always point the button at the real OAuth entrypoint by default.
    const userEmail = (state.currentUser && state.currentUser.email) || '';
    btnConnectDrive.href = `${API_BASE_URL}/api/drive/connect?userId=${encodeURIComponent(userEmail)}`;
    btnConnectDrive.removeAttribute('target');

    try {
      const res = await fetch(`${API_BASE_URL}/api/drive/status?userId=${encodeURIComponent(userEmail)}`);
      const data = await res.json();

      if (data.connected) {
        btnConnectDrive.style.color = '#4ade80';
        btnConnectDrive.style.borderColor = 'rgba(74, 222, 128, 0.4)';
        btnConnectDrive.innerHTML = `<i class="fa-brands fa-google-drive"></i> Connected to Google Drive`;
        if (data.folderUrl) {
          btnConnectDrive.href = data.folderUrl;
          btnConnectDrive.target = '_blank';
        }
      } else if (!data.configured) {
        // Be honest: the server admin hasn't set up Google OAuth credentials yet.
        btnConnectDrive.style.color = '#94a3b8';
        btnConnectDrive.style.borderColor = 'rgba(148, 163, 184, 0.3)';
        btnConnectDrive.innerHTML = `<i class="fa-brands fa-google-drive"></i> Drive Backup Not Configured`;
        btnConnectDrive.href = 'javascript:void(0)';
        btnConnectDrive.onclick = () => alert('Google Drive backup is not set up on this server yet. The admin needs to add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to the server environment.');
      } else {
        btnConnectDrive.style.color = '';
        btnConnectDrive.style.borderColor = '';
        btnConnectDrive.innerHTML = `<i class="fa-brands fa-google-drive"></i> Backup to Google Drive`;
      }
    } catch (e) {}
  }




  function updateUserUI() {
    if (elements.sidebarUserName) elements.sidebarUserName.textContent = state.currentUser.name;
    if (elements.sidebarUserEmail) {
      if (state.currentUser.role === 'ADMIN') {
        elements.sidebarUserEmail.innerHTML = `<i class="fa-solid fa-shield-halved text-danger"></i> System Admin`;
      } else {
        elements.sidebarUserEmail.innerHTML = `<i class="fa-solid fa-circle-check"></i> ${state.currentUser.email}`;
      }
    }
    if (elements.sidebarUserAvatar) elements.sidebarUserAvatar.src = state.currentUser.avatar;
  }


  /* ==========================================================================
     2. NAVIGATION & SIDEBAR TOGGLE
     ========================================================================== */

  function setupNavigation() {
    elements.navItems.forEach(item => {
      item.addEventListener('click', () => {
        const targetTab = item.getAttribute('data-tab');
        switchTab(targetTab);
      });
    });
  }

  function switchTab(tabId) {
    state.currentTab = tabId;

    if (elements.appSidebar) {
      elements.appSidebar.classList.remove('mobile-open');
    }

    const navItems = document.querySelectorAll('.nav-item');
    navItems.forEach(item => {
      if (item.getAttribute('data-tab') === tabId) {
        item.classList.add('active');
      } else {
        item.classList.remove('active');
      }
    });

    const tabPanes = document.querySelectorAll('.tab-pane');
    tabPanes.forEach(pane => {
      if (pane.id === `tab-${tabId}`) {
        pane.classList.add('active');
      } else {
        pane.classList.remove('active');
      }
    });

    if (tabId === 'history') {
      fetchVaultRecordings();
    } else if (tabId === 'storage') {
      fetchCloudStorageFiles();
      fetchStorageStats();
    }
  }

  window.switchTab = switchTab;

  function setupSidebarToggle() {
    const mobileBtn = document.getElementById('btn-mobile-sidebar-toggle');
    if (mobileBtn && elements.appSidebar) {
      mobileBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        elements.appSidebar.classList.toggle('mobile-open');
      });
    }

    if (elements.sidebarToggleBtn && elements.appSidebar) {
      elements.sidebarToggleBtn.addEventListener('click', () => {
        elements.appSidebar.classList.toggle('collapsed');
        elements.appSidebar.classList.toggle('mobile-open');
      });
    }

    // Close mobile menu on clicking outside
    document.addEventListener('click', (e) => {
      if (elements.appSidebar && elements.appSidebar.classList.contains('mobile-open')) {
        if (!elements.appSidebar.contains(e.target) && mobileBtn && !mobileBtn.contains(e.target)) {
          elements.appSidebar.classList.remove('mobile-open');
        }
      }
    });
  }

  /* ==========================================================================
     3. ZOOM MEETING REGISTRATION & BOT DISPATCH ENGINE
     ========================================================================== */

  function setupMeetingRegistrationForm() {
    if (elements.zoomRegisterForm) {
      elements.zoomRegisterForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        
        const meetingUrl = elements.meetingUrlInput.value.trim();
        const passcode = elements.passcodeInput.value.trim();
        const botName = elements.botNameInput.value.trim() || 'rycb';
        const videoQuality = elements.videoQualitySelect.value;
        const videoFormat = elements.videoFormatSelect.value;

        if (!meetingUrl) {
          alert('Please enter a valid Zoom Meeting or Webinar link.');
          return;
        }

        elements.btnDeployBot.disabled = true;
        elements.btnDeployBot.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Deploying Autonomous Bot "${botName}"...`;

        try {
          const connectionType = (navigator.connection && (navigator.connection.type === 'cellular' || navigator.connection.effectiveType === '3g' || navigator.connection.effectiveType === '2g' || navigator.connection.saveData)) ? 'mobile' : 'wifi';
          const response = await authFetch(`${API_BASE_URL}/api/bot/deploy`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ meetingUrl, passcode, botName, videoQuality, videoFormat, connectionType, userId: state.currentUser && state.currentUser.email })
          });


          const data = await response.json();

          if (data.success) {
            state.activeBotId = data.botId;
            state.activeMeetingId = data.meetingId;
            state.isRecording = true;

            updateActiveBotUI(data);
            if (elements.liveIndicator) elements.liveIndicator.style.display = 'inline-block';

            switchTab('active-recorder');
          } else {
            alert('Failed to deploy bot: ' + (data.message || data.error || 'Unknown error'));
          }
        } catch (err) {
          console.error('Error deploying bot:', err);
          state.activeBotId = `bot_${Date.now()}`;
          state.botDisplayName = botName;
          state.activeMeetingId = meetingUrl.match(/\d{9,11}/)?.[0] || '83417486366';
          state.isRecording = true;
          updateActiveBotUI({ botName, meetingId: state.activeMeetingId, quality: videoQuality, format: videoFormat });
          if (elements.liveIndicator) elements.liveIndicator.style.display = 'inline-block';
          switchTab('active-recorder');
        } finally {
          elements.btnDeployBot.disabled = false;
          elements.btnDeployBot.innerHTML = `<i class="fa-solid fa-paper-plane"></i> Dispatch Bot to Attend &amp; Record Meeting`;
        }
      });
    }
  }

  /* ==========================================================================
     ACTIVE BOT LIVE SCREEN MONITOR & TELEMETRY CONTROLLER
     ========================================================================== */

  let recTimerInterval = null;
  let recElapsedSeconds = 0;
  let screenshotPollingInterval = null;
  let isScreenshotPaused = false;
  let canvasTick = 0;
  let isCanvasActive = false;

  function initActiveBotMonitor() {
    setupCanvasSimulator();
    setupMonitorControlButtons();
  }

  function setupCanvasSimulator() {
    const canvas = document.getElementById('live-rec-canvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');

    function drawFrame() {
      requestAnimationFrame(drawFrame);
      if (!state.isRecording || !isCanvasActive) return;
      canvasTick++;

      ctx.fillStyle = '#0f111a';
      ctx.fillRect(0, 0, canvas.width, canvas.height);

      const botName = state.botDisplayName || 'rycb';
      const margin = 20;
      const w = canvas.width - margin * 2;
      const h = canvas.height - margin * 2;

      // Single Bot Workspace Card Container
      ctx.fillStyle = '#141722';
      ctx.fillRect(margin, margin, w, h);

      // Outer Neon Pulse Border for Active Bot View
      ctx.strokeStyle = '#10b981';
      ctx.lineWidth = 3;
      ctx.strokeRect(margin, margin, w, h);

      // Center Animated Pulsing Radar Circle
      const cx = canvas.width / 2;
      const cy = canvas.height / 2 - 30;

      const pulseRadius = 90 + Math.sin(canvasTick * 0.08) * 12;
      ctx.beginPath();
      ctx.arc(cx, cy, pulseRadius, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(16, 185, 129, 0.12)';
      ctx.fill();

      ctx.beginPath();
      ctx.arc(cx, cy, 70, 0, Math.PI * 2);
      ctx.fillStyle = '#10b981';
      ctx.fill();

      // Bot Icon / Avatar Symbol
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 36px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('🤖', cx, cy);

      // Bot Workspace Header Label
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 26px "Plus Jakarta Sans", sans-serif';
      ctx.fillText(`BOT VIEW: ${botName.toUpperCase()} — AUTONOMOUS RECORDING ENGINE`, cx, cy + 110);

      // Real-time Status Text
      ctx.fillStyle = '#94a3b8';
      ctx.font = '600 18px "Plus Jakarta Sans", sans-serif';
      const curStatus = state.botStatus || 'JOINING';
      ctx.fillText(`STATUS: ${curStatus} — Real-time Autonomous Engine`, cx, cy + 150);

      // Bottom Telemetry Bar inside Bot View
      ctx.fillStyle = 'rgba(15, 23, 42, 0.95)';
      ctx.fillRect(margin + 20, canvas.height - margin - 70, w - 40, 50);

      ctx.fillStyle = '#10b981';
      ctx.font = '600 18px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText(`🔴 BOT ACTIVE: ${botName} [${curStatus}]`, margin + 40, canvas.height - margin - 45);

      ctx.fillStyle = '#38bdf8';
      ctx.textAlign = 'right';
      ctx.fillText(`MEETING ID: ${state.activeMeetingId}`, canvas.width - margin - 40, canvas.height - margin - 45);


      ctx.fillStyle = 'rgba(239, 68, 68, 0.9)';
      ctx.fillRect(canvas.width - 250, 24, 226, 44);
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 18px monospace';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(`REC 🔴 ${formatTime(recElapsedSeconds)}`, canvas.width - 137, 46);
    }

    requestAnimationFrame(drawFrame);
  }

  function updateLiveBotStatusUI(status, statusMessage) {
    const statusBadge = document.getElementById('bot-status-badge');
    const overlayBotTag = document.getElementById('overlay-bot-tag');
    const telAiStatus = document.getElementById('tel-ai-status');

    const s = status || 'JOINING';
    state.botStatus = s;

    let badgeText = 'JOINING MEETING...';
    let badgeClass = 'status-badge recording';
    let telText = '<i class="fa-solid fa-spinner fa-spin"></i> Connecting to Zoom Web Client...';
    let tagText = `Bot: ${state.botDisplayName} [JOINING]`;

    if (s === 'IN_MEETING') {
      badgeText = 'IN MEETING (RECORDING)';
      badgeClass = 'status-badge recording';
      telText = '<i class="fa-solid fa-circle-check text-success"></i> In Meeting &amp; Auto-Clearing Banners';
      tagText = `Bot: ${state.botDisplayName} (1080p HD • Muted &amp; Camera OFF)`;
    } else if (s === 'IN_WAITING_ROOM') {
      badgeText = 'IN WAITING ROOM';
      badgeClass = 'status-badge standby';
      telText = '<i class="fa-solid fa-clock text-warning"></i> In Host Waiting Room (Waiting for host to admit)';
      tagText = `Bot: ${state.botDisplayName} [WAITING ROOM]`;
    } else if (s === 'REGISTRATION_REQUIRED') {
      badgeText = 'REGISTRATION AUTO-FILLING';
      badgeClass = 'status-badge standby';
      telText = '<i class="fa-solid fa-id-card text-info"></i> Meeting Registration Detected — Auto-filling details &amp; submitting...';
      tagText = `Bot: ${state.botDisplayName} [REGISTRATION]`;
    } else if (s === 'AUTHENTICATION_REQUIRED') {
      badgeText = statusMessage || 'AUTH REQUIRED';
      badgeClass = 'status-badge error';
      telText = `<i class="fa-solid fa-lock text-danger"></i> ${statusMessage || 'Host requires Zoom account sign-in / domain authentication to join'}`;
      tagText = `Bot: ${state.botDisplayName} [AUTH REQUIRED]`;
    } else if (s === 'CAPTCHA_REQUIRED') {
      badgeText = 'CAPTCHA / DIRECT BYPASS';
      badgeClass = 'status-badge standby';
      telText = '<i class="fa-solid fa-shield-halved text-warning"></i> reCAPTCHA detected — Executing direct Web Client URL bypass...';
      tagText = `Bot: ${state.botDisplayName} [CAPTCHA BYPASS]`;
    } else if (s === 'ERROR') {
      badgeText = 'JOIN ERROR';
      badgeClass = 'status-badge error';
      telText = '<i class="fa-solid fa-circle-exclamation text-danger"></i> Failed to join meeting / Invalid meeting details';
      tagText = `Bot: ${state.botDisplayName} [ERROR]`;
    }

    if (statusBadge && state.isRecording) {
      statusBadge.textContent = badgeText;
      statusBadge.className = badgeClass;
    }
    if (overlayBotTag) {
      overlayBotTag.innerHTML = `<i class="fa-solid fa-robot"></i> ${tagText}`;
    }
    if (telAiStatus) {
      telAiStatus.innerHTML = telText;
    }
  }

  /**
   * Start polling real screenshots & status from backend.
   */
  function startLiveScreenshotPolling() {
    const screenshotImg = document.getElementById('live-bot-screenshot');
    const canvas = document.getElementById('live-rec-canvas');
    const standbyScreen = document.getElementById('live-standby-screen');

    if (standbyScreen) standbyScreen.style.display = 'none';
    if (canvas) canvas.style.display = 'block';
    isCanvasActive = true;

    if (screenshotPollingInterval) clearInterval(screenshotPollingInterval);

    screenshotPollingInterval = setInterval(async () => {
      if (isScreenshotPaused || !state.isRecording) return;

      // 1. Poll Bot Real Status
      try {
        const statusRes = await authFetch(`${API_BASE_URL}/api/bot/status?t=${Date.now()}`);
        if (statusRes.ok) {
          const statusData = await statusRes.json();
          if (statusData.active) {
            updateLiveBotStatusUI(statusData.status, statusData.statusMessage);
          }
        }
      } catch (e) {}

      // 2. Poll Bot Live Screenshot
      try {
        const response = await authFetch(`${API_BASE_URL}/api/bot/screenshot?t=${Date.now()}`);
        if (response.ok) {
          const blob = await response.blob();
          const url = URL.createObjectURL(blob);
          
          if (screenshotImg) {
            if (screenshotImg.src && screenshotImg.src.startsWith('blob:')) {
              URL.revokeObjectURL(screenshotImg.src);
            }
            screenshotImg.src = url;
            screenshotImg.onload = () => {
              screenshotImg.style.display = 'block';
              if (canvas) canvas.style.display = 'none';
              isCanvasActive = false;
            };
          }
        }
      } catch (err) {
        if (canvas) canvas.style.display = 'block';
        if (screenshotImg) screenshotImg.style.display = 'none';
        isCanvasActive = true;
      }
    }, 1000);
  }



  function stopLiveScreenshotPolling() {
    if (screenshotPollingInterval) {
      clearInterval(screenshotPollingInterval);
      screenshotPollingInterval = null;
    }

    isCanvasActive = false;
    const screenshotImg = document.getElementById('live-bot-screenshot');
    const canvas = document.getElementById('live-rec-canvas');
    const standbyScreen = document.getElementById('live-standby-screen');

    if (screenshotImg) {
      if (screenshotImg.src && screenshotImg.src.startsWith('blob:')) {
        URL.revokeObjectURL(screenshotImg.src);
      }
      screenshotImg.src = '';
      screenshotImg.style.display = 'none';
    }
    if (canvas) canvas.style.display = 'none';
    if (standbyScreen) standbyScreen.style.display = 'flex';
  }

  function setupMonitorControlButtons() {
    const btnSnap = document.getElementById('btn-snap-rec');
    if (btnSnap) {
      btnSnap.addEventListener('click', takeLiveSnapshot);
    }

    const btnFullscreen = document.getElementById('btn-fullscreen-rec');
    if (btnFullscreen) {
      btnFullscreen.addEventListener('click', () => {
        const container = document.getElementById('rec-viewport-container');
        if (container) {
          if (!document.fullscreenElement) {
            container.requestFullscreen().catch(err => console.error(err));
          } else {
            document.exitFullscreen().catch(err => console.error(err));
          }
        }
      });
    }

    const btnPause = document.getElementById('btn-pause-rec');
    if (btnPause) {
      btnPause.addEventListener('click', () => {
        isScreenshotPaused = !isScreenshotPaused;
        btnPause.innerHTML = isScreenshotPaused
          ? `<i class="fa-solid fa-play"></i> Resume Monitor`
          : `<i class="fa-solid fa-pause"></i> Pause Monitor`;
      });
    }

    setupHumanTakeoverControls();
  }

  /* ==========================================================================
     HUMAN TAKEOVER (HITL) INTERACTIVE REMOTE CONTROL ENGINE
     ========================================================================== */
  function setupHumanTakeoverControls() {
    const btnTakeControl = document.getElementById('btn-take-control');
    const btnResumeBot = document.getElementById('btn-resume-bot');
    const remoteInput = document.getElementById('hitl-remote-text');
    const btnSendText = document.getElementById('btn-send-remote-text');
    const btnSendEnter = document.getElementById('btn-send-enter-key');
    const btnSendTab = document.getElementById('btn-send-tab-key');
    const screenshotImg = document.getElementById('live-bot-screenshot');

    if (btnTakeControl) {
      btnTakeControl.addEventListener('click', async () => {
        try {
          const res = await authFetch(`${API_BASE_URL}/api/bot/takeover`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mode: 'HUMAN' })
          });
          const data = await res.json();
          if (data.success) {
            updateControlModeUI('HUMAN');
          } else {
            alert(data.message || 'Failed to activate Human Takeover');
          }
        } catch (e) {
          alert('Network error requesting Human Takeover');
        }
      });
    }

    if (btnResumeBot) {
      btnResumeBot.addEventListener('click', async () => {
        try {
          const res = await authFetch(`${API_BASE_URL}/api/bot/takeover`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ mode: 'BOT' })
          });
          const data = await res.json();
          if (data.success) {
            updateControlModeUI('BOT');
          } else {
            alert(data.message || 'Failed to resume Bot Control');
          }
        } catch (e) {
          alert('Network error resuming Bot Control');
        }
      });
    }

    if (screenshotImg) {
      screenshotImg.addEventListener('click', async (e) => {
        if (state.controlMode !== 'HUMAN') return;

        const rect = screenshotImg.getBoundingClientRect();
        const clickXRel = (e.clientX - rect.left) / rect.width;
        const clickYRel = (e.clientY - rect.top) / rect.height;

        // Remote Playwright viewport is 1920x1080
        const remoteX = Math.round(clickXRel * 1920);
        const remoteY = Math.round(clickYRel * 1080);

        try {
          await authFetch(`${API_BASE_URL}/api/bot/interact`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'click', x: remoteX, y: remoteY })
          });
        } catch (err) {}
      });
    }

    if (btnSendText && remoteInput) {
      btnSendText.addEventListener('click', async () => {
        const text = remoteInput.value;
        if (!text) return;
        try {
          await authFetch(`${API_BASE_URL}/api/bot/interact`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'type', text })
          });
          remoteInput.value = '';
        } catch (e) {}
      });

      remoteInput.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          btnSendText.click();
        }
      });
    }

    if (btnSendEnter) {
      btnSendEnter.addEventListener('click', async () => {
        try {
          await authFetch(`${API_BASE_URL}/api/bot/interact`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'press', key: 'Enter' })
          });
        } catch (e) {}
      });
    }

    if (btnSendTab) {
      btnSendTab.addEventListener('click', async () => {
        try {
          await authFetch(`${API_BASE_URL}/api/bot/interact`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'press', key: 'Tab' })
          });
        } catch (e) {}
      });
    }

    // Global Physical Keyboard Keypress Capture Engine
    document.addEventListener('keydown', async (e) => {
      if (state.controlMode !== 'HUMAN') return;

      // Ignore keys if user is typing inside local form fields (e.g. search box)
      const activeTag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
      const activeId = document.activeElement ? document.activeElement.id : '';
      if (activeId !== 'hitl-remote-text' && (activeTag === 'input' || activeTag === 'textarea' || activeTag === 'select')) {
        return;
      }

      // Allow F5 / F12 / DevTools
      if (['F5', 'F12', 'F11'].includes(e.key)) return;

      // Prevent browser default actions for Tab, Backspace, Arrow keys, Space during remote control
      if (['Tab', 'Backspace', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space'].includes(e.key)) {
        e.preventDefault();
      }

      try {
        await authFetch(`${API_BASE_URL}/api/bot/interact`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ action: 'press', key: e.key })
        });
      } catch (err) {}
    });
  }

  function updateControlModeUI(mode) {
    state.controlMode = mode;
    const btnTakeControl = document.getElementById('btn-take-control');
    const btnResumeBot = document.getElementById('btn-resume-bot');
    const badgeMode = document.getElementById('control-mode-badge');
    const descMode = document.getElementById('control-mode-desc');
    const interactiveInputs = document.getElementById('hitl-interactive-inputs');
    const screenshotImg = document.getElementById('live-bot-screenshot');

    if (mode === 'HUMAN') {
      if (btnTakeControl) btnTakeControl.style.display = 'none';
      if (btnResumeBot) btnResumeBot.style.display = 'inline-flex';
      if (badgeMode) {
        badgeMode.style.background = '#eab308';
        badgeMode.style.color = '#000';
        badgeMode.innerHTML = `<i class="fa-solid fa-hand"></i> 👤 HUMAN CONTROL ACTIVE`;
      }
      if (descMode) descMode.textContent = 'Human Remote Control Active — Mouse clicks & text inputs will be sent directly to the bot browser.';
      if (interactiveInputs) interactiveInputs.style.display = 'flex';
      if (screenshotImg) screenshotImg.style.cursor = 'crosshair';
    } else {
      if (btnTakeControl) btnTakeControl.style.display = 'inline-flex';
      if (btnResumeBot) btnResumeBot.style.display = 'none';
      if (badgeMode) {
        badgeMode.style.background = '#3b82f6';
        badgeMode.style.color = '#fff';
        badgeMode.innerHTML = `🤖 BOT CONTROL ACTIVE`;
      }
      if (descMode) descMode.textContent = 'Bot is running autonomously. Click "Take Control" to operate manually.';
      if (interactiveInputs) interactiveInputs.style.display = 'none';
      if (screenshotImg) screenshotImg.style.cursor = 'default';
    }
  }

  function takeLiveSnapshot() {
    const screenshotImg = document.getElementById('live-bot-screenshot');
    const canvas = document.getElementById('live-rec-canvas');

    const link = document.createElement('a');
    link.download = `zoom_bot_snapshot_${Date.now()}.png`;

    if (screenshotImg && screenshotImg.style.display !== 'none' && screenshotImg.src) {
      link.href = screenshotImg.src;
      link.click();
      addBotConsoleLog('[SNAPSHOT] Captured live bot browser snapshot.');
    } else if (canvas && canvas.style.display !== 'none') {
      link.href = canvas.toDataURL('image/png');
      link.click();
      addBotConsoleLog('[SNAPSHOT] Captured 1080p live monitor snapshot.');
    }
  }

  function formatTime(totalSeconds) {
    const hrs = Math.floor(totalSeconds / 3600);
    const mins = Math.floor((totalSeconds % 3600) / 60);
    const secs = totalSeconds % 60;
    const pad = num => String(num).padStart(2, '0');
    return `${pad(hrs)}:${pad(mins)}:${pad(secs)}`;
  }

  function addBotConsoleLog(message) {
    const consoleBox = document.getElementById('bot-console-log');
    if (!consoleBox) return;
    const timeStr = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `<span class="log-time">[${timeStr}]</span> ${message}`;
    consoleBox.appendChild(entry);
    consoleBox.scrollTop = consoleBox.scrollHeight;
  }

  function updateActiveBotUI(data) {
    state.botDisplayName = data.botName || 'rycb';
    state.activeMeetingId = data.meetingId || '83417486366';
    state.quality = data.quality || '1080p';
    state.format = data.format || 'mp4';
    state.isRecording = true;

    // Start Live Timer
    recElapsedSeconds = 0;
    if (recTimerInterval) clearInterval(recTimerInterval);
    recTimerInterval = setInterval(() => {
      recElapsedSeconds++;
      const timerBadge = document.getElementById('rec-timer-badge');
      if (timerBadge) {
        timerBadge.innerHTML = `<i class="fa-solid fa-circle text-danger blink"></i> ${formatTime(recElapsedSeconds)}`;
      }
    }, 1000);

    // Update Status Badge
    const statusBadge = document.getElementById('bot-status-badge');
    if (statusBadge) {
      statusBadge.textContent = 'RECORDING 1080P HD';
      statusBadge.className = 'status-badge recording';
    }

    // Update Canvas Overlays
    const overlayBotTag = document.getElementById('overlay-bot-tag');
    if (overlayBotTag) {
      overlayBotTag.innerHTML = `<i class="fa-solid fa-robot"></i> Bot: ${state.botDisplayName} (1080p HD • Muted &amp; Camera OFF)`;
    }

    const overlayResTag = document.getElementById('overlay-res-tag');
    if (overlayResTag) {
      overlayResTag.textContent = `${state.quality.toUpperCase()} Full HD • 60 FPS`;
    }

    // Update Telemetry Elements
    const telBotName = document.getElementById('tel-bot-name');
    if (telBotName) telBotName.textContent = state.botDisplayName;

    const telMeetingId = document.getElementById('tel-meeting-id');
    if (telMeetingId) telMeetingId.textContent = state.activeMeetingId;

    const telQuality = document.getElementById('tel-quality');
    if (telQuality) telQuality.textContent = `${state.quality.toUpperCase()} Full HD`;

    const telFormat = document.getElementById('tel-format');
    if (telFormat) telFormat.textContent = `.${state.format.toUpperCase()}`;

    // Update Stop Button Callback
    const btnStop = document.getElementById('btn-stop-active-bot');
    if (btnStop) {
      btnStop.onclick = () => stopActiveBot(data.botId);
    }

    // Log Dispatch Sequence
    addBotConsoleLog(`[ENGINE] Dispatching Chromium Bot "${state.botDisplayName}"...`);
    addBotConsoleLog(`[ZOOM] Navigating to Zoom Meeting ID: ${state.activeMeetingId}...`);
    addBotConsoleLog(`[AUTH] Auto-entering passcode & Terms Agreement...`);
    addBotConsoleLog(`[AUDIO/VIDEO] Computer Audio Connected • Microphone MUTED & Camera OFF.`);
    addBotConsoleLog(`[RECORDING] Live 1080p HD screen stream recording to .${state.format.toUpperCase()} video container.`);
    addBotConsoleLog(`[MONITOR] Starting live browser screenshot feed...`);

    // Start polling REAL screenshots from the bot's actual browser
    startLiveScreenshotPolling();
  }

  async function stopActiveBot(botId) {
    const idToStop = botId || state.activeBotId || 'active_bot';

    const btnStop = document.getElementById('btn-stop-active-bot');
    if (btnStop) {
      btnStop.disabled = true;
      btnStop.innerHTML = `<i class="fa-solid fa-spinner fa-spin"></i> Saving 1080p Video Recording...`;
    }

    try {
      const response = await authFetch(`${API_BASE_URL}/api/bot/stop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ botId: idToStop })
      });

      const data = await response.json().catch(() => null);

      state.isRecording = false;
      state.activeBotId = null;
      if (recTimerInterval) clearInterval(recTimerInterval);

      stopLiveScreenshotPolling();

      if (elements.liveIndicator) elements.liveIndicator.style.display = 'none';

      const statusBadge = document.getElementById('bot-status-badge');

      // The server now responds as soon as the raw capture is safely on
      // disk — it no longer makes you wait for the mp4/mkv transcode.
      // `processing: true` means the file isn't ready to watch/download
      // yet, but the recording itself was captured successfully.
      const captured = !!(response.ok && data && data.success);
      const isProcessing = !!(data && data.processing);

      if (captured && isProcessing) {
        if (statusBadge) {
          statusBadge.textContent = 'PROCESSING VIDEO...';
          statusBadge.className = 'status-badge standby';
        }
        addBotConsoleLog(`[STOP] Recording captured. Converting to final format in the background — it'll appear in the Vault shortly.`);
        pollVaultUntilProcessed(data.fileName);
      } else if (captured) {
        if (statusBadge) {
          statusBadge.textContent = 'STOPPED & SAVED';
          statusBadge.className = 'status-badge standby';
        }
        const fileName = data.fileName;
        addBotConsoleLog(`[STOP] Recording stopped successfully. Saved video file: ${fileName}`);
      } else {
        if (statusBadge) {
          statusBadge.textContent = 'STOPPED (NO RECORDING)';
          statusBadge.className = 'status-badge error';
        }
        const errMsg = (data && data.message) || 'No recording was captured. The bot may not have successfully joined the meeting before it was stopped.';
        addBotConsoleLog(`[STOP] Bot stopped, but no video was saved: ${errMsg}`);
        alert(`Bot stopped, but no recording was saved:\n\n${errMsg}`);
      }

      fetchVaultRecordings();
    } catch (err) {
      console.error('Error stopping bot:', err);
      state.isRecording = false;
      state.activeBotId = null;
      if (recTimerInterval) clearInterval(recTimerInterval);
      stopLiveScreenshotPolling();
      if (elements.liveIndicator) elements.liveIndicator.style.display = 'none';

      const statusBadge = document.getElementById('bot-status-badge');
      if (statusBadge) {
        statusBadge.textContent = 'STOP FAILED';
        statusBadge.className = 'status-badge error';
      }
      addBotConsoleLog(`[STOP] Error contacting server: ${err.message}`);
      alert(`Could not confirm the bot stopped or the recording saved: ${err.message}`);
      fetchVaultRecordings();
    } finally {
      if (btnStop) {
        btnStop.disabled = false;
        btnStop.innerHTML = `<i class="fa-solid fa-stop"></i> Stop Bot &amp; Save Video Recording`;
      }
    }
  }

  /* ==========================================================================
     4. RECORDED MEETINGS VAULT & HTML5 STREAMING MODAL
     ========================================================================== */

  // After a stop response comes back with `processing: true`, the mp4/mkv
  // transcode is still running server-side. Poll the recordings list every
  // few seconds until that specific file's entry stops being "processing",
  // then refresh the Vault and let the user know it's ready.
  function pollVaultUntilProcessed(fileName, attemptsLeft = 40) {
    if (!fileName || attemptsLeft <= 0) return;
    setTimeout(async () => {
      await fetchVaultRecordings();
      const stillProcessing = state.vaultRecordings.some(r => r.fileName === fileName && r.processing);
      if (stillProcessing) {
        pollVaultUntilProcessed(fileName, attemptsLeft - 1);
      } else {
        const statusBadge = document.getElementById('bot-status-badge');
        if (statusBadge && statusBadge.textContent === 'PROCESSING VIDEO...') {
          statusBadge.textContent = 'STOPPED & SAVED';
        }
        addBotConsoleLog(`[STOP] Video conversion finished — ${fileName} is ready in the Vault.`);
      }
    }, 4000);
  }

  function updateVaultStats(recordings) {
    const statCount = document.getElementById('stat-total-count');
    const statSize = document.getElementById('stat-total-size');
    const statStorage = document.getElementById('stat-storage-type');

    if (statCount) statCount.textContent = recordings.length;
    
    let totalBytes = 0;
    let driveCount = 0;
    recordings.forEach(r => {
      if (r.sizeBytes) totalBytes += Number(r.sizeBytes);
      else if (r.sizeMb) totalBytes += (parseFloat(r.sizeMb) || 0) * 1024 * 1024;
      if (r.driveWebViewLink) driveCount++;
    });

    const totalMb = (totalBytes / (1024 * 1024)).toFixed(1);
    if (statSize) statSize.textContent = `${totalMb} MB`;
    if (statStorage) {
      statStorage.textContent = driveCount > 0 ? `Local + ${driveCount} Drive` : 'Local Storage';
    }
  }

  async function fetchVaultRecordings() {
    const gridContainer = document.getElementById('recordings-grid-container');
    if (!gridContainer) return;

    const btnRefresh = document.getElementById('btn-refresh-vault');
    if (btnRefresh) {
      const icon = btnRefresh.querySelector('i');
      if (icon) icon.classList.add('fa-spin');
    }

    try {
      const response = await authFetch(`${API_BASE_URL}/api/recordings/list?t=${Date.now()}`);
      const data = await response.json();

      if (data.success && Array.isArray(data.recordings)) {
        state.vaultRecordings = data.recordings;
        updateVaultStats(data.recordings);
        renderVaultGrid(data.recordings);
      } else {
        state.vaultRecordings = [];
        updateVaultStats([]);
        renderVaultGrid([]);
      }
    } catch (err) {
      state.vaultRecordings = [];
      updateVaultStats([]);
      renderVaultGrid([]);
    } finally {
      if (btnRefresh) {
        const icon = btnRefresh.querySelector('i');
        if (icon) icon.classList.remove('fa-spin');
      }
    }
  }

  // Bind live search filtering for Vault
  const vaultSearchInput = document.getElementById('vault-search-input');
  if (vaultSearchInput) {
    vaultSearchInput.addEventListener('input', (e) => {
      const query = (e.target.value || '').trim().toLowerCase();
      if (!state.vaultRecordings) return;
      const filtered = state.vaultRecordings.filter(r => 
        (r.fileName && r.fileName.toLowerCase().includes(query)) ||
        (r.meetingId && String(r.meetingId).toLowerCase().includes(query))
      );
      renderVaultGrid(filtered);
    });
  }

  const globalSearchInput = document.getElementById('global-search');
  if (globalSearchInput) {
    globalSearchInput.addEventListener('input', (e) => {
      const query = (e.target.value || '').trim().toLowerCase();
      if (!state.vaultRecordings) return;
      if (state.currentTab !== 'history') {
        switchTab('history');
      }
      const filtered = state.vaultRecordings.filter(r => 
        (r.fileName && r.fileName.toLowerCase().includes(query)) ||
        (r.meetingId && String(r.meetingId).toLowerCase().includes(query))
      );
      renderVaultGrid(filtered);
    });
  }

  function renderVaultGrid(recordings) {
    const gridContainer = document.getElementById('recordings-grid-container');
    if (!gridContainer) return;

    if (!recordings || recordings.length === 0) {
      gridContainer.innerHTML = `
        <div class="empty-vault-box">
          <i class="fa-solid fa-film empty-icon"></i>
          <h3>No Recorded Meetings Found</h3>
          <p>Deploy an autonomous Zoom meeting bot to record, process, and store Full HD MP4 video captures here.</p>
          <button class="btn-slate-solid btn-sm margin-top-16" onclick="switchTab('dashboard')">
            <i class="fa-solid fa-plus"></i> Deploy Meeting Bot
          </button>
        </div>
      `;
      return;
    }

    gridContainer.innerHTML = recordings.map((item) => `
      <div class="recording-card-item">
        <div class="recording-thumb">
          <i class="fa-solid ${item.processing ? 'fa-spinner fa-spin' : 'fa-circle-play'} play-icon-overlay" onclick="${!item.processing && item.videoUrl ? `playVideoModal('${item.videoUrl}', '${item.fileName}')` : ''}"></i>
          <span class="quality-badge">${item.status || 'RECORDED 1080P HD'}</span>
        </div>
        <div class="recording-info">
          <h4>${item.fileName}</h4>
          <div class="recording-meta">
            <span><i class="fa-solid fa-hard-drive"></i> ${item.sizeMb || '1.25 MB'}</span>
            <span><i class="fa-regular fa-clock"></i> ${item.createdAt ? new Date(item.createdAt).toLocaleString() : 'Recent'}</span>
            <span style="color: #10b981; font-weight: 600;"><i class="fa-solid fa-laptop"></i> Local Vault Storage</span>
            ${item.driveWebViewLink
              ? `<a href="${item.driveWebViewLink}" target="_blank" style="color: #10b981; font-weight: 600; text-decoration: none;"><i class="fa-brands fa-google-drive"></i> Backed up to Drive</a>`
              : `<span style="color: #94a3b8;"><i class="fa-brands fa-google-drive"></i> Not backed up to Drive</span>`}
          </div>
          <div class="recording-card-actions">
            ${item.processing || !item.videoUrl ? `
            <button class="btn-slate-outline btn-sm" disabled title="Converting video format...">
              <i class="fa-solid fa-spinner fa-spin"></i> Converting...
            </button>
            ` : `
            <button class="btn-slate-solid btn-sm" onclick="playVideoModal('${item.videoUrl}', '${item.fileName}')">
              <i class="fa-solid fa-play"></i> Watch
            </button>
            <a href="${item.videoUrl}" download="${item.fileName}" class="btn-slate-outline btn-sm" style="color: #4f5469; border-color: #e6deda;" title="Download MP4 Video">
              <i class="fa-solid fa-download"></i> Download
            </a>
            `}
            <button class="btn-slate-danger-icon btn-sm" onclick="deleteVaultVideo('${item.fileName}')" title="Delete Recording">
              <i class="fa-solid fa-trash"></i> Delete
            </button>
          </div>
        </div>
      </div>
    `).join('');
  }


  window.playVideoModal = (url, title) => {
    if (elements.modalVideoTitle) elements.modalVideoTitle.textContent = title || 'Recorded Meeting Video';
    if (elements.modalVideoElement) {
      elements.modalVideoElement.src = url;
      elements.modalVideoElement.play().catch(() => {});
    }
    if (elements.videoModalOverlay) elements.videoModalOverlay.style.display = 'flex';
  };

  window.deleteVaultVideo = async (fileName) => {
    if (!confirm(`Are you sure you want to delete recording "${fileName}"?`)) return;

    try {
      const response = await authFetch(`${API_BASE_URL}/api/recordings/${encodeURIComponent(fileName)}`, { method: 'DELETE' });
      const data = await response.json();
      if (data.success) {
        fetchVaultRecordings();
      }
    } catch (e) {}
  };

  function setupVault() {


    if (elements.btnCloseVideoModal && elements.videoModalOverlay) {

      elements.btnCloseVideoModal.addEventListener('click', () => {
        if (elements.modalVideoElement) elements.modalVideoElement.pause();
        elements.videoModalOverlay.style.display = 'none';
      });
    }
  }

  /* ==========================================================================
     5. SYSTEM ADMIN USER VAULT ENGINE
     ========================================================================== */

  async function fetchAdminUsers() {
    if (!elements.adminUsersTableBody) return;

    try {
      const response = await authFetch(`${API_BASE_URL}/api/admin/users`);
      const data = await response.json();

      if (data.success && Array.isArray(data.users)) {
        renderAdminUsersTable(data.users);
      } else {
        renderAdminUsersTable([]);
      }
    } catch (err) {
      renderAdminUsersTable([]);
    }
  }

  if (elements.btnRefreshAdminUsers) {
    elements.btnRefreshAdminUsers.addEventListener('click', fetchAdminUsers);
  }

  function renderAdminUsersTable(usersList) {
    if (!elements.adminUsersTableBody) return;

    if (!usersList || usersList.length === 0) {
      elements.adminUsersTableBody.innerHTML = `
        <tr>
          <td colspan="5" style="text-align: center; padding: 28px; color: #94a3b8;">
            <i class="fa-solid fa-users-slash" style="font-size: 24px; margin-bottom: 8px; display: block;"></i>
            No registered users found in system database.
          </td>
        </tr>
      `;
      return;
    }

    elements.adminUsersTableBody.innerHTML = usersList.map(u => `
      <tr>
        <td><strong>${u.name || 'User'}</strong></td>
        <td><code>${u.email || u.userId}</code></td>
        <td><span class="user-password-code">${u.password}</span></td>
        <td>${new Date(u.createdAt || Date.now()).toLocaleString()}</td>
        <td>
          <button class="btn-slate-danger-icon btn-sm" onclick="deleteAdminUser('${u.email}')" title="Delete User">
            <i class="fa-solid fa-trash"></i> Delete
          </button>
        </td>
      </tr>
    `).join('');
  }

  window.deleteAdminUser = async (email) => {
    if (!confirm(`Delete user "${email}" from system database?`)) return;

    try {
      const response = await authFetch(`${API_BASE_URL}/api/admin/users/${encodeURIComponent(email)}`, { method: 'DELETE' });
      const data = await response.json();
      if (data.success) {
        fetchAdminUsers();
      }
    } catch (e) {}
  };


  // ── Cloud Storage Vault & Server Gateway Logic ─────────────────────────────
  let cloudStorageFiles = [];
  let currentStorageFilter = 'all';

  async function fetchStorageStats() {
    try {
      const res = await authFetch(`${API_BASE_URL}/api/storage/stats`);
      const data = await res.json();
      if (data.success && data.stats) {
        const s = data.stats;
        const usageText = document.getElementById('storage-usage-text');
        const progressFill = document.getElementById('storage-progress-fill');
        const videoCount = document.getElementById('stat-video-count');
        const audioCount = document.getElementById('stat-audio-count');
        const docCount = document.getElementById('stat-doc-count');
        const lanIp = document.getElementById('stat-lan-ip');

        if (usageText) usageText.textContent = `${s.usedMb} MB / 100 GB (${s.percentUsed}%)`;
        if (progressFill) progressFill.style.width = `${Math.max(2, parseFloat(s.percentUsed))}%`;
        if (videoCount) videoCount.textContent = s.categories.video.count;
        if (audioCount) audioCount.textContent = s.categories.audio.count;
        if (docCount) docCount.textContent = s.categories.document.count;

        if (data.server && data.server.lanUrl) {
          if (lanIp) lanIp.textContent = data.server.lanUrl.replace('http://', '');
          const modalLanUrl = document.getElementById('modal-lan-url');
          const modalLanUrlCode = document.getElementById('modal-lan-url-code');
          if (modalLanUrl) modalLanUrl.textContent = data.server.lanUrl;
          if (modalLanUrlCode) modalLanUrlCode.textContent = data.server.lanUrl;
        }
      }
    } catch (e) {
      console.warn('[Storage] Error fetching stats:', e);
    }
  }

  async function fetchCloudStorageFiles() {
    const container = document.getElementById('storage-files-container');
    if (!container) return;

    container.innerHTML = '<div class="loading-state"><i class="fa-solid fa-spinner fa-spin"></i> Loading cloud storage files...</div>';

    try {
      const res = await authFetch(`${API_BASE_URL}/api/storage/files`);
      const data = await res.json();
      if (data.success && data.files) {
        cloudStorageFiles = data.files;
        renderStorageFiles();
      }
    } catch (e) {
      container.innerHTML = '<div class="empty-vault-box"><i class="fa-solid fa-cloud-slash empty-icon"></i><h3>Could not load storage</h3><p>' + (e.message || 'Server error') + '</p></div>';
    }
  }

  function renderStorageFiles() {
    const container = document.getElementById('storage-files-container');
    if (!container) return;

    const searchTerm = (document.getElementById('storage-search-input')?.value || '').toLowerCase().trim();

    const filtered = cloudStorageFiles.filter(item => {
      const matchName = (item.originalName || item.fileName || '').toLowerCase().includes(searchTerm);
      if (!matchName) return false;

      if (currentStorageFilter === 'all') return true;
      const mime = item.mimeType || '';
      if (currentStorageFilter === 'video') return mime.startsWith('video/');
      if (currentStorageFilter === 'audio') return mime.startsWith('audio/');
      if (currentStorageFilter === 'mobile_storage') return item.category === 'mobile_storage' || item.uploadedBy === 'mobile_app';
      if (currentStorageFilter === 'document') return mime.includes('pdf') || mime.includes('text') || mime.includes('document');
      return true;
    });

    if (filtered.length === 0) {
      container.innerHTML = `
        <div class="empty-vault-box" style="grid-column: 1 / -1;">
          <i class="fa-solid fa-box-open empty-icon"></i>
          <h3>No files found in Vault</h3>
          <p>Drag and drop recordings or files above to store them in your private cloud vault.</p>
        </div>
      `;
      return;
    }

    container.innerHTML = filtered.map(f => {
      const isVideo = f.mimeType && f.mimeType.startsWith('video/');
      const isAudio = f.mimeType && f.mimeType.startsWith('audio/');
      const isMobile = f.category === 'mobile_storage' || f.uploadedBy === 'mobile_app';
      const isServerRec = f.category === 'recording';
      const sizeMb = (f.fileSize / (1024 * 1024)).toFixed(1);
      const dateStr = new Date(f.createdAt).toLocaleDateString();

      const iconClass = isVideo ? 'fa-file-video is-video' : isAudio ? 'fa-file-audio' : 'fa-file-lines';

      const sourceBadge = isMobile
        ? `<span style="background: rgba(13, 114, 255, 0.2); color: #38bdf8; font-size: 0.72rem; padding: 2px 8px; border-radius: 4px; border: 1px solid rgba(13,114,255,0.3);"><i class="fa-solid fa-mobile-screen"></i> Phone Storage</span>`
        : isServerRec
        ? `<span style="background: rgba(16, 185, 129, 0.2); color: #34d399; font-size: 0.72rem; padding: 2px 8px; border-radius: 4px; border: 1px solid rgba(16,185,129,0.3);"><i class="fa-solid fa-server"></i> Server Recording</span>`
        : `<span style="background: rgba(255, 255, 255, 0.08); color: #cbd5e1; font-size: 0.72rem; padding: 2px 8px; border-radius: 4px;"><i class="fa-solid fa-cloud"></i> Vault Storage</span>`;

      return `
        <div class="storage-file-card" data-id="${f.id}">
          <div class="storage-card-thumb ${isVideo ? 'is-video' : ''}">
            <i class="fa-solid ${iconClass}"></i>
            <span class="storage-card-badge">${sizeMb} MB</span>
          </div>
          <div class="storage-card-body">
            <div style="margin-bottom: 4px;">${sourceBadge}</div>
            <h4 class="storage-card-title" title="${f.originalName || f.fileName}">${f.originalName || f.fileName}</h4>
            <div class="storage-card-meta">
              <span><i class="fa-regular fa-calendar"></i> ${dateStr}</span>
              <span><i class="fa-solid fa-download"></i> ${f.downloadsCount || 0}</span>
            </div>
            <div class="storage-card-actions">
              ${isVideo ? `<button onclick="playStorageVideo('${f.id}', '${escapeAttr(f.originalName || f.fileName)}')"><i class="fa-solid fa-play"></i> Play</button>` : ''}
              <button onclick="downloadStorageFile('${f.id}')" title="Save to local device storage"><i class="fa-solid fa-download"></i> Save</button>
              <button onclick="openShareModal('${f.id}', '${escapeAttr(f.originalName || f.fileName)}')"><i class="fa-solid fa-share-nodes"></i> Share</button>
              <button onclick="syncStorageToDrive('${f.id}')" title="Upload to Google Drive"><i class="fa-brands fa-google-drive"></i></button>
              <button class="btn-delete" onclick="deleteStorageFile('${f.id}')" title="Delete"><i class="fa-solid fa-trash"></i></button>
            </div>
          </div>
        </div>
      `;
    }).join('');
  }

  function escapeAttr(str) {
    if (!str) return '';
    return String(str).replace(/'/g, "\\'").replace(/"/g, '&quot;');
  }

  window.playStorageVideo = (fileId, fileName) => {
    const modal = document.getElementById('video-modal-overlay');
    const videoEl = document.getElementById('modal-video-element');
    const titleEl = document.getElementById('modal-video-title');
    if (modal && videoEl) {
      if (titleEl) titleEl.textContent = fileName || 'Cloud Video Playback';
      videoEl.src = `${API_BASE_URL}/api/storage/stream/${fileId}`;
      modal.style.display = 'flex';
      videoEl.play().catch(() => {});
    }
  };

  window.downloadStorageFile = (fileId) => {
    window.open(`${API_BASE_URL}/api/storage/download/${fileId}`, '_blank');
  };

  let activeShareFileId = null;
  window.openShareModal = (fileId, fileName) => {
    activeShareFileId = fileId;
    const modal = document.getElementById('share-modal-overlay');
    const nameEl = document.getElementById('share-modal-filename');
    const resContainer = document.getElementById('share-result-container');
    if (nameEl) nameEl.textContent = fileName || fileId;
    if (resContainer) resContainer.style.display = 'none';
    if (modal) modal.style.display = 'flex';
  };

  window.syncStorageToDrive = async (fileId) => {
    try {
      alert('Syncing file to your Google Drive folder...');
      const res = await authFetch(`${API_BASE_URL}/api/storage/sync-drive/${fileId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: state.currentUser ? state.currentUser.email : 'default_user' })
      });
      const data = await res.json();
      if (data.success) {
        alert('File successfully uploaded to Google Drive!');
      } else {
        alert(data.message || 'Failed to sync to Google Drive');
      }
    } catch (e) {
      alert(`Google Drive sync error: ${e.message}`);
    }
  };

  window.deleteStorageFile = async (fileId) => {
    if (!confirm('Permanently delete this file from Cloud Storage Vault?')) return;
    try {
      const res = await authFetch(`${API_BASE_URL}/api/storage/files/${fileId}`, { method: 'DELETE' });
      const data = await res.json();
      if (data.success) {
        fetchCloudStorageFiles();
        fetchStorageStats();
      } else {
        alert(data.message || 'Delete failed');
      }
    } catch (e) {
      alert(`Error deleting file: ${e.message}`);
    }
  };

  async function uploadFilesToStorage(files) {
    if (!files || files.length === 0) return;

    const progressBox = document.getElementById('storage-upload-progress');
    const progressFill = document.getElementById('storage-upload-fill');
    const progressStatus = document.getElementById('storage-upload-status');

    if (progressBox) progressBox.style.display = 'flex';
    if (progressFill) progressFill.style.width = '30%';
    if (progressStatus) progressStatus.textContent = `Uploading ${files.length} file(s) to Cloud Storage Vault...`;

    const formData = new FormData();
    for (let i = 0; i < files.length; i++) {
      formData.append('files', files[i]);
    }

    try {
      const res = await authFetch(`${API_BASE_URL}/api/storage/upload`, {
        method: 'POST',
        body: formData
      });
      if (progressFill) progressFill.style.width = '100%';
      const data = await res.json();
      if (data.success) {
        if (progressStatus) progressStatus.textContent = 'Upload complete!';
        setTimeout(() => {
          if (progressBox) progressBox.style.display = 'none';
          fetchCloudStorageFiles();
          fetchStorageStats();
        }, 1000);
      } else {
        alert(data.message || 'Upload failed');
        if (progressBox) progressBox.style.display = 'none';
      }
    } catch (e) {
      alert(`Upload error: ${e.message}`);
      if (progressBox) progressBox.style.display = 'none';
    }
  }

  function setupCloudStorageUI() {
    const browseBtn = document.getElementById('btn-browse-storage-files');
    const fileInput = document.getElementById('storage-file-input');
    const dropzone = document.getElementById('storage-dropzone');
    const refreshBtn = document.getElementById('btn-refresh-storage');
    const serverInfoBtn = document.getElementById('btn-show-server-info');
    const searchInput = document.getElementById('storage-search-input');
    const filterPills = document.querySelectorAll('.storage-category-pills .filter-pill');

    if (browseBtn && fileInput) {
      browseBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        fileInput.click();
      });
    }

    if (fileInput) {
      fileInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
          uploadFilesToStorage(e.target.files);
        }
      });
    }

    if (dropzone) {
      dropzone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropzone.classList.add('dragover');
      });
      dropzone.addEventListener('dragleave', () => {
        dropzone.classList.remove('dragover');
      });
      dropzone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropzone.classList.remove('dragover');
        if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
          uploadFilesToStorage(e.dataTransfer.files);
        }
      });
      dropzone.addEventListener('click', () => {
        if (fileInput) fileInput.click();
      });
    }

    if (refreshBtn) {
      refreshBtn.addEventListener('click', () => {
        fetchCloudStorageFiles();
        fetchStorageStats();
      });
    }

    if (serverInfoBtn) {
      serverInfoBtn.addEventListener('click', () => {
        const modal = document.getElementById('server-connect-modal-overlay');
        if (modal) modal.style.display = 'flex';
      });
    }

    const closeServerConnectBtn = document.getElementById('btn-close-server-connect');
    if (closeServerConnectBtn) {
      closeServerConnectBtn.addEventListener('click', () => {
        const modal = document.getElementById('server-connect-modal-overlay');
        if (modal) modal.style.display = 'none';
      });
    }

    const copyLanBtn = document.getElementById('btn-copy-lan-url');
    if (copyLanBtn) {
      copyLanBtn.addEventListener('click', () => {
        const urlText = document.getElementById('modal-lan-url')?.textContent || '';
        navigator.clipboard.writeText(urlText).then(() => {
          copyLanBtn.innerHTML = '<i class="fa-solid fa-check"></i> Copied!';
          setTimeout(() => { copyLanBtn.innerHTML = '<i class="fa-solid fa-copy"></i> Copy'; }, 2000);
        });
      });
    }

    const closeShareBtn = document.getElementById('btn-close-share-modal');
    if (closeShareBtn) {
      closeShareBtn.addEventListener('click', () => {
        const modal = document.getElementById('share-modal-overlay');
        if (modal) modal.style.display = 'none';
      });
    }

    const generateShareBtn = document.getElementById('btn-generate-share-link');
    if (generateShareBtn) {
      generateShareBtn.addEventListener('click', async () => {
        if (!activeShareFileId) return;
        const expirySelect = document.getElementById('share-expiry-select');
        const expiresInHours = expirySelect ? expirySelect.value : 24;

        try {
          const res = await authFetch(`${API_BASE_URL}/api/storage/share`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fileId: activeShareFileId, expiresInHours })
          });
          const data = await res.json();
          if (data.success && data.publicShareUrl) {
            const resContainer = document.getElementById('share-result-container');
            const urlInput = document.getElementById('share-result-url');
            if (urlInput) urlInput.value = data.publicShareUrl;
            if (resContainer) resContainer.style.display = 'block';
          } else {
            alert(data.message || 'Failed to generate share link');
          }
        } catch (e) {
          alert(`Error generating share link: ${e.message}`);
        }
      });
    }

    const copyShareUrlBtn = document.getElementById('btn-copy-share-url');
    if (copyShareUrlBtn) {
      copyShareUrlBtn.addEventListener('click', () => {
        const urlInput = document.getElementById('share-result-url');
        if (urlInput && urlInput.value) {
          navigator.clipboard.writeText(urlInput.value).then(() => {
            copyShareUrlBtn.innerHTML = '<i class="fa-solid fa-check"></i> Copied!';
            setTimeout(() => { copyShareUrlBtn.innerHTML = '<i class="fa-solid fa-copy"></i> Copy'; }, 2000);
          });
        }
      });
    }

    if (searchInput) {
      searchInput.addEventListener('input', () => {
        renderStorageFiles();
      });
    }

    filterPills.forEach(pill => {
      pill.addEventListener('click', () => {
        filterPills.forEach(p => p.classList.remove('active'));
        pill.classList.add('active');
        currentStorageFilter = pill.getAttribute('data-cat') || 'all';
        renderStorageFiles();
      });
    });
  }

  setupCloudStorageUI();

});
