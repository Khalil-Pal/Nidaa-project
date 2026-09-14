/*
 * F-5 keyboard-only checks (run against a started app): Tab walks every
 * beneficiary-facing page and operates the main controls with Enter/Space.
 * Usage: same environment variables as a11y-audit.js.
 */
const puppeteer = require('puppeteer-core');
const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const env = (k) => { if (!process.env[k]) throw new Error('missing env ' + k); return process.env[k]; };
let pass = 0, fail = 0;
const check = (id, msg, cond) => { cond ? pass++ : fail++; console.log(`  ${cond ? 'PASS' : 'FAIL'}  ${id.padEnd(26)} ${msg}`); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function login(email, password) {
    const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }) });
    return (await r.json()).data;
}
/** Tabs until an element matching selector has focus (or gives up). */
async function tabTo(page, selector, max = 250) {
    for (let i = 0; i < max; i++) {
        const hit = await page.evaluate((sel) => document.activeElement && document.activeElement.matches(sel), selector);
        if (hit) return i;
        await page.keyboard.press('Tab');
    }
    return -1;
}
async function tabWalk(page, steps) {
    await page.evaluate(() => { window.__focusSeen = new Set(); window.__stuck = 0; window.__hidden = 0; window.__last = null; });
    for (let i = 0; i < steps; i++) {
        await page.keyboard.press('Tab');
        await page.evaluate(() => {
            const el = document.activeElement; if (!el || el === document.body) return;
            if (el === window.__last) window.__stuck++;
            window.__last = el;
            // custom switches hide the checkbox and show a sibling slider; either counts as visible
            const vis = (n) => { if (!n) return false; const r = n.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(n).visibility !== 'hidden'; };
            if (!(vis(el) || vis(el.nextElementSibling) || vis(el.parentElement))) window.__hidden++;
            window.__focusSeen.add(el);
        });
    }
    return page.evaluate(() => ({ distinct: window.__focusSeen.size, stuck: window.__stuck, hidden: window.__hidden }));
}

(async () => {
    const bene = await login(env('BENE_EMAIL'), env('BENE_PASSWORD'));
    const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
    const errors = [];
    try {
        const page = await browser.newPage();
        await page.setViewport({ width: 1280, height: 900 });
        page.on('pageerror', (e) => errors.push(page.url().replace(BASE, '') + ': ' + e.message.slice(0, 100)));
        await page.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' });
        await page.evaluate((s) => storeSession(s), bene);

        // Tab walk on every beneficiary-facing page: focus keeps moving through visible controls
        for (const p of ['index.html', 'dashboard.html', 'help-requests.html', 'psychological.html', 'settings.html', 'profile.html', 'submit-story.html']) {
            await page.goto(BASE + '/' + p, { waitUntil: 'networkidle0' });
            await sleep(400);
            const w = await tabWalk(page, 25);
            check('tab walk ' + p, `${w.distinct} distinct stops in 25 tabs, stuck ${w.stuck}, invisible ${w.hidden}`, w.distinct >= 8 && w.stuck === 0 && w.hidden === 0);
        }

        // help-requests: open the new-request modal from the keyboard, close it with Escape
        await page.goto(BASE + '/help-requests.html', { waitUntil: 'networkidle0' }); await sleep(400);
        let n = await tabTo(page, '#fabBtn');
        await page.keyboard.press('Enter'); await sleep(300);
        let open = await page.$eval('#overlay', (e) => e.classList.contains('open'));
        check('help-requests modal', `Tab x${n} to New Request, Enter opens (${open})`, n >= 0 && open);
        await page.keyboard.press('Escape'); await sleep(200);
        open = await page.$eval('#overlay', (e) => e.classList.contains('open'));
        check('help-requests escape', 'Escape closes the modal', !open);

        // psychological: format card and pill toggles work with Enter/Space and expose aria-pressed
        await page.goto(BASE + '/psychological.html', { waitUntil: 'networkidle0' }); await sleep(400);
        n = await tabTo(page, '#selectFormatVideo');
        await page.keyboard.press('Enter'); await sleep(100);
        const fmt = await page.evaluate(() => ({ sel: document.getElementById('selectFormatVideo').classList.contains('selected'), pressed: document.getElementById('selectFormatVideo').getAttribute('aria-pressed'), value: document.getElementById('supportFormat').value }));
        check('psych format card', `Enter selects video: ${JSON.stringify(fmt)}`, n >= 0 && fmt.sel && fmt.pressed === 'true' && fmt.value === 'video');
        n = await tabTo(page, 'span.pill[data-value="PTSD"]');
        await page.keyboard.press(' '); await sleep(100);
        const pill = await page.$eval('span.pill[data-value="PTSD"]', (e) => ({ active: e.classList.contains('active'), pressed: e.getAttribute('aria-pressed') }));
        check('psych pill', `Space toggles PTSD: ${JSON.stringify(pill)}`, n >= 0 && pill.active && pill.pressed === 'true');
        n = await tabTo(page, '#openModalBtn');
        await page.keyboard.press('Enter'); await sleep(300);
        open = await page.$eval('#overlay', (e) => e.classList.contains('open'));
        check('psych modal', 'Enter on Request Support opens the modal', n >= 0 && open);
        await page.keyboard.press('Escape');

        // dashboard: the "View all" span (now role=button) navigates on Enter
        await page.goto(BASE + '/dashboard.html', { waitUntil: 'networkidle0' }); await sleep(400);
        n = await tabTo(page, '#goHelpRequests');
        if (n >= 0) { await Promise.all([page.waitForNavigation({ waitUntil: 'domcontentloaded' }).catch(() => {}), page.keyboard.press('Enter')]); }
        check('dashboard view all', `Tab x${n}, Enter -> ${page.url().replace(BASE, '')}`, n >= 0 && page.url().includes('help-requests.html'));

        // settings: the duty/availability style switches are native checkboxes; Space toggles the notification switch
        await page.goto(BASE + '/settings.html', { waitUntil: 'networkidle0' }); await sleep(400);
        n = await tabTo(page, '#emailNotif');
        const before = await page.$eval('#emailNotif', (e) => e.checked);
        await page.keyboard.press(' '); await sleep(100);
        const after = await page.$eval('#emailNotif', (e) => e.checked);
        check('settings switch', `Space toggles Email notifications ${before} -> ${after}`, n >= 0 && before !== after);

        // community (not a beneficiary page: they are redirected to the dashboard by design): as admin,
        // category tabs are buttons and Enter switches the active category
        const admin = await login(env('ADMIN_EMAIL'), env('ADMIN_PASSWORD'));
        await page.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' }); await page.evaluate((s) => { localStorage.clear(); storeSession(s); }, admin);
        await page.goto(BASE + '/community.html', { waitUntil: 'networkidle0' }); await sleep(600);
        const cw = await tabWalk(page, 25);
        check('tab walk community.html', `${cw.distinct} distinct stops in 25 tabs, stuck ${cw.stuck}, invisible ${cw.hidden}`, cw.distinct >= 8 && cw.stuck === 0 && cw.hidden === 0);
        n = await tabTo(page, '#setCategoryQuestion');
        if (n >= 0) { await page.keyboard.press('Enter'); await sleep(200); }
        const active = n >= 0 && await page.$eval('#setCategoryQuestion', (e) => e.classList.contains('active'));
        check('community category', 'Enter activates the Questions category tab', n >= 0 && active);

        // login/register: the password toggle is a labelled button that works by keyboard
        await page.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' }); await page.evaluate(() => localStorage.clear()); await page.reload({ waitUntil: 'domcontentloaded' });
        await page.type('#password', 'secret');
        n = await tabTo(page, '#togglePasswordPassword');
        await page.keyboard.press('Enter'); await sleep(100);
        const type = await page.$eval('#password', (e) => e.type);
        const label = await page.$eval('#togglePasswordPassword', (e) => e.getAttribute('aria-label'));
        check('login password toggle', `Enter reveals password (type=${type}), aria-label="${label}"`, n >= 0 && type === 'text' && !!label);

        // focus is visible: the outline rule applies when focus comes from the keyboard
        await page.goto(BASE + '/index.html', { waitUntil: 'networkidle0' });
        await page.keyboard.press('Tab'); await page.keyboard.press('Tab');
        const outline = await page.evaluate(() => { const el = document.activeElement; const cs = getComputedStyle(el); return { el: el.tagName + '.' + el.className, style: cs.outlineStyle, width: cs.outlineWidth }; });
        check('focus visible', `focused ${outline.el}: outline ${outline.style} ${outline.width}`, outline.style !== 'none' && parseFloat(outline.width) >= 2);
    } finally { await browser.close(); }
    check('js errors', errors.length + ' page errors' + (errors.length ? ': ' + errors.slice(0, 3).join(' | ') : ''), errors.length === 0);
    console.log(`\n${pass} passed, ${fail} failed`);
    process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
