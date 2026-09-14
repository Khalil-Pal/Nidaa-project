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
 *
 * Prerequisites: the app running against the gate database (see acceptance.sh),
 * puppeteer-core resolvable (npm install in scripts/gate, or NODE_PATH), and
 * Chrome or Edge installed.
 *
 * Usage:
 *   BASE=http://127.0.0.1:8081 ADMIN_EMAIL=... ADMIN_PASSWORD=... BENE_EMAIL=... \
 *   BENE_PASSWORD=... VOL_EMAIL=... VOL_PASSWORD=... JWT_SECRET=... \
 *   CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe" \
 *   node scripts/gate/browser-checks.js
 */
const puppeteer = require('puppeteer-core');
const crypto = require('crypto');

const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
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
    return r.body;
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
