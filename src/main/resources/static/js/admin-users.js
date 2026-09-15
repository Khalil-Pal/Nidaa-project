// admin-users.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if (!localStorage.getItem('token')) window.location.href = 'login.html';
const user = JSON.parse(localStorage.getItem('user') || '{}');
buildSidebar('admin-users.html');
const fullName = user.fullName || user.name || 'Admin';
const userRole = (user.role || '').toLowerCase();
if (userRole !== 'admin') window.location.href = 'dashboard.html';

document.getElementById('topbarName').textContent = fullName.split(' ')[0];

const roleColors = {
    volunteer:'#1d4ed8', psychologist:'#7c3aed',
    organization:'#c2410c', beneficiary:'#047857', admin:'#b91c1c'
};
const rolePillClass = {
    volunteer:'pill-volunteer', psychologist:'pill-psychologist',
    organization:'pill-organization', beneficiary:'pill-beneficiary', admin:'pill-admin'
};

let allUsers = [];

function formatDate(d) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('en-US', {year:'numeric',month:'short',day:'numeric'});
}

function renderTable(users) {
    const tbody = document.getElementById('usersTableBody');
    document.getElementById('totalCount').textContent = users.length + ' user' + (users.length !== 1 ? 's' : '');

    if (!users.length) {
        tbody.innerHTML = `<tr><td colspan="5"><div class="empty-state"><i class="fa fa-magnifying-glass"></i><p>No users match your search.</p></div></td></tr>`;
        return;
    }

    tbody.innerHTML = users.map(u => {
        const role     = (u.role || '').toLowerCase();
        const initials = (u.fullName||'?').split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
        const color    = roleColors[role] || '#475569';
        const pill     = rolePillClass[role] || '';
        const isActive = u.isActive === true || u.isActive === 'true';
        const uid      = u.id || u.userId || 0;

        return `<tr data-user-id="${Number(uid)}" tabindex="0" role="button" aria-label="Open user details">
            <td>
                <div class="user-cell">
                    <div class="user-av" style="background:${color}">${escHtml(initials)}</div>
                    <div>
                        <div class="user-name">${escHtml(u.fullName || '—')}</div>
                        <div class="user-email">${escHtml(u.email || '—')}</div>
                    </div>
                </div>
            </td>
            <td><span class="role-pill ${pill}">${escHtml((u.role||'').toLowerCase())}</span></td>
            <td>
                <span class="status-dot">
                    <span class="dot ${isActive?'dot-active':'dot-inactive'}"></span>
                    ${isActive ? 'Active' : 'Inactive'}
                </span>
            </td>
            <td>${escHtml(u.phone || '—')}</td>
            <td>${escHtml(formatDate(u.createdAt))}</td>
        </tr>`;
    }).join('');
}

function filterUsers() {
    const search = document.getElementById('searchInput').value.toLowerCase();
    const role   = document.getElementById('roleFilter').value.toUpperCase();
    const status = document.getElementById('statusFilter').value;

    const filtered = allUsers.filter(u => {
        const matchSearch = !search ||
            (u.fullName||'').toLowerCase().includes(search) ||
            (u.email||'').toLowerCase().includes(search);
        const matchRole   = !role   || (u.role||'').toUpperCase() === role;
        const isActive    = u.isActive === true || u.isActive === 'true';
        const matchStatus = !status || (status==='active' ? isActive : !isActive);
        return matchSearch && matchRole && matchStatus;
    });
    renderTable(filtered);
}

let currentModalUserId = null;

function openModal(userId) {
    currentModalUserId = userId;
    const u = allUsers.find(x => (x.id||x.userId) == userId);
    if (!u) return;

    const role     = (u.role || '').toLowerCase();
    const initials = (u.fullName||'?').split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
    const color    = roleColors[role] || '#475569';
    const pill     = rolePillClass[role] || '';
    const isActive = u.isActive === true || u.isActive === 'true';

    document.getElementById('modalAv').textContent      = initials;
    document.getElementById('modalAv').style.background = color;
    document.getElementById('modalName').textContent    = u.fullName || '—';
    document.getElementById('modalEmail').textContent   = u.email    || '—';
    document.getElementById('modalPhone').textContent   = u.phone    || 'Not provided';
    document.getElementById('modalJoined').textContent  = formatDate(u.createdAt);
    document.getElementById('modalLastLogin').textContent = u.lastLogin ? formatDate(u.lastLogin) : 'Never';
    document.getElementById('modalStatus').textContent  = isActive ? '✅ Active' : '❌ Inactive';
    document.getElementById('modalVerified').textContent = (u.isVerified===true||u.isVerified==='true') ? '✅ Verified' : '⏳ Not verified';

    const pillEl = document.getElementById('modalRolePill');
    pillEl.textContent  = (u.role||'').toLowerCase();
    pillEl.className    = `role-pill ${pill}`;

    document.getElementById('userModal').classList.add('open');
}

function closeModal() {
    document.getElementById('userModal').classList.remove('open');
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

async function deleteUserAccount() {
    if (!currentModalUserId) return;
    const u = allUsers.find(x => (x.id||x.userId) == currentModalUserId);
    const name = u ? (u.fullName || u.email) : 'this user';
    if (!confirm('Delete ' + name + '\'s account permanently? This cannot be undone.\nAn email will be sent to the user.')) return;

    const btn = document.getElementById('modalDeleteBtn');
    btn.disabled = true;
    btn.innerHTML = '<i class="fa fa-spinner fa-spin"></i> Deleting...';

    try {
        const res = await apiFetch(API + '/admin/users/' + currentModalUserId, {
            method: 'DELETE', headers: authHeader()
        });
        if (!res.ok) throw new Error('HTTP ' + res.status);

        // Remove from local list and re-render
        allUsers = allUsers.filter(x => (x.id||x.userId) != currentModalUserId);
        renderTable(allUsers);
        closeModal();

        const t = document.createElement('div');
        t.style.cssText = 'position:fixed;top:20px;right:20px;background:#f0fdf4;border:1px solid #bbf7d0;color:#047857;padding:14px 22px;border-radius:12px;font-weight:600;z-index:9999;font-size:14px;font-family:Inter,sans-serif';
        t.textContent = '✅ Account deleted and user notified by email.';
        document.body.appendChild(t);
        setTimeout(() => t.remove(), 4000);
    } catch (e) {
        alert('Could not delete account: ' + e.message);
        btn.disabled = false;
        btn.innerHTML = '<i class="fa fa-trash"></i> Delete Account';
    }
}

async function loadUsers() {
    try {
        const res  = await apiFetch(API + '/admin/users', { headers: authHeader() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        allUsers   = (await res.json()).data || [];
        renderTable(allUsers);
    } catch (e) {
        document.getElementById('usersTableBody').innerHTML =
            `<tr><td colspan="5" style="text-align:center;padding:40px;color:#b91c1c">
                <i class="fa fa-triangle-exclamation" style="font-size:24px;margin-bottom:8px;display:block"></i>
                Could not load users: ${escHtml(e.message)}
            </td></tr>`;
    }
}

loadUsers();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('searchInput', 'input', () => { filterUsers(); });
wireEvent('roleFilter', 'change', () => { filterUsers(); });
wireEvent('statusFilter', 'change', () => { filterUsers(); });
wireEvent('userModal', 'click', function (event) { if(event.target===this)closeModal(); });
wireEvent('closeModalBtn', 'click', () => { closeModal(); });
wireEvent('modalDeleteBtn', 'click', () => { deleteUserAccount(); });
wireEvent('closeModalBtn2', 'click', () => { closeModal(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
(() => {
  const body = document.getElementById('usersTableBody');
  if (!body) return;
  const open = (event) => {
    const row = event.target.closest('tr[data-user-id]');
    if (row) openModal(Number(row.dataset.userId));
  };
  body.addEventListener('click', open);
  body.addEventListener('keydown', (event) => {
    if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('tr[data-user-id]')) { event.preventDefault(); open(event); }
  });
})();
