// register.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

let selectedRole = '';

// Registration is two steps on the server: POST /auth/register stores the details
// and e-mails a code; POST /auth/register/verify with that code creates the account.
// The details are kept here (never stored) so "Send a new code" can repeat step 1.
let pendingRegistration = null;

function selectRole(r) {
    selectedRole = r;
    document.querySelectorAll('.role-card').forEach(c => c.classList.remove('selected'));
    document.getElementById('role-' + r).classList.add('selected');
}

function togglePw(id, btn) {
    const inp = document.getElementById(id);
    const ico = btn.querySelector('i');
    if (inp.type === 'password') { inp.type = 'text'; ico.className = 'fa fa-eye-slash'; }
    else { inp.type = 'password'; ico.className = 'fa fa-eye'; }
}

function checkStrength(pw) {
    const fill = document.getElementById('pwFill');
    const label = document.getElementById('pwLabel');
    let score = 0;
    if (pw.length >= 8) score++;
    if (/[A-Z]/.test(pw)) score++;
    if (/[0-9]/.test(pw)) score++;
    if (/[^A-Za-z0-9]/.test(pw)) score++;
    const colors = ['#ef4444','#f97316','#eab308','#22c55e'];
    const labels = ['Too weak','Weak','Good','Strong'];
    fill.style.width = (score * 25) + '%';
    fill.style.background = colors[score - 1] || '#e2e8f0';
    label.textContent = score > 0 ? labels[score - 1] : 'Enter a password';
    label.style.color = colors[score - 1] || 'var(--muted)';
}

function showAlert(msg, type = 'error') {
    const el = document.getElementById('errorAlert');
    document.getElementById('alertMsg').textContent = msg;
    el.className = `alert alert-${type} show`;
    if (type === 'error') setTimeout(() => el.classList.remove('show'), 6000);
}

function setLoading(loading) {
    setBusy('registerBtn', loading, 'Sending code...', '<i class="fa fa-user-plus"></i> Create Account');
}

function setBusy(id, busy, busyText, idleHtml) {
    const btn = document.getElementById(id);
    btn.disabled = busy;
    btn.innerHTML = busy ? '<div class="spinner"></div> ' + busyText : idleHtml;
}

/** Shows step 1 (the details form) or step 2 (the code) and moves focus into it. */
function showStep(step) {
    document.getElementById('registerForm').hidden = step !== 1;
    document.getElementById('verifyForm').hidden = step !== 2;
    document.getElementById('approvalPanel').hidden = true;
    document.getElementById('pageTitle').textContent = step === 1 ? 'Join Nidaa 🤝' : 'Check your email 📬';
    document.getElementById('pageSubtitle').textContent = step === 1
        ? 'Create your account and start making a difference'
        : 'One more step: enter the code we just sent you';
    if (step === 2) {
        document.getElementById('verifyEmail').textContent = pendingRegistration.email;
        const code = document.getElementById('verifyCode');
        code.value = '';
        code.focus();
    } else {
        setLoading(false);
    }
}

/** Step 1 for the given details; on success the code step can be shown (or re-shown after a resend). */
async function requestCode(details) {
    const res = await fetch(`${API}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(details)
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.message || 'Registration failed. Please try again.');
    pendingRegistration = { ...details, email: ((body.data || {}).email || details.email) };
    return body.message || 'Verification code sent to ' + pendingRegistration.email + '.';
}

/** Step 2: the code creates the account. Beneficiaries get a session; providers wait for approval. */
async function verifyCode() {
    const code = document.getElementById('verifyCode').value.trim().toUpperCase();
    if (!pendingRegistration) { showStep(1); return; }
    if (code.length !== 8) { showAlert('Please enter the full 8-character code.'); return; }

    setBusy('verifyBtn', true, 'Creating account...', '');
    try {
        const res = await fetch(`${API}/auth/register/verify`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: pendingRegistration.email, code })
        });
        const body = await res.json();
        if (!res.ok) {
            // an expired or missing pending registration can only be fixed by starting again
            if (/register again/i.test(body.message || '')) showStep(1);
            throw new Error(body.message || 'Verification failed. Please try again.');
        }
        const data = body.data || {};
        if (data.token) {
            storeSession(data);
            window.location.href = 'dashboard.html';
            return;
        }
        // No token: the role needs an administrator's approval before the first sign-in.
        const role = String(data.role || pendingRegistration.role || 'provider').toLowerCase();
        document.getElementById('approvalText').textContent =
            ' Because you registered as a ' + role + ', an administrator reviews your application first. ' +
            'You will receive an email when it is approved and can then sign in.';
        document.getElementById('verifyForm').hidden = true;
        document.getElementById('approvalPanel').hidden = false;
        document.getElementById('pageTitle').textContent = 'Almost there ✅';
        document.getElementById('pageSubtitle').textContent = 'Your application is waiting for review';
        showAlert('✅ ' + (body.message || 'Registration complete'), 'success');
    } catch (err) {
        showAlert(err.message);
    }
    setBusy('verifyBtn', false, '', '<i class="fa fa-check"></i> Verify and Create Account');
}

async function resendCode() {
    if (!pendingRegistration) { showStep(1); return; }
    const btn = document.getElementById('resendBtn');
    btn.disabled = true;
    try {
        const message = await requestCode(pendingRegistration);
        document.getElementById('verifyCode').value = '';
        document.getElementById('verifyCode').focus();
        showAlert('✅ ' + message, 'pending');
    } catch (err) {
        showAlert(err.message);
    }
    btn.disabled = false;
}

document.getElementById('registerForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const fullName = document.getElementById('fullName').value.trim();
    const email = document.getElementById('email').value.trim();
    const phone = document.getElementById('phone').value.trim() || null;
    const password = document.getElementById('password').value;

    if (!fullName) { showAlert('Please enter your full name.'); return; }
    if (!email) { showAlert('Please enter your email address.'); return; }
    if (!selectedRole) { showAlert('Please select your role.'); return; }
    if (password.length < 8) { showAlert('Password must be at least 8 characters.'); return; }
    if (!document.getElementById('terms').checked) { showAlert('Please agree to the Terms of Service.'); return; }

    setLoading(true);
    try {
        // Step 1 of 2: the server e-mails a verification code; no account or session exists yet.
        const message = await requestCode({ fullName, email, phone, password, role: selectedRole });
        showAlert('✅ ' + message, 'pending');
        showStep(2);
    } catch (err) {
        showAlert(err.message);
        setLoading(false);
    }
});

document.getElementById('verifyForm').addEventListener('submit', (e) => {
    e.preventDefault();
    verifyCode();
});

if (localStorage.getItem('token')) {
    const u = JSON.parse(localStorage.getItem('user') || '{}');
    if (u.isActive !== false) window.location.href = 'dashboard.html';
}

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('password', 'input', function () { checkStrength(this.value); });
wireEvent('togglePwPassword', 'click', function () { togglePw('password',this); });
wireEvent('selectRoleBeneficiary', 'change', () => { selectRole('beneficiary'); });
wireEvent('selectRoleVolunteer', 'change', () => { selectRole('volunteer'); });
wireEvent('selectRolePsychologist', 'change', () => { selectRole('psychologist'); });
wireEvent('selectRoleOrganization', 'change', () => { selectRole('organization'); });
wireEvent('verifyCode', 'input', function () { this.value = this.value.toUpperCase(); });
wireEvent('resendBtn', 'click', () => { resendCode(); });
wireEvent('backBtn', 'click', () => { showStep(1); });
