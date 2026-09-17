// dashboard.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

// ── USER INIT ──
if (!localStorage.getItem('token')) window.location.href = 'login.html';
const user        = JSON.parse(localStorage.getItem('user') || '{}');
const fullName    = user.fullName || user.name || 'User';
const firstName   = fullName.split(' ')[0];
const userRole    = (user.role || 'beneficiary').toLowerCase();
const roleDisplay = userRole.charAt(0).toUpperCase() + userRole.slice(1);
const initials    = fullName.split(' ').map(n => n[0]).join('').slice(0,2).toUpperCase();
const isProvider  = userRole === 'volunteer' || userRole === 'organization';
const providerBannerKey = `nidaa_provider_setup_dismissed_${user.id || user.email || 'me'}`;

function setEl(id, val) { const e = document.getElementById(id); if (e) e.textContent = val; }
setEl('sidebarName',   fullName);
setEl('sidebarRole',   roleDisplay);
setEl('sidebarAvatar', initials);
setEl('topbarName',    firstName);
setEl('topbarRole',    roleDisplay);
setEl('welcomeName',   firstName);

function toggleSidebar() { document.getElementById('sidebar').classList.toggle('open'); }

// ── BUILD SIDEBAR BASED ON ROLE ──

buildSidebar('dashboard.html');



function dismissProviderSetupBanner() {
  document.getElementById('providerSetupBanner').hidden = true;
  localStorage.setItem(providerBannerKey, 'true');
}

async function checkProviderSetup() {
  if (!isProvider || localStorage.getItem(providerBannerKey) === 'true') return;
  try {
    const response = await apiFetch(`${API}/provider-resources/me`, { headers: authHeader() });
    if (!response.ok) return;
    const resources = (await response.json()).data;
    if (Array.isArray(resources) && resources.length === 0) {
      document.getElementById('providerSetupBanner').hidden = false;
    }
  } catch (ignored) {}
}


// Set user info in sidebar/topbar

// ==================== ROLE-BASED DASHBOARD ====================
function setupDashboardByRole() {
  const subtitle = document.getElementById('welcomeSubtitle');
  const actions = document.getElementById('actionButtons');
  const sidePanel = document.getElementById('sidePanels');
  const tableTitle = document.getElementById('tableTitle');

  if (userRole === 'beneficiary') {
    subtitle.textContent = 'Track your help requests and support sessions.';
    tableTitle.textContent = 'My Help Requests';
    actions.innerHTML = `
      <a href="help-requests.html" class="btn-sm btn-sm-primary" style="text-decoration:none;">+ New Help Request</a>
      <a href="psychological.html" class="btn-sm btn-sm-outline" style="text-decoration:none;">Request Support</a>`;
    document.getElementById('labelTotal').textContent = 'My Requests';
    document.getElementById('labelPending').textContent = 'Awaiting Help';
    sidePanel.innerHTML = `
      <div class="panel">
        <div class="panel-header"><h3>Need Help?</h3></div>
        <div class="impact-item" style="flex-direction:column;align-items:flex-start;gap:12px;">
          <p style="font-size:14px;color:#475569;line-height:1.6;">You can request food, medical, shelter, clothing, water, or psychological support.</p>
          <a href="help-requests.html" class="btn-sm btn-sm-primary" style="text-decoration:none;width:100%;text-align:center;">Submit a Request</a>
          <a href="psychological.html" class="btn-sm btn-sm-outline" style="text-decoration:none;width:100%;text-align:center;">Psychological Support</a>
        </div>
      </div>
      <div class="panel">
        <div class="panel-header"><h3>Emergency Contact</h3></div>
        <div class="impact-item"><div class="impact-label">Support Email</div><div class="impact-val" style="font-size:12px;">supp0rtnidaa@yandex.ru</div></div>
      </div>`;

  } else if (userRole === 'volunteer') {
    subtitle.textContent = 'Find requests near you and make a difference today.';
    tableTitle.textContent = 'Available Help Requests';
    actions.innerHTML = `
      <a href="help-requests.html" class="btn-sm btn-sm-primary" style="text-decoration:none;">View All Requests</a>
      <a href="help-requests.html" class="btn-sm btn-sm-outline" style="text-decoration:none;">My Accepted Tasks</a>`;
    document.getElementById('labelTotal').textContent = 'Open Requests';
    document.getElementById('labelPending').textContent = 'Needs Volunteer';
    sidePanel.innerHTML = `
      <div class="panel">
        <div class="panel-header"><h3>Your Impact</h3></div>
        <div class="impact-item"><div class="impact-label">Tasks Accepted</div><div class="impact-val" id="impactAccepted">—</div></div>
        <div class="impact-item"><div class="impact-label">Tasks Completed</div><div class="impact-val" id="impactCompleted">—</div></div>
        <div class="impact-item"><div class="impact-label">Status</div><div class="impact-val" style="color:#047857;">Active ✓</div></div>
      </div>`;

  } else if (userRole === 'psychologist') {
    subtitle.textContent = 'View incoming support requests and help those in need.';
    tableTitle.textContent = 'Pending Psychological Requests';
    actions.innerHTML = `
      <a href="psychological.html" class="btn-sm btn-sm-primary" style="text-decoration:none;">View Support Requests</a>`;
    document.getElementById('labelTotal').textContent = 'Support Requests';
    document.getElementById('labelPending').textContent = 'Awaiting You';
    sidePanel.innerHTML = `
      <div class="panel">
        <div class="panel-header"><h3>Your Sessions</h3></div>
        <div class="impact-item"><div class="impact-label">Pending Requests</div><div class="impact-val" id="impactPending">—</div></div>
        <div class="impact-item"><div class="impact-label">Completed</div><div class="impact-val" id="impactDone">—</div></div>
        <div class="impact-item"><div class="impact-label">Verification</div><div class="impact-val" style="color:#047857;">Verified ✓</div></div>
      </div>`;

  } else if (userRole === 'organization') {
    subtitle.textContent = 'Monitor requests, coordinate volunteers and track outcomes.';
    tableTitle.textContent = 'All Help Requests';
    actions.innerHTML = `
      <a href="help-requests.html" class="btn-sm btn-sm-primary" style="text-decoration:none;">Manage Requests</a>`;
    sidePanel.innerHTML = `
      <div class="panel">
        <div class="panel-header"><h3>Platform Overview</h3></div>
        <div class="impact-item"><div class="impact-label">Total Requests</div><div class="impact-val" id="impactTotal">—</div></div>
        <div class="impact-item"><div class="impact-label">Completed</div><div class="impact-val" id="impactDone">—</div></div>
        <div class="impact-item"><div class="impact-label">Pending</div><div class="impact-val" id="impactPending">—</div></div>
      </div>`;

  } else if (userRole === 'admin') {
    subtitle.textContent = 'Manage users, approve applications and monitor the platform.';
    tableTitle.textContent = 'All Recent Requests';
    actions.innerHTML = `
      <a href="help-requests.html" class="btn-sm btn-sm-primary" style="text-decoration:none;">All Requests</a>
      <a href="psychological.html" class="btn-sm btn-sm-outline" style="text-decoration:none;">Support Queue</a>`;
    sidePanel.innerHTML = `
      <div class="panel">
        <div class="panel-header"><h3>Admin Panel</h3></div>
        <div class="impact-item"><div class="impact-label">Total Users</div><div class="impact-val" id="impactUsers">—</div></div>
        <div class="impact-item"><div class="impact-label">Total Requests</div><div class="impact-val" id="impactTotal">—</div></div>
        <div class="impact-item"><div class="impact-label">Completed</div><div class="impact-val" id="impactDone">—</div></div>
      </div>
      <div class="panel">
        <div class="panel-header"><h3>Pending Approvals</h3></div>
        <div style="padding:12px 20px;" id="pendingApprovals">
          <p style="font-size:13px;color:#475569;">Check pgAdmin → users table → where is_active = false</p>
        </div>
      </div>`;
  }
}

// Load real stats from API
async function loadStats() {
  try {
    const res = await apiFetch(`${API}/dashboard/stats`, { headers: authHeader() });
    if (!res.ok) return;
    const data = (await res.json()).data || {};
    document.getElementById('statTotal').textContent = data.total ?? 0;
    document.getElementById('statPending').textContent = data.pending ?? 0;
    document.getElementById('statCompleted').textContent = data.completed ?? 0;
    document.getElementById('statPsych').textContent = data.psychological ?? 0;
    // Fill side panel numbers too
    ['impactTotal','impactDone','impactPending','impactUsers'].forEach(id => {
      const el = document.getElementById(id);
      if (!el) return;
      if (id === 'impactTotal') el.textContent = data.total ?? 0;
      if (id === 'impactDone') el.textContent = data.completed ?? 0;
      if (id === 'impactPending') el.textContent = data.pending ?? 0;
    });
  } catch (e) {}
}

// Load recent requests table
async function loadRecentRequests() {
  try {
    // Psychologists are not permitted to list material help requests; their
    // table is headed "Pending Psychological Requests" and now shows those.
    const endpoint = (userRole === 'beneficiary') ? '/help-requests/my'
                   : (userRole === 'psychologist') ? '/psychological-requests/pending'
                   : '/help-requests?page=0&size=10';
    const res = await apiFetch(`${API}${endpoint}`, { headers: authHeader() });
          if (!res.ok) throw new Error('HTTP ' + res.status);

    // Unwrap ApiResponse → Page → content (paginated) or plain array
    const inner = (await res.json()).data ?? [];
    const requests = (Array.isArray(inner) ? inner : (inner?.content ?? [])).slice(0, 5);
    const tbody = document.getElementById('requestsTable');

    if (!requests.length) {
      tbody.innerHTML = '<tr><td colspan="3" style="text-align:center;padding:32px;color:#475569;">No requests yet.</td></tr>';
      return;
    }

    const statusClass = s => ({PENDING:'status-pending',ASSIGNED:'status-assigned',COMPLETED:'status-completed',CANCELLED:'status-cancelled',IN_PROGRESS:'status-assigned'}[s]||'status-pending');
    const typeClass = t => ({MEDICAL:'tag-medical',FOOD:'tag-food',SHELTER:'tag-shelter',WATER:'tag-water',CLOTHING:'tag-clothing',OTHER:'tag-psychological'}[t]||'tag-food');

    tbody.innerHTML = requests.map(r => `
      <tr>
        <td>
          <div class="req-title">${escHtml(r.title || r.description?.substring(0,40) || 'Help Request')}</div>
          <div class="req-id">#${escHtml(r.id || r.requestId || '—')} &nbsp;&#128205; ${escHtml(r.address || 'Not specified')}</div>
        </td>
        <td><span class="tag ${typeClass(r.helpType)}">${escHtml(r.helpType || r.category || 'OTHER')}</span></td>
        <td><span class="tag ${statusClass(r.status)}">${escHtml(String(r.status || 'PENDING').replace('_', ' '))}</span></td>
      </tr>`).join('');
  } catch (e) {
    document.getElementById('requestsTable').innerHTML =
            '<tr><td colspan="3" style="text-align:center;padding:32px;color:#475569;">Could not load requests.</td></tr>';
  }
}

setupDashboardByRole();
checkProviderSetup();
loadStats();
loadRecentRequests();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { toggleSidebar(); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('dismissProviderSetupBannerBtn', 'click', () => { dismissProviderSetupBanner(); });
wire('goHelpRequests', () => { window.location='help-requests.html'; });
