const API_BASE = (window.electronAPI && window.electronAPI.backendUrl) || 'http://localhost:3000';

let currentTab = 'dashboard';
let activeJob = null;
let timerInterval = null;
let elapsedSeconds = 0;

// ── Navigation Handler ────────────────────────────────────────────────
document.querySelectorAll('.nav-item').forEach(item => {
  item.addEventListener('click', () => {
    document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
    item.classList.add('active');
    currentTab = item.dataset.tab;
    renderView();
  });
});

// ── Main View Renderer ────────────────────────────────────────────────
function renderView() {
  const container = document.getElementById('content-view');

  if (currentTab === 'dashboard') {
    renderDashboard(container);
  } else if (currentTab === 'new-recording') {
    renderNewRecording(container);
  } else if (currentTab === 'recordings') {
    renderRecordings(container);
  } else if (currentTab === 'settings') {
    renderSettings(container);
  }
}

// ── Dashboard View ────────────────────────────────────────────────────
function renderDashboard(container) {
  container.innerHTML = `
    <h1 class="header-title">Meeting Recorder Dashboard</h1>
    <p class="header-subtitle">Real-time meeting monitoring, RTMS stream status & controls</p>

    ${activeJob ? renderLiveHudHtml() : ''}

    <div class="cards-grid">
      <div class="card">
        <div class="card-title">Quick Action</div>
        <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 16px;">
          Enter a Zoom meeting URL or meeting number to deploy the RTMS recorder bot immediately.
        </p>
        <button class="btn btn-primary" onclick="switchTab('new-recording')">
          <span>⏺️</span> Start New Recording
        </button>
      </div>

      <div class="card">
        <div class="card-title">System & Storage</div>
        <div style="font-size: 28px; font-weight: 700; color: #38BDF8; margin-bottom: 4px;">Ready</div>
        <p style="color: var(--text-muted); font-size: 12px;">SQLite Database & Local Media Storage active</p>
      </div>
    </div>

    <div class="card">
      <div class="card-title">Recent Meetings & Jobs</div>
      <div id="recent-jobs-list">
        <p style="color: var(--text-muted); font-size: 13px;">Loading recent jobs...</p>
      </div>
    </div>
  `;

  loadRecentJobs();
}

// ── New Recording View ────────────────────────────────────────────────
function renderNewRecording(container) {
  container.innerHTML = `
    <h1 class="header-title">Start Meeting Recording</h1>
    <p class="header-subtitle">Deploy dedicated RTMS bot participant with synchronized audio/video capture</p>

    <div class="card" style="max-width: 600px;">
      <form id="record-form" onsubmit="handleStartRecording(event)">
        <div class="input-group">
          <label class="input-label">Zoom Meeting URL or ID</label>
          <input type="text" id="meeting-url" class="text-input" placeholder="https://zoom.us/j/123456789?pwd=..." required />
        </div>

        <div class="input-group">
          <label class="input-label">Passcode (Optional if embedded in URL)</label>
          <input type="text" id="meeting-pwd" class="text-input" placeholder="Passcode if required" />
        </div>

        <div class="input-group">
          <label class="input-label">Session / Recording Name</label>
          <input type="text" id="meeting-topic" class="text-input" placeholder="e.g. Q3 Strategic Planning" />
        </div>

        <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 10px;">
          <span>🚀</span> Deploy & Start Recording
        </button>
      </form>
    </div>
  `;
}

// ── Recordings Library View ───────────────────────────────────────────
async function renderRecordings(container) {
  container.innerHTML = `
    <h1 class="header-title">Recordings Library</h1>
    <p class="header-subtitle">Exported high-definition MP4 recordings & transcripts</p>

    <div class="card">
      <table class="table-container">
        <thead>
          <tr>
            <th>Recording Name / File</th>
            <th>Duration</th>
            <th>File Size</th>
            <th>Recorded At</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody id="recordings-tbody">
          <tr><td colspan="5" style="text-align: center; color: var(--text-muted);">Loading recordings...</td></tr>
        </tbody>
      </table>
    </div>
  `;

  loadRecordingsList();
}

// ── Settings View ─────────────────────────────────────────────────────
function renderSettings(container) {
  container.innerHTML = `
    <h1 class="header-title">Settings & Authorization</h1>
    <p class="header-subtitle">Zoom OAuth, Telegram controls, and storage paths</p>

    <div class="cards-grid">
      <div class="card">
        <div class="card-title">Zoom Authorization</div>
        <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 16px;">
          Connect your dedicated Zoom user account for authorized meeting entry and RTMS streaming.
        </p>
        <button class="btn btn-primary" onclick="window.open('${API_BASE}/oauth/zoom/authorize', '_blank')">
          <span>🔑</span> Connect / Re-authorize Zoom
        </button>
      </div>

      <div class="card">
        <div class="card-title">Telegram Control Plane</div>
        <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 8px;">
          Commands: <code>/record</code>, <code>/stop</code>, <code>/status</code>, <code>/recordings</code>
        </p>
        <div style="font-size: 12px; color: var(--accent-green); font-weight: 600;">
          🟢 Bot Listener Active
        </div>
      </div>
    </div>
  `;
}

// ── Live HUD HTML Component ───────────────────────────────────────────
function renderLiveHudHtml() {
  const formattedTime = formatDuration(elapsedSeconds);
  return `
    <div class="live-hud" style="margin-bottom: 24px;">
      <div class="hud-header">
        <div class="rec-indicator">
          <div class="rec-pulse"></div>
          <span>REC • LIVE MEETING IN PROGRESS</span>
        </div>
        <div style="color: var(--text-muted); font-size: 13px;">Job ID: <b>${activeJob.id}</b></div>
      </div>

      <div style="display: flex; align-items: center; justify-content: space-between;">
        <div>
          <div class="hud-timer">${formattedTime}</div>
          <div style="color: var(--text-muted); font-size: 13px; margin-top: 4px;">
            ${activeJob.meeting?.topic || 'Zoom Session'} • Meeting ID: ${activeJob.meeting?.zoom_meeting_id || 'N/A'}
          </div>
        </div>

        <div class="waveform-container">
          <div class="wave-bar" style="animation-delay: 0.1s;"></div>
          <div class="wave-bar" style="animation-delay: 0.3s;"></div>
          <div class="wave-bar" style="animation-delay: 0.2s;"></div>
          <div class="wave-bar" style="animation-delay: 0.4s;"></div>
          <div class="wave-bar" style="animation-delay: 0.15s;"></div>
          <div class="wave-bar" style="animation-delay: 0.35s;"></div>
          <div class="wave-bar" style="animation-delay: 0.25s;"></div>
        </div>

        <button class="btn btn-danger" onclick="handleStopRecording()">
          <span>⏹️</span> Stop & Finalize MP4
        </button>
      </div>
    </div>
  `;
}

// ── API Actions & Handlers ───────────────────────────────────────────
async function handleStartRecording(event) {
  event.preventDefault();
  const url = document.getElementById('meeting-url').value;
  const pwd = document.getElementById('meeting-pwd').value;
  const topic = document.getElementById('meeting-topic').value;

  try {
    const res = await fetch(`${API_BASE}/api/recordings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        meetingUrl: url,
        password: pwd,
        topic: topic,
        requestedBy: 'desktop-user'
      })
    });

    const data = await res.json();
    if (data.success) {
      activeJob = data.job;
      elapsedSeconds = 0;
      startLiveTimer();
      switchTab('dashboard');
    } else {
      alert('Failed: ' + (data.message || 'Error creating recording job'));
    }
  } catch (err) {
    alert('Error connecting to backend API: ' + err.message);
  }
}

async function handleStopRecording() {
  if (!activeJob) return;

  try {
    await fetch(`${API_BASE}/api/recordings/${activeJob.id}/stop`, { method: 'POST' });
    clearInterval(timerInterval);
    activeJob = null;
    elapsedSeconds = 0;
    renderView();
    setTimeout(() => {
      switchTab('recordings');
    }, 1200);
  } catch (err) {
    alert('Error stopping recording: ' + err.message);
  }
}

function startLiveTimer() {
  clearInterval(timerInterval);
  timerInterval = setInterval(() => {
    elapsedSeconds++;
    const timerEl = document.querySelector('.hud-timer');
    if (timerEl) {
      timerEl.textContent = formatDuration(elapsedSeconds);
    }
  }, 1000);
}

function formatDuration(sec) {
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll('.nav-item').forEach(i => {
    if (i.dataset.tab === tab) i.classList.add('active');
    else i.classList.remove('active');
  });
  renderView();
}

async function loadRecentJobs() {
  try {
    const res = await fetch(`${API_BASE}/api/recordings?limit=5`);
    const data = await res.json();
    const listEl = document.getElementById('recent-jobs-list');
    if (!listEl) return;

    if (!data.recordings || data.recordings.length === 0) {
      listEl.innerHTML = `<p style="color: var(--text-muted); font-size: 13px;">No recording history yet.</p>`;
      return;
    }

    let html = '<div style="display: flex; flex-direction: column; gap: 10px;">';
    data.recordings.forEach(r => {
      html += `
        <div style="display: flex; justify-content: space-between; padding: 10px; background: rgba(255,255,255,0.02); border-radius: 8px;">
          <div>
            <b>${r.topic || 'Zoom Recording'}</b>
            <div style="color: var(--text-muted); font-size: 12px;">${r.object_key || 'meeting.mp4'}</div>
          </div>
          <div style="text-align: right;">
            <span style="color: var(--accent-green); font-weight: 600;">Completed</span>
            <div style="color: var(--text-muted); font-size: 12px;">${r.duration_seconds || 0}s</div>
          </div>
        </div>
      `;
    });
    html += '</div>';
    listEl.innerHTML = html;
  } catch (e) {
    const listEl = document.getElementById('recent-jobs-list');
    if (listEl) listEl.innerHTML = `<p style="color: var(--text-muted); font-size: 13px;">Ready for new recordings.</p>`;
  }
}

async function loadRecordingsList() {
  try {
    const res = await fetch(`${API_BASE}/api/recordings`);
    const data = await res.json();
    const tbody = document.getElementById('recordings-tbody');
    if (!tbody) return;

    if (!data.recordings || data.recordings.length === 0) {
      tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-muted);">No recordings available yet.</td></tr>`;
      return;
    }

    let html = '';
    data.recordings.forEach(r => {
      const sizeMb = ((r.size_bytes || 0) / (1024 * 1024)).toFixed(2);
      html += `
        <tr>
          <td><b>${r.topic || 'Zoom Meeting'}</b><br><small style="color: var(--text-muted);">${r.object_key || 'meeting.mp4'}</small></td>
          <td>${r.duration_seconds || 0}s</td>
          <td>${sizeMb} MB</td>
          <td>${(r.created_at || '').slice(0, 16)}</td>
          <td>
            <button class="btn btn-primary" style="padding: 6px 12px; font-size: 12px;" onclick="alert('File ready at: ' + '${r.file_path || 'recordings/' + r.object_key}')">
              Open File
            </button>
          </td>
        </tr>
      `;
    });
    tbody.innerHTML = html;
  } catch (e) {
    const tbody = document.getElementById('recordings-tbody');
    if (tbody) tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-muted);">No recordings found.</td></tr>`;
  }
}

// Initial view mount
renderView();
