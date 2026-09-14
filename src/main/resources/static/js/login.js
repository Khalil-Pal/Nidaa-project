// login.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

function togglePassword(id, btn) {
    const input = document.getElementById(id);
    if (input.type === 'password') { input.type = 'text'; btn.textContent = '🙈'; }
    else { input.type = 'password'; btn.textContent = '👁'; }
}

function showError(msg) {
    const el = document.getElementById('errorAlert');
    el.textContent = msg; el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 5000);
}

document.getElementById('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    const btn = document.getElementById('signInBtn');

    if (!email || !password) { showError('Please fill in all fields.'); return; }

    btn.disabled = true; btn.textContent = 'Signing in...';

    try {
        const res = await fetch(`${API}/auth/login`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        const body = await res.json();
        if (!res.ok) throw new Error(body.message || 'Invalid email or password.');
        const data = body.data || {};

        // Validate required fields before storing
        if (!data.token || !data.userId || !data.email || !data.fullName || !data.role) {
            throw new Error('Server returned incomplete authentication data. Please contact support.');
        }

        // Save the token pair and user summary so every page shows the right
        // name and apiFetch() can renew the access token silently
        storeSession(data);

        // Admin goes to their own landing page, everyone else to dashboard
        const role = (data.role || '').toLowerCase();
        if (role === 'admin') {
            window.location.href = 'admin-requests.html';
        } else {
            window.location.href = 'dashboard.html';
        }
    } catch (err) {
        showError(err.message);
        btn.disabled = false; btn.textContent = 'Sign In';
    }
});

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('togglePasswordPassword', 'click', function () { togglePassword('password', this); });
