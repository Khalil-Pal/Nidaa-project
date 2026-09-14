// admin-approvals.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if (!localStorage.getItem('token')) window.location.href = 'login.html';
const user = JSON.parse(localStorage.getItem('user') || '{}');
buildSidebar('admin-approvals.html');
const fullName = user.fullName || user.name || 'Admin';
const userRole = (user.role || '').toLowerCase();
if (userRole !== 'admin') window.location.href = 'dashboard.html';

const _s = (id,v) => { const e=document.getElementById(id); if(e) e.textContent=v; };
document.getElementById('topbarName').textContent = fullName.split(' ')[0];

const roleColors = {
  volunteer:'#1d4ed8', psychologist:'#7c3aed',
  organization:'#ea580c', beneficiary:'#047857', admin:'#b91c1c'
};
const rolePillClass = {
  volunteer:'pill-volunteer', psychologist:'pill-psychologist',
  organization:'pill-organization', beneficiary:'pill-beneficiary', admin:'pill-admin'
};

function showToast(msg, type='success') {
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.innerHTML = `<i class="fa fa-${type==='success'?'circle-check':'circle-exclamation'}"></i> ${escHtml(msg)}`;
  document.body.appendChild(t);
  setTimeout(() => { t.style.opacity='0'; t.style.transition='.3s'; setTimeout(()=>t.remove(),300); }, 3000);
}

function formatDate(d) {
  if (!d) return '—';
  return new Date(d).toLocaleDateString('en-US', {year:'numeric',month:'short',day:'numeric'});
}

function renderPending(users) {
  const list = document.getElementById('pendingList');
  const count = users.length;
  const _pc = document.getElementById('pendingCount'); if(_pc) _pc.textContent = count;
  const _tpc = document.getElementById('tabPendingCount'); if(_tpc) _tpc.textContent = count;

  if (!count) {
    list.innerHTML = `<div class="empty-state"><i class="fa fa-circle-check"></i><h3>All caught up!</h3><p>No pending applications right now.</p></div>`;
    return;
  }

  list.innerHTML = users.map(u => {
    const role = (u.role || '').toLowerCase();
    const initials = (u.fullName||'?').split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
    const color = roleColors[role] || '#475569';
    const pill  = rolePillClass[role] || '';
    return `
          <div class="app-card" id="app-${escHtml(u.id||u.userId)}">
              <div class="app-avatar" style="background:${color}">${escHtml(initials)}</div>
              <div class="app-info">
                  <div class="app-name">${escHtml(u.fullName || '—')} <span class="role-pill ${pill}">${escHtml((u.role||'').toLowerCase())}</span></div>
                  <div class="app-meta">
                      <span><i class="fa fa-envelope"></i> ${escHtml(u.email || '—')}</span>
                      ${u.phone ? `<span><i class="fa fa-phone"></i> ${escHtml(u.phone)}</span>` : ''}
                      <span><i class="fa fa-calendar"></i> Applied ${escHtml(formatDate(u.createdAt))}</span>
                  </div>
              </div>
              <div class="app-actions">
                  <button class="btn-approve" data-action="approve" data-user-id="${Number(u.id||u.userId)}">
                      <i class="fa fa-check"></i> Approve
                  </button>
                  <button class="btn-deny" data-action="deny" data-user-id="${Number(u.id||u.userId)}">
                      <i class="fa fa-xmark"></i> Deny
                  </button>
              </div>
          </div>`;
  }).join('');
}

function renderAllUsers(users) {
  const list = document.getElementById('allList');
  if (!users.length) {
    list.innerHTML = `<div class="empty-state"><i class="fa fa-users"></i><h3>No users found</h3></div>`;
    return;
  }
  list.innerHTML = users.map(u => {
    const role = (u.role || '').toLowerCase();
    const initials = (u.fullName||'?').split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
    const color = roleColors[role] || '#475569';
    const pill  = rolePillClass[role] || '';
    const isActive = u.isActive === true || u.isActive === 'true';
    return `
          <div class="app-card">
              <div class="app-avatar" style="background:${color}">${escHtml(initials)}</div>
              <div class="app-info">
                  <div class="app-name">${escHtml(u.fullName || '—')} <span class="role-pill ${pill}">${escHtml((u.role||'').toLowerCase())}</span></div>
                  <div class="app-meta">
                      <span><i class="fa fa-envelope"></i> ${escHtml(u.email || '—')}</span>
                      ${u.phone ? `<span><i class="fa fa-phone"></i> ${escHtml(u.phone)}</span>` : ''}
                      <span><i class="fa fa-circle" style="color:${isActive?'#047857':'#b91c1c'};font-size:9px"></i> ${isActive?'Active':'Inactive'}</span>
                      <span><i class="fa fa-calendar"></i> Joined ${escHtml(formatDate(u.createdAt))}</span>
                  </div>
              </div>
          </div>`;
  }).join('');
}

async function loadPending() {
  try {
    const res  = await apiFetch(API + '/admin/pending', { headers: authHeader() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const users = (await res.json()).data || [];
    renderPending(users);
  } catch (e) {
    document.getElementById('pendingList').innerHTML = `<div class="empty-state"><i class="fa fa-triangle-exclamation"></i><h3>Could not load</h3><p>${escHtml(e.message)}</p></div>`;
  }
}

async function loadAllUsers() {
  try {
    const res  = await apiFetch(API + '/admin/users', { headers: authHeader() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const users = (await res.json()).data || [];
    renderAllUsers(users);
  } catch (e) {
    document.getElementById('allList').innerHTML = `<div class="empty-state"><i class="fa fa-triangle-exclamation"></i><h3>Could not load</h3><p>${escHtml(e.message)}</p></div>`;
  }
}

async function approveUser(userId, btn) {
  if (!confirm('Approve this application? They will receive a confirmation email.')) return;
  btn.disabled = true;
  btn.innerHTML = '<i class="fa fa-spinner fa-spin"></i> Approving...';
  const denyBtn = btn.nextElementSibling;
  if (denyBtn) denyBtn.disabled = true;

  try {
    const res = await apiFetch(API + '/admin/approve/' + userId, { method: 'PUT', headers: authHeader() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast('✅ Application approved! Confirmation email sent.');
    // Remove card smoothly
    const card = document.getElementById('app-' + userId);
    if (card) { card.style.opacity='0'; card.style.transition='.3s'; setTimeout(()=>{ card.remove(); loadPending(); }, 300); }
  } catch (e) {
    showToast('❌ Failed: ' + e.message, 'error');
    btn.disabled = false;
    btn.innerHTML = '<i class="fa fa-check"></i> Approve';
    if (denyBtn) denyBtn.disabled = false;
  }
}

async function denyUser(userId, btn) {
  if (!confirm('Deny this application? Their account will be deleted and they will be notified.')) return;
  btn.disabled = true;
  btn.innerHTML = '<i class="fa fa-spinner fa-spin"></i> Denying...';
  const approveBtn = btn.previousElementSibling;
  if (approveBtn) approveBtn.disabled = true;

  try {
    const res = await apiFetch(API + '/admin/reject/' + userId, { method: 'PUT', headers: authHeader() });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast('Application denied and user notified.', 'success');
    const card = document.getElementById('app-' + userId);
    if (card) { card.style.opacity='0'; card.style.transition='.3s'; setTimeout(()=>{ card.remove(); loadPending(); }, 300); }
  } catch (e) {
    showToast('❌ Failed: ' + e.message, 'error');
    btn.disabled = false;
    btn.innerHTML = '<i class="fa fa-xmark"></i> Deny';
    if (approveBtn) approveBtn.disabled = false;
  }
}

let allLoaded = false;
function switchTab(tab, btn) {
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  if (tab === 'pending') {
    document.getElementById('pendingList').style.display = 'flex';
    document.getElementById('allList').style.display = 'none';
  } else {
    document.getElementById('pendingList').style.display = 'none';
    document.getElementById('allList').style.display = 'flex';
    if (!allLoaded) { loadAllUsers(); allLoaded = true; }
  }
}

loadPending();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('switchTabPending', 'click', function () { switchTab('pending', this); });
wireEvent('switchTabAll', 'click', function () { switchTab('all', this); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
wireEvent('pendingList', 'click', (event) => {
  const btn = event.target.closest('button[data-action]');
  if (!btn) return;
  const userId = Number(btn.dataset.userId);
  if (btn.dataset.action === 'approve') approveUser(userId, btn);
  if (btn.dataset.action === 'deny') denyUser(userId, btn);
});
