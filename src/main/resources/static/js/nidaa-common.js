/*
 * Nidaa shared frontend module (F-2).
 *
 * Included once per page with <script src="/js/nidaa-common.js"></script>,
 * before the page's own script. Plain script, no build step: every top-level
 * declaration here is a global the page scripts use directly. This replaces
 * the copy of API, authHeader(), logout(), escHtml(), timeAgo() and the
 * role-based sidebar that every page used to carry.
 */

// Relative so the same pages work on any host; the API is served by the
// same Spring Boot instance as the static files.
const API = '/api';

/** The signed-in user as stored at login, or {} when signed out. */
function currentUser() {
    try {
        return JSON.parse(localStorage.getItem('user') || '{}') || {};
    } catch (e) {
        return {};
    }
}

/** Lower-case role of the signed-in user, or '' when signed out. */
function currentUserRole() {
    return String(currentUser().role || '').toLowerCase();
}

function authHeader() {
    return {
        'Authorization': 'Bearer ' + localStorage.getItem('token'),
        'Content-Type': 'application/json'
    };
}

/** Stores the token pair and user summary returned by login, registration or refresh. */
function storeSession(data) {
    localStorage.setItem('token', data.token);
    if (data.refreshToken) localStorage.setItem('refreshToken', data.refreshToken);
    if (data.userId) {
        localStorage.setItem('user', JSON.stringify({
            id: data.userId,
            email: data.email,
            fullName: data.fullName,
            role: data.role,
            isActive: data.isActive
        }));
    }
}

/** Forgets the session in this browser and, unless told otherwise, goes to the login page. */
function endSession(redirect = true) {
    localStorage.removeItem('token');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    if (redirect) window.location.href = 'login.html';
}

// One refresh at a time: several requests failing with 401 together share
// the same refresh call instead of each rotating the refresh token.
let refreshInFlight = null;

/**
 * Exchanges the stored refresh token for a new pair. Resolves true when the
 * session was renewed. The server rotates the refresh token on every call,
 * so the new one replaces the old immediately.
 */
function refreshSession() {
    if (refreshInFlight) return refreshInFlight;
    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) return Promise.resolve(false);
    refreshInFlight = (async () => {
        try {
            const res = await fetch(API + '/auth/refresh', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken })
            });
            if (!res.ok) return false;
            const body = await res.json();
            const data = body && body.data;
            if (!data || !data.token) return false;
            storeSession(data);
            return true;
        } catch (e) {
            return false;
        } finally {
            refreshInFlight = null;
        }
    })();
    return refreshInFlight;
}

/**
 * fetch() for API calls. Prefixes the API base (paths that already start
 * with /api are left alone), attaches the bearer token and JSON content
 * type, and lets the caller override or add headers.
 *
 * On 401 the access token has expired or been revoked: the session is
 * refreshed once and the request retried with the new token. If the refresh
 * fails the browser session is cleared and the user is sent to the login
 * page, so a 15-minute access token never logs anyone out mid-task while
 * their 7-day refresh token is still valid.
 */
async function apiFetch(path, options = {}, retried = false) {
    let url = path;
    if (!/^https?:\/\//.test(path) && !path.startsWith(API + '/') && path !== API) {
        url = API + (path.startsWith('/') ? path : '/' + path);
    }
    const headers = Object.assign({}, authHeader(), options.headers || {});
    const res = await fetch(url, Object.assign({}, options, { headers }));
    if (res.status !== 401 || retried) return res;
    if (await refreshSession()) return apiFetch(path, options, true);
    endSession(true);
    return res;
}

/**
 * Ends the session on the server as well as in the browser: the refresh
 * token is revoked so it cannot be replayed for the rest of its 7-day life.
 */
async function logout() {
    const refreshToken = localStorage.getItem('refreshToken');
    if (refreshToken) {
        try {
            await fetch(API + '/auth/logout', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken })
            });
        } catch (ignored) {
            // The browser session is cleared regardless; the token expires on its own.
        }
    }
    endSession(true);
}

/** Escapes text for insertion into innerHTML. Use for every server- or user-supplied string. */
function escHtml(t) {
    return String(t == null ? '' : t)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

/** Relative time for a timestamp in milliseconds. */
function timeAgo(ts) {
    const d = Date.now() - ts, m = Math.floor(d / 60000);
    if (m < 1) return 'Just now';
    if (m < 60) return m + 'm ago';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h ago';
    const dy = Math.floor(h / 24);
    if (dy < 30) return dy + 'd ago';
    return Math.floor(dy / 30) + 'mo ago';
}

// Navigation per role. The admin list includes Community because admins may
// post there (MessageService allows it); a few pages used to omit that link.
const SIDEBAR_PAGES = {
    beneficiary: [
        { href: 'dashboard.html',      icon: 'fa-gauge',              label: 'Dashboard' },
        { href: 'help-requests.html',  icon: 'fa-hand-holding-heart', label: 'Help Requests' },
        { href: 'psychological.html',  icon: 'fa-brain',              label: 'Psychological Support' },
        { href: 'submit-story.html',   icon: 'fa-pen-to-square',      label: 'Share My Story' },
        { href: 'profile.html',        icon: 'fa-user',               label: 'My Profile' },
        { href: 'settings.html',       icon: 'fa-gear',               label: 'Settings' }
    ],
    volunteer: [
        { href: 'dashboard.html',      icon: 'fa-gauge',              label: 'Dashboard' },
        { href: 'help-requests.html',  icon: 'fa-hand-holding-heart', label: 'Help Requests' },
        { href: 'community.html',      icon: 'fa-users',              label: 'Community' },
        { href: 'profile.html',        icon: 'fa-user',               label: 'My Profile' },
        { href: 'settings.html',       icon: 'fa-gear',               label: 'Settings' }
    ],
    psychologist: [
        { href: 'dashboard.html',      icon: 'fa-gauge',              label: 'Dashboard' },
        { href: 'community.html',      icon: 'fa-users',              label: 'Community' },
        { href: 'psychological.html',  icon: 'fa-brain',              label: 'Psychological Support' },
        { href: 'profile.html',        icon: 'fa-user',               label: 'My Profile' },
        { href: 'settings.html',       icon: 'fa-gear',               label: 'Settings' }
    ],
    organization: [
        { href: 'dashboard.html',      icon: 'fa-gauge',              label: 'Dashboard' },
        { href: 'help-requests.html',  icon: 'fa-hand-holding-heart', label: 'Help Requests' },
        { href: 'community.html',      icon: 'fa-users',              label: 'Community' },
        { href: 'profile.html',        icon: 'fa-user',               label: 'My Profile' },
        { href: 'settings.html',       icon: 'fa-gear',               label: 'Settings' }
    ],
    admin: [
        { href: 'admin-requests.html',  icon: 'fa-list-check', label: 'All Requests' },
        { href: 'admin.html',           icon: 'fa-chart-bar',  label: 'Analytics' },
        { href: 'admin-approvals.html', icon: 'fa-user-check', label: 'Approvals' },
        { href: 'admin-users.html',     icon: 'fa-users',      label: 'All Users' },
        { href: 'admin-stories.html',   icon: 'fa-newspaper',  label: 'Stories' },
        { href: 'community.html',       icon: 'fa-comments',   label: 'Community' },
        { href: 'settings.html',        icon: 'fa-gear',       label: 'Settings' }
    ]
};

/**
 * Fills #sidebarNav with the links for the signed-in user's role and marks
 * the current page. The role may be passed explicitly; by default it is
 * read from the stored user. Every signed-in page calls this, so it is also
 * where the notification bell is mounted into the topbar (N-1).
 */
function buildSidebar(currentPage, role) {
    const nav = document.getElementById('sidebarNav');
    if (!nav) return;
    const links = SIDEBAR_PAGES[role || currentUserRole()] || SIDEBAR_PAGES.beneficiary;
    nav.innerHTML = links.map(p =>
        '<a href="' + p.href + '" class="nav-item' + (p.href === currentPage ? ' active' : '') + '">' +
        '<i class="fa ' + p.icon + '"></i> ' + escHtml(p.label) + '</a>'
    ).join('');
    mountNotificationBell();
}

// ---- Notifications (N-1) ---------------------------------------------------
// A bell in the topbar with the unread count, polled every 60 s (no WebSocket;
// that dependency was removed in Phase 4). Opening it lists the latest
// notifications; clicking one marks it read and, when the reference has a
// page, goes there.

const NOTIFICATION_POLL_MS = 60000;
const NOTIFICATION_PAGE_SIZE = 10;

/** The page that shows a notification's subject, or null when there is none to go to. */
function notificationHref(referenceType) {
    switch (referenceType) {
        case 'HELP_REQUEST':          return 'help-requests.html';
        case 'PSYCHOLOGICAL_REQUEST': return 'psychological.html';
        case 'MESSAGE':               return 'community.html';
        case 'PROVIDER_RESOURCE':     return 'settings.html';
        default:                      return null;
    }
}

function mountNotificationBell() {
    const host = document.querySelector && document.querySelector('.topbar-actions');
    if (!host || document.getElementById('notifBell') || !localStorage.getItem('token')) return;

    const wrap = document.createElement('div');
    wrap.className = 'notif';
    wrap.innerHTML =
        '<button type="button" class="notif-bell" id="notifBell" aria-label="Notifications" aria-haspopup="true" aria-expanded="false">' +
        '<i class="fa fa-bell" aria-hidden="true"></i><span class="notif-count" id="notifCount" hidden></span></button>' +
        '<div class="notif-panel" id="notifPanel" role="region" aria-label="Notifications" hidden>' +
        '<div class="notif-head"><span>Notifications</span>' +
        '<button type="button" class="notif-readall" id="notifReadAll">Mark all read</button></div>' +
        '<div class="notif-list" id="notifList"></div></div>';
    host.insertBefore(wrap, host.firstChild);

    const bell = document.getElementById('notifBell');
    const panel = document.getElementById('notifPanel');
    const setOpen = (open) => {
        panel.hidden = !open;
        bell.setAttribute('aria-expanded', String(open));
        if (open) loadNotifications();
    };
    bell.addEventListener('click', () => setOpen(panel.hidden));
    document.getElementById('notifReadAll').addEventListener('click', async () => {
        const res = await apiFetch(API + '/notifications/read-all', { method: 'PUT' });
        if (res.ok) { await loadNotifications(); refreshUnreadCount(); }
    });
    document.getElementById('notifList').addEventListener('click', async (event) => {
        const item = event.target.closest('button[data-id]');
        if (!item) return;
        await apiFetch(API + '/notifications/' + Number(item.dataset.id) + '/read', { method: 'PUT' });
        const href = notificationHref(item.dataset.ref);
        if (href) { window.location.href = href; return; }
        item.classList.remove('unread');
        refreshUnreadCount();
    });
    document.addEventListener('click', (event) => { if (!wrap.contains(event.target) && !panel.hidden) setOpen(false); });
    document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && !panel.hidden) { setOpen(false); bell.focus(); } });

    refreshUnreadCount();
    setInterval(refreshUnreadCount, NOTIFICATION_POLL_MS);
}

async function refreshUnreadCount() {
    try {
        const res = await apiFetch(API + '/notifications/unread-count');
        if (!res.ok) return;
        const unread = Number(((await res.json()).data || {}).unread || 0);
        const count = document.getElementById('notifCount');
        const bell = document.getElementById('notifBell');
        if (!count || !bell) return;
        count.textContent = unread > 99 ? '99+' : String(unread);
        count.hidden = unread === 0;
        bell.setAttribute('aria-label', unread === 0 ? 'Notifications' : 'Notifications, ' + unread + ' unread');
    } catch (e) {
        // the next poll tries again; a failed count must not break the page
    }
}

async function loadNotifications() {
    const list = document.getElementById('notifList');
    if (!list) return;
    list.innerHTML = '<div class="notif-empty">Loading\u2026</div>';
    try {
        const res = await apiFetch(API + '/notifications?page=0&size=' + NOTIFICATION_PAGE_SIZE);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const items = ((await res.json()).data || {}).content || [];
        if (!items.length) { list.innerHTML = '<div class="notif-empty">Nothing yet.</div>'; return; }
        list.innerHTML = items.map(n =>
            '<button type="button" class="notif-item' + (n.read ? '' : ' unread') + '" data-id="' + Number(n.id) + '"' +
            ' data-ref="' + escHtml(n.referenceType || '') + '">' +
            '<span class="notif-title">' + escHtml(n.title) + '</span>' +
            '<span class="notif-content">' + escHtml(n.content) + '</span>' +
            '<span class="notif-time">' + escHtml(timeAgo(new Date(n.createdAt).getTime())) + '</span></button>'
        ).join('');
    } catch (e) {
        list.innerHTML = '<div class="notif-empty">Could not load notifications.</div>';
    }
}

// ---- Event wiring helpers (F-5) --------------------------------------------
// The Content Security Policy is script-src 'self': no inline <script> and no
// on* attributes. Pages wire their controls with these instead. wire() also
// makes an element that is not a native button operable from the keyboard.

/** Adds an event listener to the element with that id, if the page has it. */
function wireEvent(id, type, handler) {
    const el = document.getElementById(id);
    if (el) el.addEventListener(type, handler);
    return el;
}

/**
 * Click handler for one element. A div/span/anchor-without-href that acts as a
 * button gets Enter and Space as well, so it works without a mouse; native
 * buttons and links already do.
 */
function wire(id, handler) {
    const el = document.getElementById(id);
    if (el) activate(el, handler);
    return el;
}

/** wire() for every element matching a selector. */
function wireAll(selector, handler) {
    document.querySelectorAll(selector).forEach((el) => activate(el, handler));
}

function activate(el, handler) {
    el.addEventListener('click', handler);
    const nativelyOperable = el.matches('button, a[href], input, select, textarea, summary');
    if (!nativelyOperable) {
        el.addEventListener('keydown', (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                handler.call(el, event);
            }
        });
    }
}
