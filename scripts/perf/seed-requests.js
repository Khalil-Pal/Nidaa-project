/**
 * PF-1 — the dataset the load test measures against: 10,000 help requests created
 * through the API, so every priority score is the application's own (post-V15
 * model), not one written straight into the table.
 *
 *   node scripts/perf/seed-requests.js [--count 10000] [--concurrency 32]
 *
 * Environment: BASE, DB, PGUSER, PSQL (the verification code and the first
 * administrator come from the database, as they do for the gate scripts), and
 * optionally SEED. It creates its own accounts — perf-admin, perf-bene, perf-vol
 * — and prints the environment lines load-test.js needs.
 *
 * Run it against a database built by scripts/gate/fresh-db.sh, with the
 * application started with RATELIMIT_ENABLED=false. Requests are spread over the
 * three settlements of the EV-1 study with the same urgency weighting, from a
 * fixed seed, so two seeded databases hold the same queue.
 *
 * Node 18+ (global fetch), no dependencies.
 */
'use strict';

const { execFileSync } = require('child_process');

const BASE = process.env.BASE || 'http://127.0.0.1:8081';
const DB = process.env.DB || 'nidaa_perf';
const PGUSER = process.env.PGUSER || 'postgres';
const PSQL = process.env.PSQL || 'psql';
const PW = 'Perf-Run-2026!';
const ADMIN_PW = 'Ut-Admin-2026';   // same throwaway administrator as the UT-1 setup script

const args = process.argv.slice(2);
const opt = (name, fallback) => {
    const i = args.indexOf('--' + name);
    return i >= 0 && args[i + 1] ? Number(args[i + 1]) : fallback;
};
const COUNT = opt('count', 10000);
const CONCURRENCY = opt('concurrency', 32);

const sql = (q) => execFileSync(PSQL, ['-U', PGUSER, '-d', DB, '-v', 'ON_ERROR_STOP=1', '-tAc', q],
    { encoding: 'utf8' }).replace(/\r/g, '').trim();

async function api(path, init = {}) {
    const res = await fetch(BASE + path, init);
    let body = null;
    try { body = await res.json(); } catch (e) { /* no body */ }
    return { status: res.status, body };
}
const json = (token) => ({ 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) });

async function login(email, password) {
    const r = await api('/api/auth/login', { method: 'POST', headers: json(), body: JSON.stringify({ email, password }) });
    if (r.status !== 200) throw new Error(`login ${email} -> ${r.status} ${JSON.stringify(r.body)}`);
    return r.body.data.token;
}

async function register(email, role, name) {
    await api('/api/auth/register', {
        method: 'POST', headers: json(),
        body: JSON.stringify({ fullName: name, email, password: PW, phone: '+1' + Math.floor(Math.random() * 1e9), role })
    });
    await api('/api/auth/register/verify', {
        method: 'POST', headers: json(),
        body: JSON.stringify({ email, code: sql(`select code from pending_registrations where email='${email}'`) })
    });
}

/** mulberry32: a small seeded generator, so the same seed gives the same queue. */
function rng(seed) {
    let a = seed >>> 0;
    return () => {
        a |= 0; a = (a + 0x6D2B79F5) | 0;
        let t = Math.imul(a ^ (a >>> 15), 1 | a);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

const CLUSTERS = [
    { name: 'Central', lat: 55.60, lon: 37.40, share: 0.5 },
    { name: 'North', lat: 56.30, lon: 37.60, share: 0.3 },
    { name: 'East', lat: 55.70, lon: 38.40, share: 0.2 }
];
const TYPES = ['MEDICAL', 'FOOD', 'SHELTER', 'WATER', 'CLOTHING'];
const URGENCY = [['CRITICAL', 10], ['HIGH', 25], ['MEDIUM', 40], ['LOW', 25]];

function weighted(random, pairs) {
    const total = pairs.reduce((a, [, w]) => a + w, 0);
    let point = random() * total;
    for (const [value, w] of pairs) {
        point -= w;
        if (point < 0) return value;
    }
    return pairs[pairs.length - 1][0];
}

(async () => {
    if (sql('select count(*) from users') !== '0') {
        console.error(`refusing to run: ${DB} already has users. Rebuild it first:\n  scripts/gate/fresh-db.sh ${DB}`);
        process.exit(1);
    }
    sql(`INSERT INTO users (email,password_hash,full_name,role,is_verified,is_active,is_locked)
         VALUES ('perf-admin@example.test','$2a$10$5FwMYk/oQyzCIs1YU2VIoeTXqQ9Du/3PFMqb5GB01/cKkKFSFpa0.',
                 'Perf Admin','ADMIN',true,true,false)`);
    const adminToken = await login('perf-admin@example.test', ADMIN_PW);

    await register('perf-bene@example.test', 'beneficiary', 'Perf Beneficiary');
    await register('perf-vol@example.test', 'volunteer', 'Perf Volunteer');
    await api(`/api/admin/approve/${sql("select user_id from users where email='perf-vol@example.test'")}`,
        { method: 'PUT', headers: json(adminToken) });
    const beneToken = await login('perf-bene@example.test', PW);

    const random = rng(Number(process.env.SEED || 20260917));
    const requests = Array.from({ length: COUNT }, (unused, i) => {
        let point = random();
        const cluster = CLUSTERS.find((c) => (point -= c.share) < 0) || CLUSTERS[CLUSTERS.length - 1];
        return {
            title: `Perf request #${i}`,
            description: 'Created by scripts/perf/seed-requests.js',
            helpType: TYPES[Math.floor(random() * TYPES.length)],
            urgencyLevel: weighted(random, URGENCY),
            peopleCount: 1 + Math.floor(random() * 10),
            hasChildren: random() < 0.30,
            hasElderly: random() < 0.20,
            hasDisabled: random() < 0.15,
            latitude: cluster.lat + (random() - 0.5) * 0.3,
            longitude: cluster.lon + (random() - 0.5) * 0.3
        };
    });

    const started = Date.now();
    let next = 0;
    let done = 0;
    let failed = 0;
    const worker = async () => {
        while (next < requests.length) {
            const body = requests[next++];
            const r = await api('/api/help-requests', { method: 'POST', headers: json(beneToken), body: JSON.stringify(body) });
            if (r.status !== 200 && r.status !== 201) {
                failed++;
                if (failed <= 3) console.error(`  create -> ${r.status} ${JSON.stringify(r.body)}`);
            }
            if (++done % 1000 === 0) console.log(`  ${done}/${requests.length}`);
        }
    };
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    const seconds = (Date.now() - started) / 1000;

    const rows = sql('select count(*) from help_requests');
    const scores = sql("select min(priority_score)||'-'||max(priority_score) from help_requests");
    console.log(`\n${rows} help requests in ${seconds.toFixed(1)} s (${(COUNT / seconds).toFixed(0)}/s), `
        + `${failed} failed; priority scores ${scores}`);
    console.log(`
Environment for the load test:

  export BASE=${BASE}
  export ADMIN_EMAIL=perf-admin@example.test ADMIN_PASSWORD=${ADMIN_PW}
  export BENE_EMAIL=perf-bene@example.test   BENE_PASSWORD='${PW}'
  export VOL_EMAIL=perf-vol@example.test     VOL_PASSWORD='${PW}'
`);
})().catch((e) => { console.error(e); process.exit(1); });
