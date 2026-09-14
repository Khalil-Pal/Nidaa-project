// psychological.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

function toggleSidebar() { document.getElementById('sidebar').classList.toggle('open'); }
if (!localStorage.getItem('token')) window.location.href = 'login.html';

const user     = JSON.parse(localStorage.getItem('user') || '{}');
const fullName = user.fullName || user.name || 'User';
const firstName= fullName.split(' ')[0];
const userRole = (user.role || 'user').toLowerCase();
const roleDisp = userRole.charAt(0).toUpperCase() + userRole.slice(1);
const initials = fullName.split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();

{const _e=document.getElementById('sidebarName'); if(_e) _e.textContent = fullName;}
{const _e=document.getElementById('sidebarRole'); if(_e) _e.textContent = roleDisp;}
{const _e=document.getElementById('sidebarAvatar'); if(_e) _e.textContent = initials;}
document.getElementById('topbarName').textContent    = firstName;
document.getElementById('topbarRole').textContent    = roleDisp;

if (userRole === 'psychologist') {
  document.getElementById('psychPanel').style.display = 'block';
  document.getElementById('activeSessionsPanel').style.display = 'block';
  // Hide beneficiary-only sections from psychologist
  document.querySelector('.format-grid').style.display = 'none';
  document.querySelector('.categories-section').style.display = 'none';
  document.getElementById('myRequestsPanel').style.display = 'none';
}

let selectedFormat = 'CHAT';
function selectFormat(el, format) {
  document.querySelectorAll('.format-card').forEach(c => { c.classList.remove('selected'); c.setAttribute('aria-pressed', 'false'); });
  el.classList.add('selected');
  el.setAttribute('aria-pressed', 'true');
  selectedFormat = format.toUpperCase();
  document.getElementById('supportFormat').value = format;
}
function togglePill(el) {
  const active = el.classList.toggle('active');
  el.setAttribute('aria-pressed', String(active));
}
function openModal()  { document.getElementById('overlay').classList.add('open'); }
function closeModal() { document.getElementById('overlay').classList.remove('open'); }
function handleOverlayClick(e) { if (e.target === document.getElementById('overlay')) closeModal(); }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

function showToast(msg, ok) {
  const el = document.getElementById('successAlert');
  el.textContent = msg;
  el.style.background   = ok ? '#f0fdf4' : '#fef2f2';
  el.style.borderColor  = ok ? '#bbf7d0' : '#fecaca';
  el.style.color        = ok ? '#047857' : '#b91c1c';
  el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 4000);
}

function fmtDate(d) {
  return d ? new Date(d).toLocaleDateString('en-US',{month:'short',day:'numeric',year:'numeric'}) : '';
}

// Load MY requests (beneficiary)
async function loadMyRequests() {
  const list = document.getElementById('myRequestsList');
  if (!list) return;
  try {
    const res  = await apiFetch(API + '/psychological-requests/my', { headers: authHeader() });
    if (!res.ok) throw new Error();
    const reqs = (await res.json()).data || [];
    if (!reqs.length) {
      list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">No support requests yet.</div>';
      return;
    }
    const sc = {PENDING:'status-pending',ASSIGNED:'status-assigned',COMPLETED:'status-completed',CANCELLED:'status-cancelled'};
    list.innerHTML = reqs.map(r => `
      <div class="req-row">
        <div class="req-info">
          <div class="title">${escHtml(r.category||'')} — ${escHtml(r.preferredFormat||r.supportType||'')}</div>
          <div class="meta">Submitted ${escHtml(fmtDate(r.createdAt))}${r.description?' · '+escHtml(r.description.substring(0,40)):''}</div>
        </div>
        <div class="req-actions">
          <span class="tag ${sc[r.status]||'status-pending'}">${escHtml(r.status||'PENDING')}</span>
          ${['ASSIGNED','COMPLETED'].includes((r.status||'').toUpperCase())
            ? `<button class="btn-accept" data-action="contact" data-id="${Number(r.id)}" data-title="Psychologist Contact" data-status="${escHtml(r.status||'ASSIGNED')}">Contact Psychologist</button>`
            : ''}
        </div>
      </div>`).join('');
  } catch {
    if (list) list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">Could not load requests.</div>';
  }
}

// Load PENDING requests (psychologist)
async function loadPendingRequests() {
  const list = document.getElementById('pendingRequestsList');
  if (!list) return;
  try {
    const res  = await apiFetch(API + '/psychological-requests/pending', { headers: authHeader() });
    if (!res.ok) throw new Error();
    const reqs = ((await res.json()).data || {}).content || [];   // paged (Q-3)
    if (!reqs.length) {
      list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">No pending requests.</div>';
      return;
    }
    list.innerHTML = reqs.map(r => `
      <div class="req-row" id="pr-${Number(r.id)}">
        <div class="req-info">
          <div class="title">${escHtml(r.category||'')} — ${escHtml(r.preferredFormat||'')}</div>
          <div class="meta">${escHtml(fmtDate(r.createdAt))} · ${escHtml(r.urgencyLevel||'')}</div>
        </div>
        <div class="req-actions">
          <button class="btn-accept" data-action="start" data-id="${Number(r.id)}">&#9654; Start Working</button>
          <button class="btn-decline" data-action="dismiss" data-id="${Number(r.id)}">Decline</button>
        </div>
      </div>`).join('');
  } catch {
    if (list) list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">Could not load.</div>';
  }
}

// SUBMIT support request
document.getElementById('supportForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('.btn-submit');
  btn.disabled = true; btn.textContent = 'Submitting...';

  const payload = {
    supportType:     'INDIVIDUAL',
    category:        document.getElementById('supportCategory').value,
    urgencyLevel:    'MEDIUM',
    preferredFormat: selectedFormat,
    description:     document.getElementById('supportDesc').value.trim(),
    isAnonymous:     document.getElementById('supportAnonymous').checked
  };

  try {
    const res = await apiFetch(API + '/psychological-requests', {
      method: 'POST', headers: authHeader(), body: JSON.stringify(payload)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.message || 'Server error ' + res.status);
    }
    closeModal();
    e.target.reset();
    showToast('✅ Request submitted! A psychologist will contact you shortly.', true);
    loadMyRequests(); // reload to show the new saved request
  } catch (err) {
    showToast('❌ Failed: ' + err.message, false);
  }
  btn.disabled = false; btn.textContent = 'Submit Request';
});

let currentPsychRequestId = null;

async function startPsychWork(id, btn) {
  if (!confirm('Start working on this request? Anonymous requests will keep the beneficiary identity private.')) return;
  btn.disabled = true;
  btn.textContent = 'Starting...';
  try {
    const res = await apiFetch(API + '/psychological-requests/' + id + '/accept', { method: 'PUT', headers: authHeader() });
    if (!res.ok) {
      const error = await res.json().catch(() => ({}));
      throw new Error(error.message || 'HTTP ' + res.status);
    }

    // Remove decline button
    const row = document.getElementById('pr-' + id);
    if (row) {
      const dec = row.querySelector('.btn-decline');
      if (dec) dec.remove();
      btn.textContent = '✓ Working';
      btn.style.background = '#047857';
      btn.disabled = false;
      btn.onclick = () => openPsychContact(
        id,
        row.querySelector('.title') ? row.querySelector('.title').textContent : 'Support Request',
        'ASSIGNED');
    }
    await openPsychContact(id, 'Support Request', 'ASSIGNED');
  } catch (e) {
    alert('Could not start: ' + e.message);
    btn.disabled = false;
    btn.textContent = '▶ Start Working';
  }
}

async function openPsychContact(id, title, status = 'ASSIGNED') {
  currentPsychRequestId = id;
  document.getElementById('psychContactTitle').textContent = title || 'Requester Contact';
  document.getElementById('psychContactName').textContent  = '...';
  document.getElementById('psychContactEmail').textContent = '...';
  document.getElementById('psychContactPhone').textContent = '...';
  const notice = document.getElementById('psychContactNotice');
  notice.hidden = true;
  notice.textContent = '';
  const statusSection = document.getElementById('psychStatusSection');
  statusSection.style.display = userRole === 'psychologist' ? 'block' : 'none';
  if (userRole === 'psychologist') buildPsychStatusOptions(status);
  document.getElementById('psychContactOverlay').classList.add('open');
  try {
    const res = await apiFetch(API + '/psychological-requests/' + id + '/contact', { headers: authHeader() });
    if (!res.ok) {
      const error = await res.json().catch(() => ({}));
      throw new Error(error.message || 'Contact is not available');
    }
    const contact = (await res.json()).data || {};
    const roleLabel = formatPsychContactRole(contact.contactRole);
    document.getElementById('psychContactTitle').textContent =
      contact.anonymous ? 'Anonymous Request' : roleLabel + ' Contact';
    document.getElementById('psychContactName').textContent = contact.name || '—';
    document.getElementById('psychContactEmail').textContent =
      contact.anonymous ? 'Not shared' : (contact.email || '—');
    document.getElementById('psychContactPhone').textContent =
      contact.anonymous ? 'Not shared' : (contact.phone || 'Not provided');
    if (contact.message) {
      notice.textContent = contact.message;
      notice.hidden = false;
    }
  } catch (error) {
    document.getElementById('psychContactName').textContent = error.message;
    document.getElementById('psychContactEmail').textContent = '—';
    document.getElementById('psychContactPhone').textContent = '—';
  }
}

function formatPsychContactRole(role) {
  const normalized = (role || 'Contact').toLowerCase();
  return normalized.charAt(0).toUpperCase() + normalized.slice(1);
}

async function loadActiveSessions() {
  const list = document.getElementById('activeSessionsList');
  if (!list) return;
  try {
    const res  = await apiFetch(API + '/psychological-requests/my-assigned', { headers: authHeader() });
    if (!res.ok) throw new Error();
    const reqs = (await res.json()).data || [];
    if (!reqs.length) {
      list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">No active sessions yet.</div>';
      return;
    }
    const sc = {PENDING:'status-pending',ASSIGNED:'status-assigned',COMPLETED:'status-completed',CANCELLED:'status-cancelled'};
    list.innerHTML = reqs.map(r => `
      <div class="req-row" id="as-${Number(r.id)}">
        <div class="req-info">
          <div class="title">${escHtml(r.category||'')} — ${escHtml(r.preferredFormat||r.supportType||'')}</div>
          <div class="meta">Started ${escHtml(fmtDate(r.createdAt))}${r.description?' · '+escHtml(r.description.substring(0,40)):''}</div>
        </div>
        <div class="req-actions">
          <span class="tag ${sc[r.status]||'status-assigned'}">${escHtml(r.status||'ASSIGNED')}</span>
          <button class="btn-accept" style="font-size:12px;padding:6px 12px" data-action="contact" data-id="${Number(r.id)}" data-title="${escHtml(r.category||'Session')}" data-status="${escHtml(r.status||'ASSIGNED')}">Contact & Status</button>
        </div>
      </div>`).join('');
  } catch {
    if (list) list.innerHTML = '<div style="text-align:center;padding:24px;color:#475569;font-size:14px">Could not load sessions.</div>';
  }
}

function closePsychContact() {
  document.getElementById('psychContactOverlay').classList.remove('open');
  currentPsychRequestId = null;
}

function buildPsychStatusOptions(currentStatus) {
  const VALID_TRANSITIONS = {
    'PENDING':   ['ASSIGNED', 'CANCELLED'],
    'ASSIGNED':  ['COMPLETED', 'CANCELLED'],
    'COMPLETED': [],
    'CANCELLED': []
  };
  const LABELS = {
    'ASSIGNED':  'Assigned (In Progress)',
    'COMPLETED': 'Completed',
    'CANCELLED': 'Cancelled',
    'PENDING':   'Pending'
  };
  const sel = document.getElementById('psychStatusSelect');
  const btn = document.querySelector('.btn-psych-update');
  const transitions = VALID_TRANSITIONS[(currentStatus || '').toUpperCase()] || [];
  sel.innerHTML = '';

  if (transitions.length === 0) {
    sel.innerHTML = '<option value="" disabled>No further transitions allowed</option>';
    if (btn) btn.disabled = true;
    return;
  }

  if (btn) btn.disabled = false;
  transitions.forEach(status => {
    const opt = document.createElement('option');
    opt.value = status;
    opt.textContent = LABELS[status] || status;
    sel.appendChild(opt);
  });
}

async function submitPsychStatus() {
  if (!currentPsychRequestId) return;
  const status = document.getElementById('psychStatusSelect').value;
  const btn = document.querySelector('.btn-psych-update');
  if (btn) { btn.disabled = true; btn.textContent = 'Updating...'; }
  try {
    const res = await apiFetch(API + '/psychological-requests/' + currentPsychRequestId + '/status?status=' + status, {
      method: 'PUT', headers: authHeader()
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    closePsychContact();
    loadPendingRequests();
    const t = document.createElement('div');
    t.style.cssText = 'position:fixed;top:20px;right:20px;background:#f0fdf4;border:1px solid #bbf7d0;color:#047857;padding:14px 22px;border-radius:12px;font-weight:600;z-index:9999;font-size:14px;font-family:Inter,sans-serif';
    t.textContent = '✅ Status updated to ' + status.charAt(0) + status.slice(1).toLowerCase();
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 3000);
  } catch (e) {
    alert('Could not update: ' + e.message);
  }
  if (btn) { btn.disabled = false; btn.textContent = 'Update Status'; }
}

loadMyRequests();
if (userRole === 'psychologist') {
  loadPendingRequests();
  loadActiveSessions();
}

// ── ACCESS CONTROL: Volunteer & Organization cannot access this page ──
if (userRole === 'volunteer' || userRole === 'organization') {
  window.location.href = 'help-requests.html';
}

// ── BUILD SIDEBAR BASED ON ROLE ──
buildSidebar('psychological.html');

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { toggleSidebar(); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wire('selectFormatChat', function () { selectFormat(this, 'chat'); });
wire('selectFormatAudio', function () { selectFormat(this, 'audio'); });
wire('selectFormatVideo', function () { selectFormat(this, 'video'); });
wireAll('span.pill', function () { togglePill(this); });
wireEvent('openModalBtn', 'click', () => { openModal(); });
wireEvent('overlay', 'click', (event) => { handleOverlayClick(event); });
wireEvent('closeModalBtn', 'click', () => { closeModal(); });
wireEvent('submitPsychStatusBtn', 'click', () => { submitPsychStatus(); });
wireEvent('closePsychContactBtn', 'click', () => { closePsychContact(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
function dispatchCaseAction(event) {
  const btn = event.target.closest('button[data-action]');
  if (!btn) return;
  const id = Number(btn.dataset.id);
  switch (btn.dataset.action) {
    case 'start':   startPsychWork(id, btn); break;
    case 'dismiss': { const row = document.getElementById('pr-' + id); if (row) row.remove(); break; }
    case 'contact': openPsychContact(id, btn.dataset.title, btn.dataset.status); break;
  }
}
['myRequestsList', 'pendingRequestsList', 'activeSessionsList'].forEach((id) => wireEvent(id, 'click', dispatchCaseAction));
