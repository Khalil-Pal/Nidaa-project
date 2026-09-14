// forgot-password.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

let userEmail = '';

function togglePw(id, btn) {
  const inp = document.getElementById(id);
  const ico = btn.querySelector('i');
  if (inp.type === 'password') { inp.type = 'text'; ico.className = 'fa fa-eye-slash'; }
  else { inp.type = 'password'; ico.className = 'fa fa-eye'; }
}

function showError(msg) {
  const el = document.getElementById('errorAlert');
  document.getElementById('alertMsg').textContent = msg;
  document.getElementById('successAlert').classList.remove('show');
  el.className = 'alert alert-error show';
  setTimeout(() => el.classList.remove('show'), 6000);
}

function showSuccess(msg) {
  const el = document.getElementById('successAlert');
  document.getElementById('successMsg').textContent = msg;
  document.getElementById('errorAlert').classList.remove('show');
  el.className = 'alert alert-success show';
}

function setLoading(btnId, loading, text) {
  const btn = document.getElementById(btnId);
  btn.disabled = loading;
  btn.innerHTML = loading
          ? '<div class="spinner"></div> Please wait...'
          : text;
}

function goToStep(step) {
  document.querySelectorAll('.step-panel').forEach(p => p.classList.remove('active'));
  document.getElementById('step' + step).classList.add('active');

  // Update dots
  for (let i = 1; i <= 3; i++) {
    const dot = document.getElementById('dot' + i);
    dot.classList.remove('active', 'done');
    if (i < step) { dot.classList.add('done'); dot.innerHTML = '<i class="fa fa-check"></i>'; }
    else if (i === step) { dot.classList.add('active'); dot.textContent = i; }
    else { dot.textContent = i; }
  }
  for (let i = 1; i <= 2; i++) {
    document.getElementById('line' + i).classList.toggle('done', i < step);
  }

  const titles = ['Forgot Password? 🔐', 'Enter Your Code 🔑', 'Set New Password 🔒'];
  const subs = [
    'Enter your email and we\'ll send you a reset code.',
    'We sent a 6-character code to your email.',
    'Almost done! Choose a strong new password.'
  ];
  document.getElementById('pageTitle').textContent = titles[step - 1];
  document.getElementById('pageSubtitle').textContent = subs[step - 1];
}

async function sendCode() {
  const email = document.getElementById('emailInput').value.trim();
  if (!email) { showError('Please enter your email address.'); return; }
  setLoading('btn1', true, '<i class="fa fa-paper-plane"></i> Send Reset Code');
  try {
    const res = await fetch(`${API}/auth/forgot-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email })
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message);
    userEmail = email;
    showSuccess(data.message);
    setTimeout(() => goToStep(2), 1000);
  } catch (err) {
    showError(err.message);
  }
  setLoading('btn1', false, '<i class="fa fa-paper-plane"></i> Send Reset Code');
}

async function verifyCode() {
  const code = document.getElementById('codeInput').value.trim();
  if (code.length < 6) { showError('Please enter the full 6-character code.'); return; }
  setLoading('btn2', true, '<i class="fa fa-check"></i> Verify Code');
  try {
    const res = await fetch(`${API}/auth/verify-reset-code`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: userEmail, code })
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message);
    showSuccess('Code verified!');
    setTimeout(() => goToStep(3), 800);
  } catch (err) {
    showError(err.message);
  }
  setLoading('btn2', false, '<i class="fa fa-check"></i> Verify Code');
}

async function resetPassword() {
  const newPassword = document.getElementById('newPassword').value;
  const confirmPassword = document.getElementById('confirmPassword').value;
  const code = document.getElementById('codeInput').value.trim();

  if (newPassword.length < 6) { showError('Password must be at least 6 characters.'); return; }
  if (newPassword !== confirmPassword) { showError('Passwords do not match.'); return; }

  setLoading('btn3', true, '<i class="fa fa-shield-halved"></i> Reset Password');
  try {
    const res = await fetch(`${API}/auth/reset-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: userEmail, code, newPassword })
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message);
    showSuccess('✅ Password reset successfully! Redirecting to login...');
    setTimeout(() => window.location.href = 'login.html', 2000);
  } catch (err) {
    showError(err.message);
  }
  setLoading('btn3', false, '<i class="fa fa-shield-halved"></i> Reset Password');
}

async function resendCode() {
  if (!userEmail) return;
  try {
    await fetch(`${API}/auth/forgot-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: userEmail })
    });
    showSuccess('New code sent to your email!');
    document.getElementById('codeInput').value = '';
  } catch (err) {
    showError('Could not resend code. Please try again.');
  }
}

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('btn1', 'click', () => { sendCode(); });
wireEvent('codeInput', 'input', function () { this.value=this.value.toUpperCase(); });
wireEvent('btn2', 'click', () => { verifyCode(); });
wireEvent('resendCodeBtn', 'click', () => { resendCode(); });
wireEvent('togglePwNewPassword', 'click', function () { togglePw('newPassword',this); });
wireEvent('togglePwConfirmPassword', 'click', function () { togglePw('confirmPassword',this); });
wireEvent('btn3', 'click', () => { resetPassword(); });
