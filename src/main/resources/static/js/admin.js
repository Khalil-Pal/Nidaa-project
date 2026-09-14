// admin.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if (!localStorage.getItem('token')) window.location.href = 'login.html';

const user       = JSON.parse(localStorage.getItem('user') || '{}');
buildSidebar('admin.html');
const fullName   = user.fullName || user.name || 'User';
const firstName  = fullName.split(' ')[0];
const userRole   = (user.role || 'admin').toLowerCase();
const roleDisplay = userRole.charAt(0).toUpperCase() + userRole.slice(1);
const initials   = fullName.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase();

// Only admin can access this page
if (userRole !== 'admin') {
    window.location.href = 'dashboard.html';
}

const _s = (id,v) => { const e=document.getElementById(id); if(e) e.textContent=v; };
_s('topbarName', firstName);
_s('topbarRole', roleDisplay);

// ── LOAD STATS ──────────────────────────────────────────────────────────
async function loadStats() {
    try {
        const res = await apiFetch(API + '/admin/stats', { headers: authHeader() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const stats = (await res.json()).data || {};

        const byStatus  = stats.byStatus  || {};
        const byType    = stats.byType    || {};
        const byRegion  = stats.byRegion  || {};
        const usersByRole = stats.usersByRole || {};

        // Stat cards
        document.getElementById('statTotal').textContent      = stats.totalRequests ?? '—';
        document.getElementById('statPending').textContent    = byStatus['PENDING'] ?? 0;
        document.getElementById('statCompleted').textContent  = byStatus['COMPLETED'] ?? 0;
        document.getElementById('statThisWeek').textContent   = stats.thisWeek ?? '—';
        document.getElementById('statTotalUsers').textContent  = stats.totalUsers  ?? '—';
        document.getElementById('statActiveUsers').textContent = stats.activeUsers ?? '—';

        // Status detail
        document.getElementById('sPending').textContent   = byStatus['PENDING']   ?? 0;
        document.getElementById('sAssigned').textContent  = byStatus['ASSIGNED']  ?? 0;
        document.getElementById('sCompleted').textContent = byStatus['COMPLETED'] ?? 0;
        document.getElementById('sCancelled').textContent = byStatus['CANCELLED'] ?? 0;

        document.getElementById('pageDesc').textContent =
            'Last refreshed: ' + new Date().toLocaleTimeString();

        // Type chart
        const typeColors = {
            FOOD: '#047857', MEDICAL: '#b91c1c', SHELTER: '#1d4ed8',
            WATER: '#0ea5e9', CLOTHING: '#b45309', PSYCHOLOGICAL: '#7c3aed', OTHER: '#94a3b8'
        };
        const typeLabels = Object.keys(byType);
        const typeData   = typeLabels.map(k => byType[k]);
        const typeBgColors = typeLabels.map(k => typeColors[k] || '#94a3b8');

        new Chart(document.getElementById('typeChart'), {
            type: 'bar',
            data: {
                labels: typeLabels.map(l => l.charAt(0) + l.slice(1).toLowerCase()),
                datasets: [{ data: typeData, backgroundColor: typeBgColors, borderRadius: 6, borderSkipped: false }]
            },
            options: {
                responsive: true, maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: {
                    y: { beginAtZero: true, grid: { color: '#f1f5f9' }, ticks: { precision: 0 } },
                    x: { grid: { display: false } }
                }
            }
        });

        // Regions table
        const regions = Object.entries(byRegion).sort((a, b) => b[1] - a[1]).slice(0, 8);
        const tbody = document.getElementById('regionTable');
        if (regions.length === 0) {
            tbody.innerHTML = '<tr><td colspan="3" style="color:var(--muted);text-align:center;padding:20px">No location data yet — ask users to enter their city when submitting requests</td></tr>';
        } else {
            tbody.innerHTML = regions.map(([region, count], i) =>
                `<tr><td><span class="rank-badge">${i + 1}</span></td><td>${escHtml(region)}</td><td><strong>${Number(count)}</strong></td></tr>`
            ).join('');
        }

        // Users by role
        const roleClassMap = {
            VOLUNTEER: 'r-volunteer', PSYCHOLOGIST: 'r-psychologist',
            ORGANIZATION: 'r-organization', BENEFICIARY: 'r-beneficiary',
            ADMIN: 'r-admin'
        };
        const roleLabelMap = {
            VOLUNTEER: 'Volunteers', PSYCHOLOGIST: 'Psychologists',
            ORGANIZATION: 'Organizations', BENEFICIARY: 'Beneficiaries',
            ADMIN: 'Admins'
        };
        const roleGrid = document.getElementById('roleGrid');
        const roleEntries = Object.entries(usersByRole).sort((a, b) => b[1] - a[1]);
        if (roleEntries.length === 0) {
            roleGrid.innerHTML = '<div style="color:var(--muted);font-size:14px">No active users</div>';
        } else {
            roleGrid.innerHTML = roleEntries.map(([role, count]) => {
                const cls = roleClassMap[role] || 'r-unknown';
                const lbl = roleLabelMap[role] || role.charAt(0) + role.slice(1).toLowerCase();
                return `<div class="role-card ${cls}"><div class="r-count">${Number(count)}</div><div class="r-label">${escHtml(lbl)}</div></div>`;
            }).join('');
        }

    } catch (err) {
        const banner = document.getElementById('errorBanner');
        banner.style.display = 'block';
        document.getElementById('errorMsg').textContent = 'Could not load stats: ' + err.message;
        document.getElementById('pageDesc').textContent = 'Error loading data';
    }
}

loadStats();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
