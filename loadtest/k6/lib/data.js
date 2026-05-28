// Shared helpers for jraft-ledger k6 scenarios.
//
// jraft-ledger writes must go to the Raft leader. Followers respond 503
// NOT_LEADER. Each VU caches a leader URL and re-resolves on 503.
//
// Account pool is resolved once at start-of-test and shared via SharedArray.
// Accounts are roled: `master` (COMPANY hotspot DEBIT side), `sub` (CLIENT
// CREDIT side), and `suspense` (SUSPENSE, used by deposit/withdrawal flows).

import { check } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';

// Comma-separated list of node base URLs. Reads can hit any node; writes
// resolve to the leader first.
export const NODES = (__ENV.NODES || 'http://localhost:8081,http://localhost:8082,http://localhost:8083').split(',');

// Default endpoint for non-leader-aware GETs (balance reads).
export const ENDPOINT = __ENV.LEDGER_ENDPOINT || NODES[0];

export const CURRENCY = __ENV.CURRENCY || 'USD';
export const VALUE_DATE = __ENV.VALUE_DATE || new Date().toISOString().slice(0, 10);

export const accounts = new SharedArray('accounts', () => {
    const raw = open('../../../target/loadtest-accounts.json');
    return JSON.parse(raw);
});

// Per-VU cached leader URL.
let leaderUrl = null;

export function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

export function resolveLeader() {
    for (const n of NODES) {
        const r = http.get(`${n}/health`, { tags: { name: 'health' } });
        try {
            if (r.status === 200 && r.json('role') === 'LEADER') return n;
        } catch (_) { /* ignore */ }
    }
    return null;
}

// Pre-filter once at module load. accounts.filter() is O(n) and was being
// called on every iteration — 2001 items × 2 calls × thousands of iters/sec
// = k6 CPU bottleneck masquerading as server throughput cap.
const _masters  = accounts.filter(a => a.role === 'master');
const _subs     = accounts.filter(a => a.role === 'sub');
const _suspense = accounts.find(a => a.role === 'suspense');

export function pickMaster() { return _masters[Math.floor(Math.random() * _masters.length)]; }
export function pickSub()    { return _subs[Math.floor(Math.random() * _subs.length)]; }
export function pickSuspense() { return _suspense; }

// Pick two distinct accounts. Returns [from, to] where from is a master
// (COMPANY hotspot DEBIT) and to is a sub (CLIENT CREDIT). This matches the
// pattern in scripts/k6-posting-stress.leader.js: COMPANY auto-tops up, so
// the DEBIT side cannot trip INSUFFICIENT_BALANCE.
export function pickPair() {
    return [pickMaster(), pickSub()];
}

// Build a /ledger/postings request body matching PostingController's expected
// shape: { requestId, businessEventType, businessEventRef, valueDate, legs }.
// Each leg carries amount/currency + lines (DEBIT/CREDIT). Lines do NOT carry
// amount — PostingController reads it at leg level (see
// docs/LOAD-TEST-RESULTS-2026-05-27.md §"Test-script defects found & fixed").
export function buildPosting(opts) {
    const {
        debitAccountId,
        creditAccountId,
        amount = '1',
        currency = CURRENCY,
        businessEventType = 'TRANSFER',
        businessEventRef = 'k6',
        requestId = uuid(),
        balanceType = 'AVAILABLE_BALANCE',
    } = opts;
    return {
        requestId,
        businessEventType,
        businessEventRef,
        valueDate: VALUE_DATE,
        legs: [{
            legId: 'leg-1',
            postingType: businessEventType,
            amount,
            currency,
            lines: [
                { accountId: debitAccountId,  balanceType, position: 'CURRENT', entryType: 'DEBIT',  description: `${businessEventType} debit` },
                { accountId: creditAccountId, balanceType, position: 'CURRENT', entryType: 'CREDIT', description: `${businessEventType} credit` },
            ],
        }],
    };
}

// Leader-aware POST with one re-resolve + retry on 503 NOT_LEADER.
export function postLeader(path, body, tagName = 'posting') {
    if (!leaderUrl) leaderUrl = resolveLeader() || NODES[0];
    const opts = { headers: { 'Content-Type': 'application/json' }, tags: { name: tagName } };
    let res = http.post(`${leaderUrl}${path}`, JSON.stringify(body), opts);
    if (res.status === 503) {
        leaderUrl = resolveLeader() || leaderUrl;
        res = http.post(`${leaderUrl}${path}`, JSON.stringify(body), opts);
    }
    return res;
}

// Non-leader-aware GET. Reads can hit any node — Learner / projection path.
export function getJson(path) {
    return http.get(`${ENDPOINT}${path}`);
}

export function expect2xx(res, name) {
    return check(res, {
        [`${name} 2xx`]: r => r.status >= 200 && r.status < 300,
    });
}

export function expectCompleted(res, name) {
    return check(res, {
        [`${name} 2xx`]: r => r.status >= 200 && r.status < 300,
        [`${name} COMPLETED`]: r => {
            try { return r.json('status') === 'COMPLETED'; } catch (_) { return false; }
        },
    });
}
