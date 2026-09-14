// register.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

let selectedRole = '';

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
    const btn = document.getElementById('registerBtn');
    if (loading) {
        btn.disabled = true;
        btn.innerHTML = '<div class="spinner"></div> Creating account...';
    } else {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa fa-user-plus"></i> Create Account';
    }
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
        const res = await fetch(`${API}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ fullName, email, phone, password, role: selectedRole })
        });
        const body = await res.json();
        if (!res.ok) throw new Error(body.message || 'Registration failed. Please try again.');

        // Step 1 of 2: the server has emailed a verification code; no session exists yet.
        showAlert('✅ ' + (body.message || 'Verification code sent to ' + ((body.data || {}).email || email) + '.'), 'pending');
        document.getElementById('registerBtn').textContent = '✓ Code Sent';
        document.getElementById('registerBtn').disabled = true;
    } catch (err) {
        showAlert(err.message);
        setLoading(false);
    }
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
