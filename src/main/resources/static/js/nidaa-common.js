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

/**
 * fetch() for API calls: prefixes the API base, attaches the bearer token and
 * JSON content type, and lets the caller override or add headers.
 * Token refresh on 401 is added in F-4.
 */
async function apiFetch(path, options = {}) {
    const url = /^https?:\/\//.test(path) ? path : API + (path.startsWith('/') ? path : '/' + path);
    const headers = Object.assign({}, authHeader(), options.headers || {});
    return fetch(url, Object.assign({}, options, { headers }));
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
    localStorage.removeItem('token');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    window.location.href = 'login.html';
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

/**
 * A server-supplied string as a JavaScript string literal inside an inline
 * handler attribute, e.g. onclick="open(${jsString(r.name)})". JSON quoting
 * makes it a valid literal; the HTML escaping survives attribute decoding.
 * escHtml() alone is not enough there, because the browser decodes entities
 * before the handler is parsed as JavaScript.
 */
function jsString(value) {
    return escHtml(JSON.stringify(String(value == null ? '' : value)));
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
 * read from the stored user.
 */
function buildSidebar(currentPage, role) {
    const nav = document.getElementById('sidebarNav');
    if (!nav) return;
    const links = SIDEBAR_PAGES[role || currentUserRole()] || SIDEBAR_PAGES.beneficiary;
    nav.innerHTML = links.map(p =>
        '<a href="' + p.href + '" class="nav-item' + (p.href === currentPage ? ' active' : '') + '">' +
        '<i class="fa ' + p.icon + '"></i> ' + escHtml(p.label) + '</a>'
    ).join('');
}
