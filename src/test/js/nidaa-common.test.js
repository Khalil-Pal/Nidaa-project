/*
 * Tests for src/main/resources/static/js/nidaa-common.js.
 * Run with:  node src/test/js/nidaa-common.test.js
 * No framework: the module is plain script, so it is evaluated here with
 * stubbed fetch, localStorage and window.
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vm = require('vm');

const source = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/js/nidaa-common.js'), 'utf8');

function freshContext(fetchImpl) {
    const store = {};
    const ctx = {
        console,
        localStorage: {
            getItem: k => (k in store ? store[k] : null),
            setItem: (k, v) => { store[k] = String(v); },
            removeItem: k => { delete store[k]; }
        },
        window: { location: { href: 'dashboard.html' } },
        document: { getElementById: () => null },
        fetch: fetchImpl,
        JSON, Object, String, Number, Date, Math, Promise, Array, RegExp, Error
    };
    vm.createContext(ctx);
    vm.runInContext(source, ctx);
    ctx.__store = store;
    return ctx;
}

const json = (status, body) => ({
    status, ok: status >= 200 && status < 300, json: async () => body
});

(async () => {
    // escHtml and jsString
    {
        const ctx = freshContext(async () => json(200, {}));
        assert.strictEqual(ctx.escHtml('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
        assert.strictEqual(ctx.escHtml(null), '');
        assert.strictEqual(ctx.jsString("a'); alert(1); ('"), '&quot;a&#039;); alert(1); (&#039;&quot;');
    }

    // apiFetch prefixes the API base but leaves /api paths alone
    {
        const urls = [];
        const ctx = freshContext(async url => { urls.push(url); return json(200, {}); });
        ctx.localStorage.setItem('token', 't1');
        await ctx.apiFetch('/help-requests/my');
        await ctx.apiFetch('help-requests/my');
        await ctx.apiFetch('/api/help-requests/my');
        assert.deepStrictEqual(urls, ['/api/help-requests/my', '/api/help-requests/my', '/api/help-requests/my']);
    }

    // 401 -> refresh once -> retry with the new token
    {
        const calls = [];
        const ctx = freshContext(async (url, opts) => {
            calls.push({ url, auth: opts && opts.headers && opts.headers.Authorization });
            if (url === '/api/auth/refresh') {
                assert.strictEqual(JSON.parse(opts.body).refreshToken, 'r1');
                return json(200, { success: true, data: { token: 't2', refreshToken: 'r2', userId: 7, email: 'a@b.c', fullName: 'A', role: 'beneficiary', isActive: true } });
            }
            return calls.filter(c => c.url === url).length === 1 ? json(401, {}) : json(200, { ok: true });
        });
        ctx.localStorage.setItem('token', 't1');
        ctx.localStorage.setItem('refreshToken', 'r1');
        const res = await ctx.apiFetch('/help-requests/my');
        assert.strictEqual(res.status, 200);
        assert.deepStrictEqual(calls.map(c => c.url), ['/api/help-requests/my', '/api/auth/refresh', '/api/help-requests/my']);
        assert.strictEqual(calls[0].auth, 'Bearer t1');
        assert.strictEqual(calls[2].auth, 'Bearer t2');
        assert.strictEqual(ctx.__store.refreshToken, 'r2', 'rotated refresh token is stored');
        assert.strictEqual(JSON.parse(ctx.__store.user).id, 7);
        assert.strictEqual(ctx.window.location.href, 'dashboard.html', 'no redirect on success');
    }

    // concurrent 401s share one refresh
    {
        let refreshes = 0;
        const ctx = freshContext(async (url) => {
            if (url === '/api/auth/refresh') {
                refreshes++;
                await new Promise(r => setTimeout(r, 10));
                return json(200, { success: true, data: { token: 't2', refreshToken: 'r2' } });
            }
            return ctx.localStorage.getItem('token') === 't2' ? json(200, {}) : json(401, {});
        });
        ctx.localStorage.setItem('token', 't1');
        ctx.localStorage.setItem('refreshToken', 'r1');
        const results = await Promise.all([ctx.apiFetch('/a'), ctx.apiFetch('/b'), ctx.apiFetch('/c')]);
        assert.deepStrictEqual(results.map(r => r.status), [200, 200, 200]);
        assert.strictEqual(refreshes, 1);
    }

    // refresh fails -> session cleared and redirected to login, no infinite retry
    {
        let requests = 0;
        const ctx = freshContext(async (url) => {
            requests++;
            if (url === '/api/auth/refresh') return json(400, { message: 'expired' });
            return json(401, {});
        });
        ctx.localStorage.setItem('token', 't1');
        ctx.localStorage.setItem('refreshToken', 'r1');
        ctx.localStorage.setItem('user', '{}');
        const res = await ctx.apiFetch('/help-requests/my');
        assert.strictEqual(res.status, 401);
        assert.strictEqual(requests, 2);
        assert.strictEqual(ctx.__store.token, undefined);
        assert.strictEqual(ctx.__store.refreshToken, undefined);
        assert.strictEqual(ctx.window.location.href, 'login.html');
    }

    // logout revokes the refresh token server-side, then clears the session
    {
        const calls = [];
        const ctx = freshContext(async (url, opts) => { calls.push({ url, body: opts && opts.body }); return json(200, {}); });
        ctx.localStorage.setItem('token', 't1');
        ctx.localStorage.setItem('refreshToken', 'r1');
        await ctx.logout();
        assert.strictEqual(calls[0].url, '/api/auth/logout');
        assert.strictEqual(JSON.parse(calls[0].body).refreshToken, 'r1');
        assert.strictEqual(ctx.__store.token, undefined);
        assert.strictEqual(ctx.window.location.href, 'login.html');
    }

    // buildSidebar renders the role's links and marks the current page
    {
        let html = '';
        const ctx = freshContext(async () => json(200, {}));
        ctx.document = { getElementById: id => id === 'sidebarNav' ? { set innerHTML(v) { html = v; } } : null };
        ctx.localStorage.setItem('user', JSON.stringify({ role: 'ADMIN' }));
        ctx.buildSidebar('admin.html');
        assert.ok(html.includes('href="community.html"'), 'admin sees Community');
        assert.ok(html.includes('class="nav-item active"><i class="fa fa-chart-bar"></i> Analytics'), 'current page is active');
        assert.strictEqual((html.match(/nav-item/g) || []).length, 7);
    }

    console.log('nidaa-common.test.js: all assertions passed');
})().catch(err => { console.error(err); process.exit(1); });
