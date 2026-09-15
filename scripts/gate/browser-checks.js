/*
 * Gate checks that need a real browser: what the pages actually render and do.
 *
 *   S-2  a stored title of <img src=x onerror=alert(1)> renders as literal text in
 *        the admin queue, no <img> is created and no dialog fires
 *   L-1  with location granted, the form captures coordinates and the submitted
 *        request is auto-matched; with location denied, the user is told the
 *        request will be matched manually
 *   F-4  an expired access token in the browser is refreshed silently: the page
 *        keeps working, the token changes, and no redirect to login happens
 *   REG  sign-up completes in the browser: register.html sends the code, the code
 *        step creates the account, a beneficiary lands on the dashboard signed in
 *        and can sign in again from login.html; a volunteer is told to wait for
 *        approval and gets no token
 *
 * Prerequisites: the app running against the gate database (see acceptance.sh),
 * psql access to that database (REG reads the e-mailed code from
 * pending_registrations, like acceptance.sh), puppeteer-core resolvable
 * (npm install in scripts/gate, or NODE_PATH), and Chrome or Edge installed.
 *
 * Usage:
 *   BASE=http://127.0.0.1:8081 ADMIN_EMAIL=... ADMIN_PASSWORD=... BENE_EMAIL=... \
 *   BENE_PASSWORD=... VOL_EMAIL=... VOL_PASSWORD=... JWT_SECRET=... \
 *   DB=nidaa_gate PGUSER=postgres PSQL="C:/Program Files/PostgreSQL/17/bin/psql.exe" \
 *   CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe" \
 *   node scripts/gate/browser-checks.js
 */
const puppeteer = require('puppeteer-core');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const DB = process.env.DB || 'nidaa_gate';
const PGUSER = process.env.PGUSER || 'postgres';
const PSQL = process.env.PSQL || 'psql';
const env = (k) => { if (!process.env[k]) throw new Error('missing env ' + k); return process.env[k]; };

let pass = 0, fail = 0;
const ok = (id, msg) => { pass++; console.log(`  PASS  ${id.padEnd(6)} ${msg}`); };
const ko = (id, msg) => { fail++; console.log(`  FAIL  ${id.padEnd(6)} ${msg}`); };
const check = (id, msg, cond) => (cond ? ok(id, msg) : ko(id, msg));

async function api(path, opts = {}, token) {
    const headers = { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) };
    const res = await fetch(BASE + path, { ...opts, headers });
    let body = null; try { body = await res.json(); } catch (e) { /* no body */ }
    return { status: res.status, body };
}
async function login(email, password) {
    const r = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
    if (r.status !== 200) throw new Error(`login ${email} -> ${r.status} ${JSON.stringify(r.body)}`);
    return r.body.data;
}
function expiredToken(secret, email) {
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const h = b64({ alg: 'HS256', typ: 'JWT' });
    const p = b64({ sub: email, type: 'access', iat: now - 7200, exp: now - 3600 });
    const sig = crypto.createHmac('sha256', secret).update(`${h}.${p}`).digest('base64url');
    return `${h}.${p}.${sig}`;
}
/** Puts a login response into the page's localStorage exactly as login.html does. */
async function signIn(page, auth) {
    await page.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' });
    await page.evaluate((a) => storeSession(a), auth);
}
/** One scalar from the gate database (the same psql call acceptance.sh makes). */
function sql(query) {
    return execFileSync(PSQL, ['-U', PGUSER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-tAc', query], { encoding: 'utf8' }).replace(/\r/g, '').trim();
}
/** Fills register.html's step 1 for the given role and submits it; returns the page on the code step. */
async function registerThroughUi(context, { fullName, email, password, role }) {
    // register.html sends a signed-in browser to the dashboard, so the context must hold no session
    const page = await context.newPage();
    await page.goto(BASE + '/register.html', { waitUntil: 'domcontentloaded' });
    await page.type('#fullName', fullName);
    await page.type('#email', email);
    await page.type('#phone', '+1555' + String(Date.now()).slice(-7));
    await page.type('#password', password);
    await page.click('#role-' + role);
    await page.click('#terms');
    await page.click('#registerBtn');
    await page.waitForFunction(() => !document.getElementById('verifyForm').hidden, { timeout: 15000 });
    return page;
}

(async () => {
    const admin = await login(env('ADMIN_EMAIL'), env('ADMIN_PASSWORD'));
    const bene = await login(env('BENE_EMAIL'), env('BENE_PASSWORD'));
    const vol = await login(env('VOL_EMAIL'), env('VOL_PASSWORD'));

    // Fixtures: an available volunteer with a FOOD resource and a location near Tripoli;
    // a request whose title is the XSS payload, inserted through the API is refused
    // (S-2 layer 2), so it is planted directly in the database by the caller (see
    // acceptance run) or here via the gate SQL hook if provided.
    await api('/api/users/me/profile', { method: 'PUT', body: JSON.stringify({ address: 'Tripoli', latitude: 32.8872, longitude: 13.1913 }) }, vol.token);
    await api('/api/provider-resources', { method: 'PUT', body: JSON.stringify({ helpType: 'FOOD', capacityMode: 'NUMERIC', capacityAmount: 50 }) }, vol.token);
    // A previous run leaves its request ASSIGNED to this volunteer, which makes the
    // volunteer unavailable; completing it releases them so the run is repeatable.
    const mineBefore = await api('/api/help-requests/my', {}, bene.token);
    for (const r of (mineBefore.body && mineBefore.body.data) || []) {
        if (r.title === 'Browser gate: food for three' && r.status === 'ASSIGNED') {
            await api(`/api/help-requests/${r.id}/status?status=COMPLETED`, { method: 'PUT' }, vol.token);
        }
    }

    const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
    try {
        // ---------------- S-2: stored XSS renders as text ----------------
        {
            const page = await browser.newPage();
            let dialogs = 0; page.on('dialog', async (d) => { dialogs++; await d.dismiss(); });
            let pageErrors = []; page.on('pageerror', (e) => pageErrors.push(String(e)));
            await signIn(page, admin);
            await page.goto(BASE + '/admin-requests.html', { waitUntil: 'networkidle0' });
            await page.waitForFunction(() => document.querySelectorAll('#requestsBody tr').length > 0, { timeout: 15000 });
            const payload = '<img src=x onerror=alert(1)>';
            const rows = await page.$$eval('#requestsBody tr', (trs) => trs.map((tr) => tr.innerText));
            const hit = rows.find((t) => t.includes(payload));
            check('S-2', 'admin queue shows the payload as literal text', !!hit);
            const imgs = await page.$$eval('#requestsBody img', (els) => els.length);
            check('S-2', `no <img> element created inside the queue (found ${imgs})`, imgs === 0);
            check('S-2', `no alert dialog fired (${dialogs})`, dialogs === 0);
            check('S-2', `no page errors (${pageErrors.length})`, pageErrors.length === 0);
            await page.close();
        }

        // ---------------- L-1: geolocation granted -> auto-matched ----------------
        {
            const context = browser.defaultBrowserContext();
            await context.overridePermissions(BASE, ['geolocation']);
            const page = await browser.newPage();
            await page.setGeolocation({ latitude: 32.8925, longitude: 13.1802 });
            await signIn(page, bene);
            await page.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
            await page.evaluate(() => openModal());
            await page.click('#useMyLocationBtn');
            await page.waitForFunction(() => document.getElementById('reqLocationStatus').textContent.includes('Location captured'), { timeout: 10000 });
            const status = await page.$eval('#reqLocationStatus', (el) => el.textContent);
            check('L-1', 'status after granting location: "' + status.slice(0, 60) + '..."', status.includes('assigned automatically'));
            await page.type('#reqTitle', 'Browser gate: food for three');
            await page.select('#reqType', 'FOOD');
            await page.select('#reqUrgency', 'HIGH');
            await page.type('#reqPeople', '3');
            await page.click('#submitBtn');
            await page.waitForFunction(() => [...document.querySelectorAll('div')].some((d) => d.textContent.startsWith('\u2705 Request saved')), { timeout: 15000 });
            const toast = await page.evaluate(() => [...document.querySelectorAll('div')].map((d) => d.textContent).find((t) => t.startsWith('\u2705 Request saved')));
            check('L-1', 'toast reports automatic matching: "' + toast + '"', toast.includes('matched to the nearest available provider'));
            const mine = await api('/api/help-requests/my', {}, bene.token);
            const created = (mine.body.data || []).find((r) => r.title === 'Browser gate: food for three');
            check('L-1', 'request created through the UI is ASSIGNED (' + (created && created.status) + ')', created && created.status === 'ASSIGNED');
            await page.close();
        }

        // ---------------- L-1: geolocation denied -> "matched manually" ----------------
        {
            const context = await browser.createBrowserContext();
            await context.overridePermissions(BASE, []);   // no geolocation permission
            const page = await context.newPage();
            await signIn(page, bene);
            await page.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
            await page.evaluate(() => openModal());
            await page.click('#useMyLocationBtn');
            await page.waitForFunction(() => /declined|could not be determined|matched manually/.test(document.getElementById('reqLocationStatus').textContent), { timeout: 15000 });
            const status = await page.$eval('#reqLocationStatus', (el) => el.textContent);
            check('L-1', 'status after denying location: "' + status.slice(0, 80) + '"', status.includes('matched manually'));
            await context.close();
        }

        // ---------------- REG: sign-up completes in the browser ----------------
        {
            const stamp = Date.now();
            const password = 'Browser-Gate-2026!';
            const context = await browser.createBrowserContext();   // no session from the earlier sections

            // a beneficiary: code step -> account -> signed in on the dashboard -> can log in again
            const email = `browser-bene-${stamp}@example.test`;
            const page = await registerThroughUi(context, { fullName: 'Browser Beneficiary', email, password, role: 'beneficiary' });
            const shown = await page.$eval('#verifyEmail', (el) => el.textContent);
            const detailsHidden = await page.$eval('#registerForm', (el) => el.hidden);
            check('REG', `code step shown for ${shown}, details form hidden (${detailsHidden})`, shown === email && detailsHidden);
            const focused = await page.evaluate(() => document.activeElement && document.activeElement.id);
            check('REG', `focus moved to the code field (${focused})`, focused === 'verifyCode');

            await page.type('#verifyCode', 'WRONG123');
            await page.click('#verifyBtn');
            // the "code sent" notice uses the same alert element, so wait for it to turn into an error
            await page.waitForFunction(() => document.getElementById('errorAlert').classList.contains('alert-error'), { timeout: 10000 });
            const wrongMsg = await page.$eval('#alertMsg', (el) => el.textContent);
            const stillOnStep2 = await page.$eval('#verifyForm', (el) => !el.hidden);
            check('REG', `wrong code is refused and the step stays: "${wrongMsg}"`, /incorrect/i.test(wrongMsg) && stillOnStep2);

            const code = sql(`select code from pending_registrations where email='${email}'`);
            check('REG', `pending registration has an 8-character code (${code.length})`, code.length === 8);
            await page.click('#verifyCode', { clickCount: 3 });
            await page.type('#verifyCode', code.toLowerCase());   // the field upper-cases as you type
            await Promise.all([
                page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 15000 }),
                page.click('#verifyBtn')
            ]);
            const landed = page.url().replace(BASE, '');
            const stored = await page.evaluate(() => ({ token: !!localStorage.getItem('token'), user: JSON.parse(localStorage.getItem('user') || '{}') }));
            check('REG', `right code creates the account and lands on ${landed}`, landed.startsWith('/dashboard.html'));
            check('REG', `session stored for ${stored.user.email} (${stored.user.role})`, stored.token && stored.user.email === email && stored.user.role === 'beneficiary');
            const gone = sql(`select count(*) from pending_registrations where email='${email}'`);
            check('REG', `pending registration removed after use (${gone} left)`, gone === '0');

            // the new account signs in from login.html with the chosen password
            await page.evaluate(() => localStorage.clear());
            await page.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' });
            await page.type('#email', email);
            await page.type('#password', password);
            await Promise.all([
                page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 15000 }),
                page.click('button[type="submit"]')
            ]);
            check('REG', `login.html with the new password lands on ${page.url().replace(BASE, '')}`, page.url().includes('dashboard.html'));
            await page.evaluate(() => localStorage.clear());   // the volunteer below must start signed out
            await page.close();

            // a volunteer: the code step ends in the approval notice, no session
            const volEmail = `browser-vol-${stamp}@example.test`;
            const vpage = await registerThroughUi(context, { fullName: 'Browser Volunteer', email: volEmail, password, role: 'volunteer' });
            await vpage.type('#verifyCode', sql(`select code from pending_registrations where email='${volEmail}'`));
            await vpage.click('#verifyBtn');
            await vpage.waitForFunction(() => !document.getElementById('approvalPanel').hidden, { timeout: 15000 });
            const notice = await vpage.$eval('#approvalText', (el) => el.textContent);
            const volToken = await vpage.evaluate(() => localStorage.getItem('token'));
            check('REG', `volunteer sees the approval notice: "${notice.trim().slice(0, 70)}..."`, /volunteer/.test(notice) && /administrator/.test(notice));
            check('REG', 'volunteer gets no session before approval', volToken === null && vpage.url().includes('register.html'));
            const volActive = sql(`select is_active from users where email='${volEmail}'`);
            check('REG', `volunteer account exists and is inactive (is_active=${volActive})`, volActive === 'f');
            await context.close();
        }

        // ---------------- F-4: expired access token is refreshed silently ----------------
        {
            const page = await browser.newPage();
            await signIn(page, bene);
            const expired = expiredToken(env('JWT_SECRET'), bene.email);
            await page.evaluate((t) => localStorage.setItem('token', t), expired);
            const refreshCalls = [];
            page.on('request', (req) => { if (req.url().includes('/api/auth/refresh')) refreshCalls.push(req.url()); });
            page.on('response', async (res) => {
                if (res.url().includes('/api/auth/refresh')) {
                    let text = ''; try { text = await res.text(); } catch (e) { /* ignore */ }
                    console.log(`        refresh -> HTTP ${res.status()} ${text.slice(0, 120)}`);
                }
            });
            await page.goto(BASE + '/dashboard.html', { waitUntil: 'domcontentloaded' });
            // apiFetch must survive 401 -> refresh -> retry: wait for the stored token to change,
            // then for the stats tile the retried call fills in
            await page.waitForFunction((exp) => localStorage.getItem('token') !== exp, { timeout: 15000 }, expired).catch(() => {});
            await page.waitForFunction(() => {
                const el = document.getElementById('statTotal');
                return el && el.textContent.trim() !== '' && el.textContent.trim() !== '—';
            }, { timeout: 15000 }).catch(() => {});
            const url = page.url();
            const newToken = await page.evaluate(() => localStorage.getItem('token'));
            check('F-4', 'no redirect to login (' + url.replace(BASE, '') + ')', !url.includes('login.html'));
            check('F-4', `exactly one refresh call (${refreshCalls.length})`, refreshCalls.length === 1);
            check('F-4', 'access token was replaced', newToken && newToken !== expired);
            const statTotal = await page.$eval('#statTotal', (el) => el.textContent).catch(() => null);
            check('F-4', 'page loaded data after the refresh (statTotal="' + statTotal + '")', statTotal !== null && statTotal !== '—' && statTotal !== '');
            await page.close();
        }
    } finally {
        await browser.close();
    }
    console.log(`\nBROWSER RESULT: ${pass} passed, ${fail} failed`);
    process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('browser checks aborted:', e); process.exit(2); });
