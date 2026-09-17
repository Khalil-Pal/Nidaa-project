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
document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeModal(); closeConsultationModal(); } });

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
          ${(r.status||'').toUpperCase() === 'COMPLETED'
            ? `<button class="btn-accept" data-action="consultation" data-id="${Number(r.id)}" data-title="${escHtml(r.category||'Consultation')}">Rate this consultation</button>`
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
          ${(r.status||'').toUpperCase() === 'COMPLETED'
            ? `<button class="btn-accept" style="font-size:12px;padding:6px 12px" data-action="consultation" data-id="${Number(r.id)}" data-title="${escHtml(r.category||'Session')}" data-format="${escHtml(r.preferredFormat||'CHAT')}">Record consultation</button>`
            : ''}
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

// ── CS-1: consultation record and beneficiary rating ─────────────────────────
// The psychologist records the consultation once the case is COMPLETED; the
// beneficiary rates it once. The server decides what each role may see: the
// psychologist's private notes come back only to the psychologist, and the
// response never names the beneficiary.
let consultationRequestId = null;
let consultationMeta = {};

function closeConsultationModal() {
  document.getElementById('consultationOverlay').classList.remove('open');
  consultationRequestId = null;
}

function formatLabel(f) {
  return { CHAT: 'Text chat', AUDIO: 'Audio call', VIDEO: 'Video session' }[(f || '').toUpperCase()] || (f || '');
}

async function openConsultationModal(id, meta) {
  consultationRequestId = id;
  consultationMeta = meta || {};
  document.getElementById('consultationTitle').textContent =
    userRole === 'psychologist' ? 'Consultation record' : 'Rate this consultation';
  document.getElementById('consultationSubtitle').textContent = consultationMeta.title || 'Support session';
  const body = document.getElementById('consultationBody');
  body.innerHTML = '<p class="report-note">Loading…</p>';
  document.getElementById('consultationOverlay').classList.add('open');
  const res = await apiFetch(API + '/psychological-requests/' + id + '/consultation', { headers: authHeader() });
  if (res.status === 404) { renderConsultation(null); return; }
  if (!res.ok) {
    body.innerHTML = '<p class="report-note">Could not load the consultation (HTTP ' + res.status + ').</p>';
    return;
  }
  renderConsultation((await res.json()).data || null);
}

function renderConsultation(record) {
  const body = document.getElementById('consultationBody');
  let html = '';
  if (record) {
    document.getElementById('consultationSubtitle').textContent =
      (consultationMeta.title || 'Support session') + (record.psychologistName ? ' — ' + record.psychologistName : '');
    const when = record.startedAt ? fmtDate(record.startedAt) : '';
    const duration = record.durationMinutes != null ? record.durationMinutes + ' min' : '';
    html += '<div class="report-block"><h4>Session</h4><p>' + escHtml(formatLabel(record.format)) +
      (duration ? ' · ' + escHtml(duration) : '') + (when ? ' · ' + escHtml(when) : '') + '</p>' +
      (record.topicsDiscussed && record.topicsDiscussed.length
        ? '<div class="report-meta" style="margin-top:10px">Topics discussed</div><div>' +
          record.topicsDiscussed.map(t => '<span class="topic-chip">' + escHtml(t) + '</span>').join('') + '</div>'
        : '') + '</div>';
    if (record.recommendations) {
      html += '<div class="report-block"><h4>Recommendations</h4><p>' + escHtml(record.recommendations) + '</p></div>';
    }
    // present only in the assigned psychologist's own response
    if (record.notesForPsychologist) {
      html += '<div class="report-block private"><h4>Private notes (only you see these)</h4><p>' + escHtml(record.notesForPsychologist) + '</p></div>';
    }
    if (record.rating) {
      html += '<div class="report-block"><h4>Rating</h4>' +
        '<div class="rating-given" aria-label="' + Number(record.rating) + ' out of 5">' +
        '★'.repeat(Number(record.rating)) + '☆'.repeat(5 - Number(record.rating)) + '</div>' +
        (record.feedbackFromBeneficiary ? '<p>' + escHtml(record.feedbackFromBeneficiary) + '</p>' : '') + '</div>';
    } else if (userRole === 'beneficiary') {
      html += '<form id="consultFeedbackForm"><fieldset style="border:none;padding:0;margin:0">' +
        '<legend style="font-size:14px;font-weight:600;margin-bottom:4px">How was this consultation?</legend>' +
        '<div class="stars">' + [1, 2, 3, 4, 5].map(n =>
          '<input type="radio" name="consultRating" id="consultRating' + n + '" value="' + n + '" required/>' +
          '<label for="consultRating' + n + '" title="' + n + ' of 5">' + n + '</label>').join('') + '</div></fieldset>' +
        '<div class="form-group"><label for="consultFeedbackText">Anything to add? <span style="font-weight:400;color:var(--muted)">(optional)</span></label>' +
        '<textarea id="consultFeedbackText" maxlength="2000" placeholder="Your feedback goes to the psychologist without your name"></textarea></div>' +
        '<button type="submit" class="btn-psych-update" id="consultFeedbackSubmitBtn">Send rating</button></form>';
    } else {
      html += '<p class="report-note">The person you supported has not rated this consultation yet.</p>';
    }
  } else if (userRole === 'psychologist') {
    const fmt = (consultationMeta.format || 'CHAT').toUpperCase();
    html += '<p class="report-note">Record the consultation that closed this case. The person you supported sees the session details and your recommendations and is asked to rate the consultation; your private notes stay with you.</p>' +
      '<form id="consultationForm">' +
      '<div class="form-group"><label for="consultFormat">Session format</label><select id="consultFormat">' +
      ['CHAT', 'AUDIO', 'VIDEO'].map(f => '<option value="' + f + '"' + (f === fmt ? ' selected' : '') + '>' + formatLabel(f) + '</option>').join('') +
      '</select></div>' +
      '<div class="form-group"><label for="consultDuration">Duration (minutes)</label><input type="number" id="consultDuration" min="0" max="1440" step="1" placeholder="e.g. 45"/></div>' +
      '<div class="form-group"><label for="consultTopics">Topics discussed</label><input type="text" id="consultTopics" maxlength="500" placeholder="sleep, grief, coping"/><div class="field-hint">Separate topics with commas.</div></div>' +
      '<div class="form-group"><label for="consultRecommendations">Recommendations for the person</label><textarea id="consultRecommendations" maxlength="4000" placeholder="What you suggested they do next"></textarea></div>' +
      '<div class="form-group"><label for="consultNotes">Private notes <span style="font-weight:400;color:var(--muted)">(only you can see these)</span></label><textarea id="consultNotes" maxlength="4000" placeholder="Never shown to the person or to administrators"></textarea></div>' +
      '<button type="submit" class="btn-psych-update" id="consultationSubmitBtn">Record consultation</button></form>';
  } else {
    html += '<p class="report-note">The psychologist has not recorded the consultation yet. You can rate it once they have.</p>';
  }
  body.innerHTML = html;
  const form = document.getElementById('consultationForm');
  if (form) form.addEventListener('submit', (e) => { e.preventDefault(); submitConsultation(); });
  const feedbackForm = document.getElementById('consultFeedbackForm');
  if (feedbackForm) feedbackForm.addEventListener('submit', (e) => { e.preventDefault(); submitConsultationFeedback(); });
}

async function submitConsultation() {
  const btn = document.getElementById('consultationSubmitBtn');
  const durationRaw = document.getElementById('consultDuration').value.trim();
  const payload = {
    format: document.getElementById('consultFormat').value,
    durationMinutes: durationRaw === '' ? null : Number(durationRaw),
    topicsDiscussed: document.getElementById('consultTopics').value.split(',').map(t => t.trim()).filter(Boolean),
    recommendations: document.getElementById('consultRecommendations').value.trim() || null,
    notesForPsychologist: document.getElementById('consultNotes').value.trim() || null
  };
  btn.disabled = true; btn.textContent = 'Saving...';
  try {
    const res = await apiFetch(API + '/psychological-requests/' + consultationRequestId + '/consultation', {
      method: 'POST', headers: authHeader(), body: JSON.stringify(payload)
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
    renderConsultation(data.data);
    showToast('✅ Consultation recorded.', true);
  } catch (e) {
    alert('Could not record the consultation: ' + e.message);
    btn.disabled = false; btn.textContent = 'Record consultation';
  }
}

async function submitConsultationFeedback() {
  const picked = document.querySelector('#consultFeedbackForm input[name="consultRating"]:checked');
  if (!picked) { alert('Please choose a rating from 1 to 5.'); return; }
  const btn = document.getElementById('consultFeedbackSubmitBtn');
  btn.disabled = true; btn.textContent = 'Sending...';
  try {
    const res = await apiFetch(API + '/psychological-requests/' + consultationRequestId + '/consultation/feedback', {
      method: 'POST', headers: authHeader(),
      body: JSON.stringify({ rating: Number(picked.value), feedback: document.getElementById('consultFeedbackText').value.trim() || null })
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
    renderConsultation(data.data);
    showToast('✅ Thank you for rating this consultation.', true);
  } catch (e) {
    alert('Could not send the rating: ' + e.message);
    btn.disabled = false; btn.textContent = 'Send rating';
  }
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
wireEvent('closeConsultationBtn', 'click', () => { closeConsultationModal(); });
wireEvent('consultationOverlay', 'click', function (event) { if (event.target === this) closeConsultationModal(); });

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
    case 'consultation': openConsultationModal(id, { title: btn.dataset.title, format: btn.dataset.format }); break;
  }
}
['myRequestsList', 'pendingRequestsList', 'activeSessionsList'].forEach((id) => wireEvent(id, 'click', dispatchCaseAction));
