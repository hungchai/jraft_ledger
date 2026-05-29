// ─────────────────────────────────────────────────────────────────────────────
// jraft-ledger CORRECTNESS + metrics test  (self-contained, single file)
//
// Goal: prove the ledger foundation is solid under concurrent load by checking
// the invariants a double-entry ledger MUST never break:
//
//   1. Conservation        Σ(all account balances) == 0           (double-entry: every
//                          posting is balanced DEBIT==CREDIT, so a closed pool of
//                          internal transfers always sums to zero. Non-zero => money
//                          created or destroyed.)
//   2. Per-account exact   balance(acc) == Σ(signed journal lines for acc)
//                          (materialized balance store == replay of the journal.
//                          Verified from genesis: every pool account starts at 0.)
//   3. Idempotency         re-POST same requestId => same journalId, applied ONCE.
//   4. Floor enforcement   CLIENT cannot go negative; over-debit => INSUFFICIENT_BALANCE,
//                          balance unchanged.
//   5. Concurrency         hot single account hammered by many VUs still reconciles
//                          (no lost updates in the account-queue serialization).
//
// Design notes:
//   * k6 VUs are isolated JS runtimes — per-VU deltas cannot be shared into
//     teardown(). So we do NOT predict balances from k6 memory. We reconcile the
//     server's own balance store against its own journal (two independent
//     witnesses), plus in-VU behavioural checks for idempotency/floor.
//   * ALL reads (balance, journal) go to the Raft LEADER — the state machine is
//     authoritative and synchronously consistent there; followers/projection lag.
//   * Writes go to the leader with one re-resolve+retry on 503 NOT_LEADER.
//   * Scenarios are sequenced via startTime so each invariant is exercised in a
//     clean window and the final reconciliation is unambiguous.
//
// Run:   k6 run scripts/k6-correctness.js
//   env: NODES, CLIENTS, RUN_TAG, CURRENCY
// ─────────────────────────────────────────────────────────────────────────────

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// ── Config ───────────────────────────────────────────────────────────────────
const NODES = (__ENV.NODES || 'http://localhost:8081,http://localhost:8082,http://localhost:8083').split(',');
const CURRENCY = __ENV.CURRENCY || 'USD';
const BTYPE = 'AVAILABLE_BALANCE';
const VALUE_DATE = __ENV.VALUE_DATE || new Date().toISOString().slice(0, 10);
const N_CLIENTS = parseInt(__ENV.CLIENTS || '20', 10);
const RUN_TAG = __ENV.RUN_TAG || `c${Date.now()}`;        // unique per run, avoids account collisions
const SEED = '1000.00';                                    // starting balance per client
const AMT = '1.00';                                        // unit transfer amount

const FUND = `CT-${RUN_TAG}-FUND`;                          // COMPANY money source (floor unenforced => infinite sink)
const HOT = `CT-${RUN_TAG}-HOT`;                            // CLIENT hot account for concurrency phase
const client = (i) => `CT-${RUN_TAG}-C${String(i).padStart(4, '0')}`;

// ── Custom metrics ───────────────────────────────────────────────────────────
const mLatency = new Trend('posting_latency', true);
const mCompleted = new Counter('postings_completed');
const mRejected = new Counter('postings_rejected');
const mIdemReplays = new Counter('idempotency_replays');
const mIdemViol = new Counter('idempotency_violations');
const mFloorViol = new Counter('floor_violations');

// ── k6 options ───────────────────────────────────────────────────────────────
// teardown reconciliation is O(total journals); keep load BOUNDED so it stays
// exhaustive + fast. Throughput/perf is measured separately (k6-posting-stress.js).
const TX_ITERS = parseInt(__ENV.TX_ITERS || '3000', 10);
const HOT_ITERS = parseInt(__ENV.HOT_ITERS || '2000', 10);

export const options = {
    teardownTimeout: '180s',
    scenarios: {
        // A: random client->client transfers — drives conservation + reconciliation
        transfers: {
            executor: 'shared-iterations', vus: 10, iterations: TX_ITERS, maxDuration: '90s',
            exec: 'internalTransfers', gracefulStop: '2s', startTime: '0s',
        },
        // B: many VUs hammer ONE hot account — concurrency / lost-update check
        hot: {
            executor: 'shared-iterations', vus: 15, iterations: HOT_ITERS, maxDuration: '60s',
            exec: 'hotAccount', gracefulStop: '2s', startTime: '92s',
        },
        // C: replay same requestId — idempotency check (isolated account/window)
        idem: {
            executor: 'per-vu-iterations', vus: 1, iterations: 15, maxDuration: '20s',
            exec: 'idempotency', gracefulStop: '2s', startTime: '155s',
        },
        // D: drain a CLIENT to 0 then over-debit — floor enforcement check
        floor: {
            executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '15s',
            exec: 'floorCheck', gracefulStop: '2s', startTime: '177s',
        },
    },
    thresholds: {
        idempotency_violations: ['count==0'],
        floor_violations: ['count==0'],
        checks: ['rate>0.99'],
        // informational: write-path latency (not the gate — see CLAUDE.md §2.10)
        posting_latency: ['p(95)<300'],
    },
};

// ── HTTP helpers (leader-aware) ──────────────────────────────────────────────
function resolveLeader() {
    for (const n of NODES) {
        const r = http.get(`${n}/health`, { tags: { name: 'health' } });
        try { if (r.status === 200 && r.json('role') === 'LEADER') return n; } catch (_) { /* ignore */ }
    }
    return NODES[0];
}
let leader = null;
function L() { if (!leader) leader = resolveLeader(); return leader; }

function post(path, body, tag) {
    const opts = { headers: { 'Content-Type': 'application/json' }, tags: { name: tag || 'posting' } };
    let res = http.post(`${L()}${path}`, JSON.stringify(body), opts);
    if (res.status === 503) { leader = resolveLeader(); res = http.post(`${L()}${path}`, JSON.stringify(body), opts); }
    return res;
}
function getLeader(path) {
    let res = http.get(`${L()}${path}`, { tags: { name: 'read' } });
    if (res.status === 503) { leader = resolveLeader(); res = http.get(`${L()}${path}`, { tags: { name: 'read' } }); }
    return res;
}

function buildPosting(debit, credit, amount, requestId, ref) {
    return {
        requestId, businessEventType: 'TRANSFER', businessEventRef: ref || `CT-${RUN_TAG}`, valueDate: VALUE_DATE,
        legs: [{
            legId: 'leg-1', postingType: 'TRANSFER', amount, currency: CURRENCY,
            lines: [
                { accountId: debit, balanceType: BTYPE, position: 'CURRENT', entryType: 'DEBIT', description: 'd' },
                { accountId: credit, balanceType: BTYPE, position: 'CURRENT', entryType: 'CREDIT', description: 'c' },
            ],
        }],
    };
}

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

// money as integer cents to avoid float drift
const cents = (x) => Math.round(Number(x) * 100);

function transfer(debit, credit, amount, ref) {
    const res = post('/ledger/postings', buildPosting(debit, credit, amount, uuid(), ref), 'transfer');
    let status = '';
    try { status = res.json('status'); } catch (_) { /* ignore */ }
    mLatency.add(res.timings.duration, { type: 'transfer' });
    if (res.status === 200 && status === 'COMPLETED') mCompleted.add(1); else mRejected.add(1);
    return { res, status };
}

function balanceCents(acc) {
    const r = getLeader(`/ledger/balances?accountId=${acc}&balanceType=${BTYPE}&currency=${CURRENCY}`);
    if (r.status !== 200) return null;
    try { return cents(r.json('amount')); } catch (_) { return null; }
}

// Sum signed journal-line deltas for `acc` across ALL its journals (paginated).
// CREDIT => +amount, DEBIT => -amount. Returns cents.
function journalDeltaCents(acc) {
    let total = 0, page = 0, seen = 0, totalCount = Infinity;
    const size = 1000;
    while (seen < totalCount) {
        const r = getLeader(`/ledger/journals?accountId=${acc}&page=${page}&size=${size}`);
        if (r.status !== 200) break;
        let body;
        try { body = r.json(); } catch (_) { break; }
        totalCount = body.totalCount;
        const journals = body.journals || [];
        if (journals.length === 0) break;
        for (const j of journals) {
            for (const ln of (j.lines || [])) {
                if (ln.accountId === acc && ln.balanceType === BTYPE && ln.currency === CURRENCY) {
                    total += (ln.entryType === 'CREDIT' ? 1 : -1) * cents(ln.amount);
                }
            }
        }
        seen += journals.length;
        page += 1;
        if (page > 10000) break; // safety
    }
    return total;
}

// ── setup: build a bounded closed pool, seed clients from FUND ────────────────
export function setup() {
    leader = resolveLeader();
    console.log(`=== correctness setup: leader=${leader} clients=${N_CLIENTS} tag=${RUN_TAG} ===`);

    const createAcc = (id, type, owner) => post('/ledger/accounts', {
        requestId: `${id}-create`, accountId: id, accountType: type, displayName: id,
        ownerId: owner || id, balanceInitializations: [{ balanceType: BTYPE, currency: CURRENCY }],
    }, 'account-create');

    createAcc(FUND, 'COMPANY');
    createAcc(HOT, 'CLIENT', 'HOTOWNER');
    const clients = [];
    for (let i = 0; i < N_CLIENTS; i++) { const id = client(i); createAcc(id, 'CLIENT', `OWN-${i}`); clients.push(id); }

    // seed each CLIENT + HOT from FUND (FUND goes negative — COMPANY sink). Pool stays Σ==0.
    for (const acc of [HOT, ...clients]) {
        const r = post('/ledger/postings', buildPosting(FUND, acc, SEED, uuid(), `CT-${RUN_TAG}-SEED`), 'seed');
        let st = ''; try { st = r.json('status'); } catch (_) { /* */ }
        if (st !== 'COMPLETED') console.error(`seed FAILED for ${acc}: ${r.status} ${r.body}`);
    }
    console.log(`=== setup complete: seeded ${clients.length + 1} accounts @ ${SEED} ${CURRENCY} ===`);
    return { leader, fund: FUND, hot: HOT, clients, all: [FUND, HOT, ...clients] };
}

// ── A: random client->client transfers ───────────────────────────────────────
export function internalTransfers(data) {
    const c = data.clients;
    const a = c[Math.floor(Math.random() * c.length)];
    let b = c[Math.floor(Math.random() * c.length)];
    if (b === a) b = c[(c.indexOf(a) + 1) % c.length];
    const { res, status } = transfer(a, b, AMT);
    check(res, { 'transfer not 5xx': r => r.status < 500 });
    void status;
}

// ── B: hot account — many VUs, alternating direction ──────────────────────────
export function hotAccount(data) {
    // even iters credit HOT (FUND->HOT), odd iters debit HOT (HOT->FUND)
    if ((__ITER & 1) === 0) transfer(data.fund, data.hot, AMT, `CT-${RUN_TAG}-HOT`);
    else transfer(data.hot, data.fund, AMT, `CT-${RUN_TAG}-HOT`);
}

// ── C: idempotency — replay same requestId, assert applied exactly once ────────
export function idempotency(data) {
    const acc = data.clients[0];
    const rid = `idem-${RUN_TAG}-${__ITER}`;
    const body = buildPosting(data.fund, acc, AMT, rid, `CT-${RUN_TAG}-IDEM`);

    const b0 = balanceCents(acc);
    const r1 = post('/ledger/postings', body, 'idem');
    const r2 = post('/ledger/postings', body, 'idem-replay'); // same requestId
    mIdemReplays.add(1);

    let j1 = '', j2 = '', s1 = '', s2 = '';
    try { j1 = r1.json('journalId'); s1 = r1.json('status'); } catch (_) { /* */ }
    try { j2 = r2.json('journalId'); s2 = r2.json('status'); } catch (_) { /* */ }
    sleep(0.2); // let apply settle before re-read
    const b1 = balanceCents(acc);

    const sameJournal = j1 && j2 && j1 === j2;
    const appliedOnce = (b0 !== null && b1 !== null) && (b1 - b0 === cents(AMT));
    const ok = check(null, {
        'idem both COMPLETED': () => s1 === 'COMPLETED' && s2 === 'COMPLETED',
        'idem same journalId': () => sameJournal,
        'idem applied exactly once': () => appliedOnce,
    });
    if (!ok) {
        mIdemViol.add(1);
        console.error(`IDEM VIOLATION rid=${rid} j1=${j1} j2=${j2} b0=${b0} b1=${b1} expectDelta=${cents(AMT)}`);
    }
}

// ── D: floor — drain a CLIENT to 0 then over-debit ────────────────────────────
export function floorCheck(data) {
    const acc = data.clients[1];
    const bal = balanceCents(acc);
    if (bal === null) { mFloorViol.add(1); console.error('floor: cannot read balance'); return; }

    // drain to exactly 0 (internal transfer back to FUND)
    if (bal > 0) {
        const drain = (bal / 100).toFixed(2);
        transfer(acc, data.fund, drain, `CT-${RUN_TAG}-DRAIN`);
        sleep(0.3);
    }
    const atZero = balanceCents(acc);

    // over-debit by AMT — must be REJECTED INSUFFICIENT_BALANCE, balance unchanged
    const r = post('/ledger/postings', buildPosting(acc, data.fund, AMT, uuid(), `CT-${RUN_TAG}-OVERDRAFT`), 'overdraft');
    let st = '', codes = [];
    try { st = r.json('status'); codes = r.json('errorCodes') || []; } catch (_) { /* */ }
    sleep(0.2);
    const after = balanceCents(acc);

    const ok = check(null, {
        'floor drained to 0': () => atZero === 0,
        'floor over-debit REJECTED': () => st === 'REJECTED',
        'floor INSUFFICIENT_BALANCE': () => codes.indexOf('INSUFFICIENT_BALANCE') >= 0,
        'floor balance unchanged (still 0)': () => after === atZero,
        'floor balance never negative': () => after !== null && after >= 0,
    });
    if (!ok) {
        mFloorViol.add(1);
        console.error(`FLOOR VIOLATION acc=${acc} atZero=${atZero} status=${st} codes=${codes} after=${after}`);
    }
}

// ── teardown: reconcile balance store vs journal + conservation ───────────────
export function teardown(data) {
    leader = data.leader;
    sleep(2); // let any in-flight apply/persist settle

    console.log('\n──────────────── CORRECTNESS RECONCILIATION ────────────────');
    let sum = 0, violations = 0, reconFail = 0;
    const rows = [];
    for (const acc of data.all) {
        const bal = balanceCents(acc);
        const jd = journalDeltaCents(acc);
        if (bal === null) { violations++; rows.push(`  ${acc}  BALANCE READ FAILED`); continue; }
        sum += bal;
        const match = (bal === jd);
        if (!match) reconFail++;
        rows.push(`  ${match ? 'OK ' : 'XX '} ${acc.padEnd(22)} balance=${(bal / 100).toFixed(2).padStart(14)}  journalΣ=${(jd / 100).toFixed(2).padStart(14)}${match ? '' : '  <-- MISMATCH'}`);
    }
    // print only mismatches + a few samples to keep output readable
    const bad = rows.filter(r => r.includes('XX') || r.includes('FAILED'));
    console.log(`accounts checked: ${data.all.length}   per-account reconciliation mismatches: ${reconFail}`);
    if (bad.length) { console.log('MISMATCHES:'); bad.forEach(r => console.log(r)); }
    else console.log('  all accounts reconcile (balance == journal replay)');

    const conserved = (sum === 0);
    console.log(`\nCONSERVATION  Σ(all balances) = ${(sum / 100).toFixed(2)} ${CURRENCY}  =>  ${conserved ? 'OK (zero)' : 'VIOLATION (non-zero!)'}`);

    violations += reconFail + (conserved ? 0 : 1);
    console.log('────────────────────────────────────────────────────────────');
    console.log(`RESULT: ${violations === 0 ? 'PASS — foundation solid' : `FAIL — ${violations} correctness violation(s)`}`);
    console.log('────────────────────────────────────────────────────────────\n');

    if (violations > 0) {
        throw new Error(`CORRECTNESS FAILED: reconMismatch=${reconFail} conserved=${conserved} (Σ=${(sum / 100).toFixed(2)})`);
    }
}
