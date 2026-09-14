/*
 * F-5 accessibility and CSP audit for every page (run against a started app).
 *   axe-core WCAG 2.1 A/AA violations, CSP violations, page errors, and a count of
 *   inline handlers / inline scripts / javascript: URLs (all must be 0).
 * Usage:
 *   BASE=http://127.0.0.1:8081 ADMIN_EMAIL=... ADMIN_PASSWORD=... BENE_EMAIL=... BENE_PASSWORD=...  *   CHROME="C:/Program Files/Google/Chrome/Application/chrome.exe" node scripts/gate/a11y-audit.js <label>
 * Needs puppeteer-core and axe-core resolvable (npm install --no-save, or NODE_PATH).
 */
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const axeSource = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const env = (k) => { if (!process.env[k]) throw new Error('missing env ' + k); return process.env[k]; };
const label = process.argv[2] || 'run';

const PUBLIC = ['index.html', 'login.html', 'register.html', 'forgot-password.html', 'Privacy.html', 'Terms.html'];
const BENE = ['dashboard.html', 'help-requests.html', 'psychological.html', 'settings.html', 'profile.html', 'submit-story.html'];
// community.html sends beneficiaries to the dashboard by design, so it is audited as admin
const ADMIN = ['community.html', 'admin.html', 'admin-requests.html', 'admin-users.html', 'admin-approvals.html', 'admin-stories.html'];

async function api(path, opts = {}, token) {
    const headers = { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) };
    const res = await fetch(BASE + path, { ...opts, headers });
    let body = null; try { body = await res.json(); } catch (e) {}
    return { status: res.status, body };
}
async function login(email, password) {
    const r = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
    if (r.status !== 200) throw new Error('login ' + email + ' -> ' + r.status);
    return r.body.data;
}

(async () => {
    const bene = await login(env('BENE_EMAIL'), env('BENE_PASSWORD'));
    const admin = await login(env('ADMIN_EMAIL'), env('ADMIN_PASSWORD'));
    const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
    const summary = {};
    let totalViolations = 0, totalCsp = 0, totalInline = 0, totalErrors = 0;
    try {
        // Two tabs: one with the CSP enforced (violations, page errors, inline counts) and one
        // with the CSP bypassed only so axe-core itself can be injected.
        const page = await browser.newPage();
        await page.setViewport({ width: 1280, height: 900 });
        const lax = await browser.newPage();
        await lax.setBypassCSP(true);
        await lax.setViewport({ width: 1280, height: 900 });
        const csp = [], pageErrors = [];
        page.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) csp.push(m.text().slice(0, 160)); });
        page.on('pageerror', (e) => pageErrors.push(e.message.slice(0, 120)));
        await page.evaluateOnNewDocument(() => {
            window.__csp = [];
            document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(e.violatedDirective + ' ' + (e.blockedURI || 'inline') + ' line ' + e.lineNumber));
        });
        const run = async (name, session) => {
            for (const p of [page, lax]) {
                await p.goto(BASE + '/login.html', { waitUntil: 'domcontentloaded' });
                await p.evaluate((s) => { localStorage.clear(); if (s) storeSession(s); }, session);
            }
            csp.length = 0; pageErrors.length = 0;
            await page.goto(BASE + '/' + name, { waitUntil: 'networkidle0' });
            await new Promise((r) => setTimeout(r, 700));
            const cspInPage = await page.evaluate(() => window.__csp || []);
            const errs = [...pageErrors];
            await lax.goto(BASE + '/' + name, { waitUntil: 'networkidle0' });
            await new Promise((r) => setTimeout(r, 500));
            await lax.addScriptTag({ content: axeSource, id: 'axe-audit' });
            const results = await lax.evaluate(async () => {
                const r = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
                return r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, sample: v.nodes[0] && v.nodes[0].html.slice(0, 100) }));
            });
            const inline = await page.evaluate(() => {
                let n = 0;
                for (const el of document.querySelectorAll('*')) for (const a of el.attributes) if (/^on[a-z]+$/i.test(a.name)) n++;
                const inlineScripts = [...document.querySelectorAll('script:not([src]):not(#axe-audit)')].length;
                const jsHrefs = [...document.querySelectorAll('a[href^="javascript:"]')].length;
                return { handlers: n, inlineScripts, jsHrefs };
            });
            const v = results.reduce((a, r) => a + r.nodes, 0);
            totalViolations += v; totalCsp += cspInPage.length + csp.length; totalInline += inline.handlers + inline.inlineScripts + inline.jsHrefs;
            summary[name] = { axeNodes: v, axe: results, csp: [...new Set([...cspInPage, ...csp])].slice(0, 5), inline, pageErrors: errs };
            totalErrors += errs.length;
            const lines = [`${name.padEnd(22)} axe: ${String(v).padStart(3)} nodes/${results.length} rules  csp: ${cspInPage.length + csp.length}  js errors: ${errs.length}  inline: ${inline.handlers}/${inline.inlineScripts}/${inline.jsHrefs}`];
            for (const r of results) lines.push(`    - ${r.id} (${r.impact}) x${r.nodes}: ${String(r.sample).replace(/[\s]+/g, ' ')}`);
            for (const e of errs) lines.push('    ! ' + e);
            for (const c of [...new Set(cspInPage)].slice(0, 3)) lines.push('    csp: ' + c);
            console.log(lines.join('\n'));
        };
        for (const p of PUBLIC) await run(p, null);
        for (const p of BENE) await run(p, bene);
        for (const p of ADMIN) await run(p, admin);
    } finally { await browser.close(); }
    console.log(`\nTOTAL axe violation nodes: ${totalViolations}; CSP violations: ${totalCsp}; inline handlers+scripts+js-hrefs: ${totalInline}`);
    fs.writeFileSync(`a11y-${label}.json`, JSON.stringify({ label, totalViolations, totalCsp, totalErrors, totalInline, pages: summary }, null, 2));
    process.exit(totalViolations || totalCsp || totalErrors || totalInline ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
