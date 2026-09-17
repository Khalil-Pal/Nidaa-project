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
 *   VER  admin-users.html shows a psychologist's credential status and the
 *        "Verify Credentials" action in the details modal sets it (audited on the
 *        server); the badge in the table follows
 *   N-1  a volunteer accepting a request produces an unread notification on the
 *        beneficiary's open page within 60 s without a reload; opening it lists
 *        the notification and clicking it marks it read and goes to the request
 *   R-1  on a completed request the volunteer's card offers "Record what you
 *        delivered" and the beneficiary's "Rate this help"; both prompts work and
 *        the second view shows what the first recorded
 *   W-1  the assigned volunteer's card offers "On my way"; one tap moves the
 *        request to IN_PROGRESS, the chip changes on both sides and the
 *        beneficiary is notified
 *   ON-2 a volunteer files a request for someone else through the form (toggle,
 *        beneficiary fields, consent copy); the card carries "Filed on behalf of
 *        <name>" and no "Start Working"; the admin queue shows the badge
 *   CS-1 on a completed psychological case the psychologist's session offers
 *        "Record consultation" and the beneficiary's request "Rate this
 *        consultation"; the beneficiary sees the recommendations but never the
 *        psychologist's private note; the rating lands in the database
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

        // ---------------- VER: credential verification from the admin UI ----------------
        // the psychologist created here is also the one CS-1 records a consultation as
        const psyEmail = `browser-psy-${Date.now()}@example.test`;
        const psyPassword = 'Browser-Gate-2026!';
        {
            // a freshly approved psychologist: off duty, unverified
            const email = psyEmail;
            await api('/api/auth/register', { method: 'POST', body: JSON.stringify({ fullName: 'Browser Psychologist', email, password: psyPassword, phone: '+15550001111', role: 'psychologist' }) });
            await api('/api/auth/register/verify', { method: 'POST', body: JSON.stringify({ email, code: sql(`select code from pending_registrations where email='${email}'`) }) });
            const uid = sql(`select user_id from users where email='${email}'`);
            await api(`/api/admin/approve/${uid}`, { method: 'PUT' }, admin.token);
            check('VER', `approved psychologist starts off duty and unverified (${sql(`select is_on_duty||'/'||is_verified from psychologists where user_id=${uid}`)})`,
                sql(`select is_on_duty||'/'||is_verified from psychologists where user_id=${uid}`) === 'false/false');

            const page = await browser.newPage();
            page.on('dialog', async (d) => { await d.accept(); });   // the confirm() before the action
            await signIn(page, admin);
            await page.goto(BASE + '/admin-users.html', { waitUntil: 'networkidle0' });
            await page.waitForFunction((id) => !!document.querySelector(`tr[data-user-id="${id}"]`), { timeout: 15000 }, uid);
            const badgeBefore = await page.$eval(`tr[data-user-id="${uid}"] .cred-badge`, (el) => el.textContent.trim());
            check('VER', `table badge before: "${badgeBefore}"`, badgeBefore === 'unverified');
            await page.click(`tr[data-user-id="${uid}"]`);
            await page.waitForFunction(() => document.getElementById('userModal').classList.contains('open'), { timeout: 5000 });
            const rowShown = await page.$eval('#modalCredentialsRow', (el) => !el.hidden);
            const btnText = await page.$eval('#modalVerifyBtn', (el) => ({ hidden: el.hidden, text: el.textContent.trim() }));
            check('VER', `modal shows the credentials row and "${btnText.text}"`, rowShown && !btnText.hidden && /Verify Credentials/.test(btnText.text));
            await page.click('#modalVerifyBtn');
            await page.waitForFunction((id) => {
                const b = document.querySelector(`tr[data-user-id="${id}"] .cred-badge`);
                return b && b.classList.contains('ok');
            }, { timeout: 15000 }, uid);
            const badgeAfter = await page.$eval(`tr[data-user-id="${uid}"] .cred-badge`, (el) => el.textContent.trim());
            const modalAfter = await page.$eval('#modalCredentials', (el) => el.textContent);
            const btnAfter = await page.$eval('#modalVerifyBtn', (el) => el.textContent.trim());
            check('VER', `table badge after: "${badgeAfter}"; modal: "${modalAfter.slice(0, 60)}"`, /verified/.test(badgeAfter) && /Verified/.test(modalAfter) && /off duty/.test(modalAfter));
            check('VER', `action flips to "${btnAfter}"`, /Revoke/.test(btnAfter));
            const db = sql(`select is_verified||'/'||(verified_by is not null)||'/'||(select count(*) from activity_logs where action='PSYCHOLOGIST_VERIFIED' and entity_id=${uid}) from psychologists where user_id=${uid}`);
            check('VER', `database: is_verified/verified_by set/audit rows = ${db}`, db === 'true/true/1');
            // a non-psychologist row has no badge and no action
            const beneRow = await page.evaluate((mail) => {
                const cell = [...document.querySelectorAll('#usersTableBody tr')].find((tr) => tr.textContent.includes(mail));
                return cell ? { badge: !!cell.querySelector('.cred-badge'), id: cell.dataset.userId } : null;
            }, bene.email);
            check('VER', 'a beneficiary row carries no credentials badge', beneRow && !beneRow.badge);
            await page.close();
        }

        // ---------------- N-1: the bell updates without a reload ----------------
        {
            // the L-1 section left its request ASSIGNED to the volunteer, which makes them unavailable
            const open = await api('/api/help-requests/my', {}, bene.token);
            for (const r of (open.body && open.body.data) || []) {
                if (r.status === 'ASSIGNED') await api(`/api/help-requests/${r.id}/status?status=COMPLETED`, { method: 'PUT' }, vol.token);
            }
            // a fresh request from the beneficiary, then the page open before anything happens
            const created = await api('/api/help-requests', { method: 'POST', body: JSON.stringify({
                title: 'Browser gate: notification', helpType: 'FOOD', urgencyLevel: 'MEDIUM', peopleCount: 1 }) }, bene.token);
            const reqId = created.body && created.body.data && created.body.data.id;
            check('N-1', `request created for the notification check (${created.status}, id ${reqId})`, created.status === 200 && !!reqId);
            const page = await browser.newPage();
            await signIn(page, bene);
            await page.goto(BASE + '/dashboard.html', { waitUntil: 'networkidle0' });
            await page.waitForSelector('#notifBell', { timeout: 10000 });
            const before = await page.evaluate(() => ({ hidden: document.getElementById('notifCount').hidden, text: document.getElementById('notifCount').textContent }));
            const unreadBefore = before.hidden ? 0 : Number(before.text);

            // the volunteer accepts from another client; the beneficiary's page is not touched
            const accepted = await api(`/api/help-requests/${reqId}/assign`, { method: 'PUT' }, vol.token);
            check('N-1', `volunteer accepted through the API (${accepted.status})`, accepted.status === 200);
            const t0 = Date.now();
            await page.waitForFunction((n) => {
                const c = document.getElementById('notifCount');
                return c && !c.hidden && Number(c.textContent) > n;
            }, { timeout: 70000, polling: 500 }, unreadBefore);
            const seconds = Math.round((Date.now() - t0) / 1000);
            const label = await page.$eval('#notifBell', (el) => el.getAttribute('aria-label'));
            check('N-1', `bell count rose ${unreadBefore} -> ${await page.$eval('#notifCount', (el) => el.textContent)} after ${seconds}s without reload; aria-label "${label}"`,
                seconds <= 65 && /unread/.test(label) && page.url().includes('dashboard.html'));

            await page.click('#notifBell');
            await page.waitForFunction(() => document.querySelectorAll('#notifList .notif-item').length > 0, { timeout: 10000 });
            const first = await page.$eval('#notifList .notif-item', (el) => ({ title: el.querySelector('.notif-title').textContent, unread: el.classList.contains('unread'), ref: el.dataset.ref }));
            check('N-1', `panel lists "${first.title}" (unread ${first.unread}, ref ${first.ref})`, first.title === 'Your request was accepted' && first.unread && first.ref === 'HELP_REQUEST');
            await Promise.all([
                page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 15000 }),
                page.click('#notifList .notif-item')
            ]);
            check('N-1', `clicking it goes to ${page.url().replace(BASE, '')}`, page.url().includes('help-requests.html'));
            const unreadNow = await api('/api/notifications/unread-count', {}, bene.token);
            check('N-1', `it is read on the server (unread now ${unreadNow.body.data.unread})`, unreadNow.body.data.unread === unreadBefore);

            // the volunteer cannot read the beneficiary's notification
            const mine = await api('/api/notifications?size=1', {}, bene.token);
            const nid = mine.body.data.content[0].id;
            const foreign = await api(`/api/notifications/${nid}/read`, { method: 'PUT' }, vol.token);
            check('N-1', `another user marking it read -> ${foreign.status}`, foreign.status === 404);
            await page.close();

            // ---------------- W-1: "On my way" on the assigned request ----------------
            {
                const cardOf = (p, id) => p.evaluateHandle((rid) => [...document.querySelectorAll('#cardsGrid .req-card')]
                    .find((c) => c.querySelector('.fa-hashtag') && c.querySelector('.fa-hashtag').parentElement.textContent.trim() === String(rid)), id);
                const vpage = await browser.newPage();
                await signIn(vpage, vol);
                await vpage.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
                await vpage.waitForFunction(() => document.querySelectorAll('#cardsGrid .req-card').length > 0, { timeout: 15000 });
                let card = await cardOf(vpage, reqId);
                const onMyWay = await card.evaluate((c) => { const b = c.querySelector('button[data-action="onmyway"]'); return b ? b.textContent.trim() : null; });
                check('W-1', `volunteer's assigned card offers "${onMyWay}"`, onMyWay === 'On my way');
                await card.evaluate((c) => c.querySelector('button[data-action="onmyway"]').click());
                await vpage.waitForFunction((rid) => {
                    const c = [...document.querySelectorAll('#cardsGrid .req-card')].find((x) => x.querySelector('.fa-hashtag') && x.querySelector('.fa-hashtag').parentElement.textContent.trim() === String(rid));
                    return c && /IN PROGRESS/.test(c.querySelector('.card-badges').textContent);
                }, { timeout: 15000 }, reqId);
                card = await cardOf(vpage, reqId);
                const after = await card.evaluate((c) => ({
                    chip: [...c.querySelectorAll('.card-badges .tag')].map((t) => t.textContent.trim()).pop(),
                    chipClass: [...c.querySelectorAll('.card-badges .tag')].pop().className,
                    button: !!c.querySelector('button[data-action="onmyway"]'),
                    contact: !!c.querySelector('button[data-action="contact"]') }));
                check('W-1', `after the tap the chip reads "${after.chip}" (${after.chipClass}), the button is gone, contact stays`,
                    after.chip === 'IN PROGRESS' && /status-inprogress/.test(after.chipClass) && !after.button && after.contact);
                await vpage.close();

                const bpage = await browser.newPage();
                await signIn(bpage, bene);
                await bpage.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
                await bpage.waitForFunction(() => document.querySelectorAll('#cardsGrid .req-card').length > 0, { timeout: 15000 });
                const bcard = await cardOf(bpage, reqId);
                const seen = await bcard.evaluate((c) => ({
                    chip: [...c.querySelectorAll('.card-badges .tag')].map((t) => t.textContent.trim()).pop(),
                    contact: !!c.querySelector('button[data-action="contact"]') }));
                check('W-1', `the beneficiary's card shows "${seen.chip}" and still offers the contact`, seen.chip === 'IN PROGRESS' && seen.contact);
                await bpage.close();
                const db = sql(`select h.status||'/'||(select count(*) from notifications n where n.reference_id=h.request_id and n.title='Request in progress') from help_requests h where h.request_id=${reqId}`);
                check('W-1', `database: status/notifications = ${db}`, db === 'IN_PROGRESS/1');
            }

            // leave the request in a terminal state so the volunteer is free for the next run (IN_PROGRESS -> COMPLETED)
            await api(`/api/help-requests/${reqId}/status?status=COMPLETED`, { method: 'PUT' }, vol.token);

            // ---------------- R-1: the prompts on the completed request ----------------
            {
                const vpage = await browser.newPage();
                vpage.on('dialog', async (d) => { await d.dismiss(); });
                await signIn(vpage, vol);
                await vpage.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
                await vpage.waitForFunction(() => document.querySelectorAll('#cardsGrid .req-card').length > 0, { timeout: 15000 });
                const card = await vpage.evaluateHandle((id) => [...document.querySelectorAll('#cardsGrid .req-card')]
                    .find((c) => c.querySelector('.fa-hashtag') && c.querySelector('.fa-hashtag').parentElement.textContent.trim() === String(id)), reqId);
                const promptText = await card.evaluate((c) => { const b = c.querySelector('button[data-action="report"]'); return b ? b.textContent.trim() : null; });
                check('R-1', `volunteer's completed card offers "${promptText}"`, promptText === 'Record what you delivered');
                await card.evaluate((c) => c.querySelector('button[data-action="report"]').click());
                await vpage.waitForSelector('#reportDescription', { timeout: 15000 });
                await vpage.type('#reportDescription', 'Food parcel for one person, delivered by bike');
                await vpage.click('#reportSubmitBtn');
                await vpage.waitForFunction(() => !document.getElementById('reportForm') && document.querySelector('#reportBody .report-block'), { timeout: 15000 });
                const recorded = await vpage.$eval('#reportBody', (el) => el.textContent);
                check('R-1', 'after submitting, the modal shows the recorded delivery and "not rated yet"', /delivered by bike/.test(recorded) && /not rated/.test(recorded));
                await vpage.close();

                const bpage = await browser.newPage();
                await signIn(bpage, bene);
                await bpage.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
                await bpage.waitForFunction(() => document.querySelectorAll('#cardsGrid .req-card').length > 0, { timeout: 15000 });
                const bcard = await bpage.evaluateHandle((id) => [...document.querySelectorAll('#cardsGrid .req-card')]
                    .find((c) => c.querySelector('.fa-hashtag') && c.querySelector('.fa-hashtag').parentElement.textContent.trim() === String(id)), reqId);
                const bprompt = await bcard.evaluate((c) => { const b = c.querySelector('button[data-action="report"]'); return b ? b.textContent.trim() : null; });
                check('R-1', `beneficiary's completed card offers "${bprompt}"`, bprompt === 'Rate this help');
                await bcard.evaluate((c) => c.querySelector('button[data-action="report"]').click());
                await bpage.waitForSelector('#feedbackForm', { timeout: 15000 });
                const seesReport = await bpage.$eval('#reportBody', (el) => /delivered by bike/.test(el.textContent));
                check('R-1', 'the beneficiary sees what the volunteer recorded before rating', seesReport);
                await bpage.click('label[for="rating5"]');
                await bpage.type('#feedbackText', 'Fast and friendly');
                await bpage.click('#feedbackSubmitBtn');
                await bpage.waitForFunction(() => !document.getElementById('feedbackForm') && document.querySelector('.rating-given'), { timeout: 15000 });
                const stars = await bpage.$eval('.rating-given', (el) => el.getAttribute('aria-label'));
                check('R-1', `rating shown as "${stars}"`, stars === '5 out of 5');
                const row = sql(`select beneficiary_rating||'/'||feedback_from_beneficiary from reports r join assignments a on a.assignment_id=r.assignment_id where a.request_id=${reqId}`);
                check('R-1', `database row: ${row}`, row === '5/Fast and friendly');
                await bpage.close();
            }
        }

        // ---------------- ON-2: filing for someone else through the UI ----------------
        {
            const stamp = Date.now();
            const personEmail = `browser-filed-${stamp}@example.test`;
            const vpage = await browser.newPage();
            vpage.on('dialog', async (d) => { await d.dismiss(); });
            await signIn(vpage, vol);
            await vpage.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' });
            const fabShown = await vpage.$eval('#fabBtn', (el) => getComputedStyle(el).display !== 'none' && el.getAttribute('aria-label'));
            check('ON-2', `the volunteer gets the "+" button (${fabShown})`, /someone/.test(String(fabShown)));
            await vpage.click('#fabBtn');
            await vpage.waitForFunction(() => document.getElementById('overlay').classList.contains('open'), { timeout: 5000 });
            const form = await vpage.evaluate(() => ({
                toggleShown: !document.getElementById('onBehalfGroup').hidden,
                toggleOn: document.getElementById('onBehalfToggle').checked,
                fieldsShown: !document.getElementById('onBehalfFields').hidden,
                consent: document.querySelector('#onBehalfFields .consent-copy').textContent }));
            check('ON-2', 'the form shows the "filing for someone else" toggle, on, with the fields and the consent copy',
                form.toggleShown && form.toggleOn && form.fieldsShown && /agreed/.test(form.consent) && /not be able to accept/.test(form.consent));
            // off hides the fields, on brings them back
            await vpage.click('#onBehalfToggle');
            const hiddenWhenOff = await vpage.$eval('#onBehalfFields', (el) => el.hidden);
            await vpage.click('#onBehalfToggle');
            const shownWhenOn = await vpage.$eval('#onBehalfFields', (el) => !el.hidden);
            check('ON-2', 'the toggle hides and reveals the beneficiary fields', hiddenWhenOff && shownWhenOn);
            await vpage.type('#onBehalfName', 'Nadia Browser');
            await vpage.type('#onBehalfEmail', personEmail);
            await vpage.type('#onBehalfPhone', '+15550003333');
            await vpage.type('#reqTitle', 'Browser gate: filed for Nadia');
            await vpage.select('#reqType', 'FOOD');
            await vpage.select('#reqUrgency', 'MEDIUM');
            await vpage.type('#reqPeople', '3');
            await vpage.click('#submitBtn');
            await vpage.waitForFunction(() => [...document.querySelectorAll('#cardsGrid .req-card')].some((c) => /filed for Nadia/.test(c.textContent)), { timeout: 15000 });
            const card = await vpage.evaluate(() => {
                const c = [...document.querySelectorAll('#cardsGrid .req-card')].find((x) => /filed for Nadia/.test(x.textContent));
                const badge = c.querySelector('.tag-filed');
                return { badge: badge ? badge.textContent.trim() : null, start: !!c.querySelector('button[data-action="start"]'),
                    note: c.querySelector('[data-filer-note]') ? c.querySelector('[data-filer-note]').textContent : null,
                    id: c.querySelector('.fa-hashtag').parentElement.textContent.trim() };
            });
            check('ON-2', `the filer's card shows the badge "${card.badge}"`, card.badge === 'Filed on behalf of Nadia Browser');
            check('ON-2', `no "Start Working" for the filer; instead: "${card.note}"`, !card.start && /another provider will deliver/.test(String(card.note)));
            await vpage.close();
            const filedId = Number(card.id);
            const dbRow = sql(`select (h.filed_by_user_id = ${vol.userId})||'/'||u.full_name||'/'||u.email||'/'||u.role||'/'||u.is_verified from help_requests h join users u on u.user_id=h.beneficiary_id where h.request_id=${filedId}`);
            check('ON-2', `database: filed by the volunteer for a new unverified beneficiary (${dbRow})`, dbRow === `true/Nadia Browser/${personEmail}/BENEFICIARY/false`);
            const selfAccept = await api(`/api/help-requests/${filedId}/assign`, { method: 'PUT' }, vol.token);
            check('ON-2', `the filer cannot accept their own filed request (${selfAccept.status})`, selfAccept.status === 400);

            // the admin queue shows the badge with the name
            const apage = await browser.newPage();
            await signIn(apage, admin);
            await apage.goto(BASE + '/admin-requests.html', { waitUntil: 'networkidle0' });
            await apage.waitForFunction((id) => !!document.querySelector(`#requestsBody tr`) && [...document.querySelectorAll('#requestsBody tr')].some((tr) => tr.textContent.includes('#' + id)), { timeout: 15000, polling: 500 }, filedId).catch(() => {});
            const adminBadge = await apage.evaluate((id) => {
                const tr = [...document.querySelectorAll('#requestsBody tr')].find((t) => t.textContent.includes('#' + id));
                const b = tr && tr.querySelector('.tag-filed');
                return b ? b.textContent.trim() : (tr ? 'row without badge' : 'row not found');
            }, filedId);
            check('ON-2', `admin queue badge: "${adminBadge}"`, adminBadge === 'Filed on behalf of Nadia Browser');
            await apage.close();
        }

        // ---------------- CS-1: the consultation record and its rating ----------------
        {
            // an anonymous case, accepted and completed through the API by the VER psychologist
            const psy = await login(psyEmail, psyPassword);
            const created = await api('/api/psychological-requests', { method: 'POST', body: JSON.stringify({
                supportType: 'INDIVIDUAL', category: 'GRIEF', preferredFormat: 'VIDEO', isAnonymous: true,
                description: 'Browser gate: consultation record' }) }, bene.token);
            const caseId = created.body && created.body.data && created.body.data.id;
            const accepted = await api(`/api/psychological-requests/${caseId}/accept`, { method: 'PUT' }, psy.token);
            const completed = await api(`/api/psychological-requests/${caseId}/status?status=COMPLETED`, { method: 'PUT' }, psy.token);
            check('CS-1', `case ${caseId} accepted (${accepted.status}) and completed (${completed.status}) by the psychologist`, accepted.status === 200 && completed.status === 200);

            const ppage = await browser.newPage();
            ppage.on('dialog', async (d) => { await d.dismiss(); });
            await signIn(ppage, psy);
            await ppage.goto(BASE + '/psychological.html', { waitUntil: 'networkidle0' });
            await ppage.waitForFunction((id) => !!document.querySelector(`#as-${id} button[data-action="consultation"]`), { timeout: 15000 }, caseId);
            const prompt = await ppage.$eval(`#as-${caseId} button[data-action="consultation"]`, (b) => b.textContent.trim());
            check('CS-1', `psychologist's completed session offers "${prompt}"`, prompt === 'Record consultation');
            await ppage.click(`#as-${caseId} button[data-action="consultation"]`);
            await ppage.waitForSelector('#consultationForm', { timeout: 15000 });
            const preselected = await ppage.$eval('#consultFormat', (el) => el.value);
            check('CS-1', `the form preselects the case's preferred format (${preselected})`, preselected === 'VIDEO');
            await ppage.type('#consultDuration', '45');
            await ppage.type('#consultTopics', 'sleep, grief');
            await ppage.type('#consultRecommendations', 'Keep a sleep diary');
            await ppage.type('#consultNotes', 'PRIVATE-NOTE consider referral');
            await ppage.click('#consultationSubmitBtn');
            await ppage.waitForFunction(() => !document.getElementById('consultationForm') && document.querySelector('#consultationBody .report-block'), { timeout: 15000 });
            const recorded = await ppage.$eval('#consultationBody', (el) => el.textContent);
            check('CS-1', 'after submitting, the psychologist sees the session, the recommendations, their private note and "not rated yet"',
                /Video session · 45 min/.test(recorded) && /Keep a sleep diary/.test(recorded) && /PRIVATE-NOTE/.test(recorded) && /not rated/.test(recorded));
            const chips = await ppage.$$eval('#consultationBody .topic-chip', (els) => els.map((e) => e.textContent.trim()));
            check('CS-1', `topics rendered as chips: ${chips.join('|')}`, chips.join('|') === 'sleep|grief');
            await ppage.close();

            const bpage = await browser.newPage();
            await signIn(bpage, bene);
            await bpage.goto(BASE + '/psychological.html', { waitUntil: 'networkidle0' });
            await bpage.waitForFunction((id) => !!document.querySelector(`#myRequestsList button[data-action="consultation"][data-id="${id}"]`), { timeout: 15000 }, caseId);
            const bprompt = await bpage.$eval(`#myRequestsList button[data-action="consultation"][data-id="${caseId}"]`, (b) => b.textContent.trim());
            check('CS-1', `beneficiary's completed request offers "${bprompt}"`, bprompt === 'Rate this consultation');
            await bpage.click(`#myRequestsList button[data-action="consultation"][data-id="${caseId}"]`);
            await bpage.waitForSelector('#consultFeedbackForm', { timeout: 15000 });
            const seen = await bpage.$eval('#consultationBody', (el) => el.textContent);
            check('CS-1', 'the beneficiary sees the recommendations and never the private note', /Keep a sleep diary/.test(seen) && !/PRIVATE-NOTE/.test(seen) && !/Private notes/.test(seen));
            await bpage.click('label[for="consultRating5"]');
            await bpage.type('#consultFeedbackText', 'Felt heard');
            await bpage.click('#consultFeedbackSubmitBtn');
            await bpage.waitForFunction(() => !document.getElementById('consultFeedbackForm') && document.querySelector('.rating-given'), { timeout: 15000 });
            const stars = await bpage.$eval('.rating-given', (el) => el.getAttribute('aria-label'));
            check('CS-1', `rating shown as "${stars}"`, stars === '5 out of 5');
            const row = sql(`select rating||'/'||feedback_from_beneficiary||'/'||cast(format as text)||'/'||(assignment_id is not null) from consultations where psychological_request_id=${caseId}`);
            check('CS-1', `database row: ${row}`, row === '5/Felt heard/VIDEO/true');
            await bpage.close();
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
