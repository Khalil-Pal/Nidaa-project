/**
 * PF-1 — API latency and throughput measurement.
 *
 * Measures four endpoints at three concurrency levels and reports p50, p95, p99,
 * the mean, throughput and errors for each. Measurement, not optimisation: it
 * changes nothing and reports what it finds.
 *
 *   node scripts/perf/load-test.js --out perf-run.json [--label before]
 *
 * Environment: BASE (default http://127.0.0.1:8081), and the accounts to act as —
 * ADMIN_EMAIL/ADMIN_PASSWORD, BENE_EMAIL/BENE_PASSWORD, VOL_EMAIL/VOL_PASSWORD.
 * scripts/perf/seed-requests.js creates the accounts and the 10,000 requests the
 * run measures against.
 *
 * The application must run with RATELIMIT_ENABLED=false: the limiter allows 100
 * API and 5 auth calls per minute per client, so with it on this measures the
 * limiter rather than the platform. What the limiter itself does is measured by
 * the S-9 acceptance check.
 *
 * Node 18+ (global fetch). No dependencies: it must run wherever the jar runs.
 */
'use strict';

const fs = require('fs');

const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const CONCURRENCIES = (process.env.CONCURRENCIES || '1,10,50').split(',').map(Number);
const SECONDS = Number(process.env.SECONDS || 10);        // measured seconds per level
const WARMUP = Number(process.env.WARMUP || 3);           // discarded seconds per level

const args = process.argv.slice(2);
const opt = (name, fallback) => {
    const i = args.indexOf('--' + name);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const OUT = opt('out', 'perf-run.json');
const LABEL = opt('label', 'run');

const env = (name) => {
    const v = process.env[name];
    if (!v) throw new Error(`${name} is not set`);
    return v;
};

async function call(path, init = {}) {
    const started = process.hrtime.bigint();
    let status = 0;
    let body = null;
    try {
        const res = await fetch(BASE + path, init);
        status = res.status;
        body = await res.text();
    } catch (e) {
        status = -1;
        body = String(e && e.message);
    }
    return { ms: Number(process.hrtime.bigint() - started) / 1e6, status, body };
}

async function login(email, password) {
    const r = await call('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
    });
    if (r.status !== 200) throw new Error(`login ${email} -> ${r.status} ${r.body}`);
    return JSON.parse(r.body).data.token;
}

/** The nearest-rank percentile of a sorted sample, the same definition the EV-1 study uses. */
function percentile(sorted, p) {
    if (!sorted.length) return 0;
    const rank = Math.ceil(p * sorted.length);
    return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
}

function summarise(samples, elapsedSeconds, errors) {
    const sorted = [...samples].sort((a, b) => a - b);
    const mean = samples.reduce((a, b) => a + b, 0) / (samples.length || 1);
    return {
        requests: samples.length,
        errors,
        seconds: Number(elapsedSeconds.toFixed(2)),
        throughputPerSecond: Number((samples.length / elapsedSeconds).toFixed(1)),
        meanMs: Number(mean.toFixed(1)),
        p50Ms: Number(percentile(sorted, 0.5).toFixed(1)),
        p95Ms: Number(percentile(sorted, 0.95).toFixed(1)),
        p99Ms: Number(percentile(sorted, 0.99).toFixed(1)),
        maxMs: Number((sorted[sorted.length - 1] || 0).toFixed(1))
    };
}

/**
 * Runs `concurrency` workers in a closed loop for the warm-up plus the measured
 * window: every worker issues one request, waits for it and issues the next, so
 * the offered load is the concurrency and the samples are per-request latencies.
 */
async function measure(name, concurrency, request) {
    const warmUntil = Date.now() + WARMUP * 1000;
    const until = warmUntil + SECONDS * 1000;
    const samples = [];
    let errors = 0;
    let measuringFrom = null;

    const worker = async () => {
        while (Date.now() < until) {
            const r = await request();
            const measuring = Date.now() > warmUntil;
            if (measuring) {
                if (measuringFrom === null) measuringFrom = Date.now();
                if (r.status >= 200 && r.status < 300) samples.push(r.ms);
                else errors++;
            }
        }
    };
    await Promise.all(Array.from({ length: concurrency }, worker));
    const elapsed = (Date.now() - (measuringFrom || warmUntil)) / 1000;
    const summary = summarise(samples, elapsed || SECONDS, errors);
    console.log(`  ${String(concurrency).padStart(2)} concurrent  ${String(summary.requests).padStart(6)} req  `
        + `p50 ${String(summary.p50Ms).padStart(7)} ms  p95 ${String(summary.p95Ms).padStart(7)} ms  `
        + `p99 ${String(summary.p99Ms).padStart(7)} ms  ${String(summary.throughputPerSecond).padStart(7)} req/s`
        + (errors ? `  ERRORS ${errors}` : ''));
    return { concurrency, ...summary };
}

(async () => {
    const adminToken = await login(env('ADMIN_EMAIL'), env('ADMIN_PASSWORD'));
    const beneToken = await login(env('BENE_EMAIL'), env('BENE_PASSWORD'));
    await login(env('VOL_EMAIL'), env('VOL_PASSWORD'));   // fails early if the account is wrong

    const auth = (token) => ({ Authorization: 'Bearer ' + token });
    const jsonAuth = (token) => ({ 'Content-Type': 'application/json', ...auth(token) });

    const guard = await call('/api/dashboard/public-stats');
    const limiter = await call('/api/dashboard/public-stats');
    if (guard.status !== 200 || limiter.status === 429) {
        throw new Error('the application is not answering, or the rate limiter is on (RATELIMIT_ENABLED=false)');
    }
    const total = JSON.parse((await call('/api/admin/stats', { headers: auth(adminToken) })).body);
    console.log(`${LABEL}: ${BASE}, ${WARMUP}s warm-up + ${SECONDS}s measured per level`);
    console.log(`dataset: ${JSON.stringify(total.data)}\n`);

    const scenarios = [
        {
            name: 'login',
            what: 'POST /api/auth/login — bcrypt verification and token issue',
            request: () => call('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: env('BENE_EMAIL'), password: env('BENE_PASSWORD') })
            })
        },
        {
            name: 'submit-request',
            what: 'POST /api/help-requests — validation, priority score, insert, matching',
            request: () => call('/api/help-requests', {
                method: 'POST',
                headers: jsonAuth(beneToken),
                body: JSON.stringify({
                    title: 'Load test request',
                    description: 'Created by scripts/perf/load-test.js',
                    helpType: 'FOOD',
                    urgencyLevel: 'MEDIUM',
                    peopleCount: 3,
                    hasChildren: true,
                    latitude: 55.6 + Math.random() * 0.3,
                    longitude: 37.4 + Math.random() * 0.3
                })
            })
        },
        {
            name: 'ranked-queue',
            what: 'GET /api/v1/admin/dashboard/ranked?size=20 — the ranked page with provider suggestions',
            request: () => call('/api/v1/admin/dashboard/ranked?page=0&size=20', { headers: auth(adminToken) })
        },
        {
            name: 'admin-dashboard',
            what: 'GET /api/admin/stats — the aggregate counters behind admin.html',
            request: () => call('/api/admin/stats', { headers: auth(adminToken) })
        }
    ];

    const results = [];
    for (const scenario of scenarios) {
        console.log(`${scenario.name} — ${scenario.what}`);
        const levels = [];
        for (const concurrency of CONCURRENCIES) {
            levels.push(await measure(scenario.name, concurrency, scenario.request));
        }
        results.push({ name: scenario.name, what: scenario.what, levels });
        console.log('');
    }

    const report = {
        label: LABEL,
        base: BASE,
        takenAt: new Date().toISOString(),
        warmupSeconds: WARMUP,
        measuredSeconds: SECONDS,
        dataset: total.data,
        scenarios: results
    };
    fs.writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n');
    console.log('written ' + OUT);
})().catch((e) => { console.error(e); process.exit(1); });
