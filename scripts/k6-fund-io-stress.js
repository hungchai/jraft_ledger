import http from 'k6/http';
import { check, sleep } from 'k6';
import { htmlReport } from 'https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.3/index.js';

// Stress Test: Random Fund In/Out (Deposit + Withdraw)
// Maps to docs/STRESS-TEST-PLAN.md Phase 2 alternative
// Two-sided random postings between the EXCHANGE and CLIENTS:
//   - 50% FUND IN  : EXCHANGE (debit) → CLIENT (credit)
//   - 50% FUND OUT : CLIENT (debit)   → EXCHANGE (credit)
//
// Setup: exchange seeded from SYSTEM_SEED, then each client seeded from
// the exchange. So at t=0:  exchange == EX_SEED - N*CLI_SEED  (per currency)
//   and  exchange + Σ clients == EX_SEED  (conservation).
//
// Per iteration:
//   - pick a random client, random currency (USDT/BTC), random amount in [MIN,MAX]
//
// Invariant under projection catch-up:
//   EXCHANGE_AVAILABLE + Σ CLIENT_AVAILABLE == EX_SEED  (constant, no money leak)
//
// Usage:
//   k6 run --vus 200 --duration 120s scripts/k6-fund-io-stress.js
//
// Env overrides:
//   BASE_URL         explicit leader URL
//   NUM_CLIENTS      number of client accounts to create (default 500)
//   DEPOSIT_RATIO    fraction of iterations that are deposits (default 0.5)
//   AMOUNT_MIN_USDT  min deposit/withdraw USDT (default 1.0)
//   AMOUNT_MAX_USDT  max deposit/withdraw USDT (default 1000.0)
//   AMOUNT_MIN_BTC   min deposit/withdraw BTC  (default 0.0001)
//   AMOUNT_MAX_BTC   max deposit/withdraw BTC  (default 1.0)

export function handleSummary(data) {
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    'stdout': textSummary(data, { indent: '  ', enableColors: true }),
    [`k6-report-fund-io-${ts}.html`]: htmlReport(data),
  };
}

export const options = {
  stages: [
    { duration: '30s', target: 100 },    // warm-up
    { duration: '60s', target: 500 },    // peak fund-in/out load
    { duration: '30s', target: 0 },      // cool-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<80', 'p(99)<200'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

// ---------------------- Config ----------------------
const HOTSPOT_ACC = 'FUNDIO-EX-CO-001';                  // exchange (COMPANY) counterparty
const CLIENT_PREFIX = 'FUNDIO-CLI-';                     // 500 clients
const SYSTEM_SEED = 'SYSTEM_SEED';                       // external funding source

const NUM_CLIENTS = parseInt(__ENV.NUM_CLIENTS || '500', 10);
const DEPOSIT_RATIO = parseFloat(__ENV.DEPOSIT_RATIO || '0.5');

const AMOUNT_MIN_USDT = parseFloat(__ENV.AMOUNT_MIN_USDT || '1.0');
const AMOUNT_MAX_USDT = parseFloat(__ENV.AMOUNT_MAX_USDT || '1000.0');
const AMOUNT_MIN_BTC  = parseFloat(__ENV.AMOUNT_MIN_BTC  || '0.0001');
const AMOUNT_MAX_BTC  = parseFloat(__ENV.AMOUNT_MAX_BTC  || '1.0');

// Precision: BTC 8dp, USDT 16dp
const USDT_DP = 16;
const BTC_DP  = 8;

// Per-client initial seed (so withdraw can be exercised immediately)
const SEED_USDT_PER_CLIENT = '100000.0000000000000000'; // 100k USDT
const SEED_BTC_PER_CLIENT  = '1.00000000';              // 1 BTC

// Exchange needs a giant pool to absorb withdrawals
function pad16(n) {
  const s = String(n);
  return s.includes('.')
    ? s.split('.')[0] + '.' + (s.split('.')[1] + '0'.repeat(USDT_DP)).slice(0, USDT_DP)
    : s + '.' + '0'.repeat(USDT_DP);
}
function pad8(n) {
  const s = String(n);
  return s.includes('.')
    ? s.split('.')[0] + '.' + (s.split('.')[1] + '0'.repeat(BTC_DP)).slice(0, BTC_DP)
    : s + '.' + '0'.repeat(BTC_DP);
}
const SEED_EX_USDT_FIXED = pad16(NUM_CLIENTS * 1_000_000); // 1M USDT per client
const SEED_EX_BTC_FIXED  = pad8(NUM_CLIENTS * 1000);      // 1000 BTC per client

const LEADER_PORTS = [8081, 8082, 8083];
const MAX_RETRIES = 3;
const RETRY_BACKOFF_MS = [100, 200, 400];

// ---------------------- Leader discovery ----------------------
let leaderUrl = __ENV.BASE_URL || null;
let leaderUrlTimestamp = 0;
const LEADER_TTL_MS = 10_000;

function findLeader() {
  for (const port of LEADER_PORTS) {
    const url = `http://localhost:${port}`;
    try {
      const res = http.get(`${url}/health`, { timeout: '2s' });
      if (res.status === 200) {
        const body = JSON.parse(res.body);
        if (body.role === 'LEADER') {
          console.log(`Leader found: ${url} (term ${body.term})`);
          return url;
        }
      }
    } catch (e) { /* try next */ }
  }
  console.log('No leader found, falling back to http://localhost:8081');
  return 'http://localhost:8081';
}

function getLeaderUrl() {
  const now = Date.now();
  if (!leaderUrl || (now - leaderUrlTimestamp > LEADER_TTL_MS)) {
    leaderUrl = findLeader();
    leaderUrlTimestamp = now;
  }
  return leaderUrl;
}

function refreshLeader() {
  const old = leaderUrl;
  leaderUrl = findLeader();
  leaderUrlTimestamp = Date.now();
  if (old && old !== leaderUrl) {
    console.log(`Leader changed: ${old} → ${leaderUrl}`);
  }
  return leaderUrl;
}

// ---------------------- Setup helpers ----------------------
let setupFailures = 0;

function setupPost(path, payload, label) {
  const res = http.post(`${getLeaderUrl()}${path}`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '30s',
  });
  const ok = check(res, {
    [`${label}: status 200`]: (r) => r.status === 200,
    [`${label}: COMPLETED`]: (r) => r.json('status') === 'COMPLETED',
  });
  if (!ok) {
    setupFailures++;
    console.error(`SETUP FAIL: ${label} — status=${res.status} body=${res.body.substring(0, 200)}`);
  }
  return ok;
}

function setupCheckDie(ok) {
  if (!ok) {
    console.error(`FATAL: ${setupFailures} setup step(s) failed. Aborting test.`);
    throw new Error(`Setup failed: ${setupFailures} step(s) failed`);
  }
}

export function setup() {
  console.log(`=== SETUP: Fund In/Out (${NUM_CLIENTS} clients, USDT 16dp + BTC 8dp) ===`);

  // 1. Create exchange account (COMPANY)
  const ok1 = setupPost('/ledger/accounts', JSON.stringify({
    requestId: `init-ex-${Date.now()}`,
    accountId: HOTSPOT_ACC,
    accountType: 'COMPANY',
    displayName: 'FundIO Exchange Co',
    ownerId: 'CO-FUNDIO-EX',
    balanceInitializations: [
      { balanceType: 'AVAILABLE_BALANCE', currency: 'USDT' },
      { balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'  },
    ],
  }), 'create-exchange');
  setupCheckDie(ok1);

  // 2. Seed exchange from SYSTEM_SEED (big pool)
  const ok2 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-ex-usdt-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-EX-USDT',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: SEED_EX_USDT_FIXED,
      currency: 'USDT',
      lines: [
        { accountId: SYSTEM_SEED, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT' },
        { accountId: HOTSPOT_ACC,  balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT' },
      ],
    }],
  }), 'seed-exchange-USDT');

  const ok3 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-ex-btc-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-EX-BTC',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: SEED_EX_BTC_FIXED,
      currency: 'BTC',
      lines: [
        { accountId: SYSTEM_SEED, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT' },
        { accountId: HOTSPOT_ACC,  balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT' },
      ],
    }],
  }), 'seed-exchange-BTC');
  setupCheckDie(ok2 && ok3);

  // 3. Create + seed N client accounts
  // Each client seeded from EXCHANGE (so conservation holds: EXCHANGE debit, CLIENT credit).
  // Withdrawal will reverse direction: CLIENT debit, EXCHANGE credit.
  let bad = 0;
  for (let i = 1; i <= NUM_CLIENTS; i++) {
    const clientAcc = `${CLIENT_PREFIX}${String(i).padStart(4, '0')}`;

    const okA = setupPost('/ledger/accounts', JSON.stringify({
      requestId: `init-cli-${i}-${Date.now()}`,
      accountId: clientAcc,
      accountType: 'CLIENT',
      displayName: `FundIO Client ${i}`,
      ownerId: `CUST-FUNDIO-${i}`,
      balanceInitializations: [
        { balanceType: 'AVAILABLE_BALANCE', currency: 'USDT' },
        { balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'  },
      ],
    }), `create-cli-${i}`);

    const okB = setupPost('/ledger/postings', JSON.stringify({
      requestId: `seed-cli-${i}-${Date.now()}`,
      businessEventType: 'DEPOSIT',
      businessEventRef: `SEED-CLI-${i}`,
      valueDate: '2026-05-27',
      legs: [
        {
          legId: 'leg-1',
          postingType: 'DEPOSIT',
          amount: SEED_USDT_PER_CLIENT,
          currency: 'USDT',
          lines: [
            { accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT' },
            { accountId: clientAcc,   balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT' },
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'DEPOSIT',
          amount: SEED_BTC_PER_CLIENT,
          currency: 'BTC',
          lines: [
            { accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT' },
            { accountId: clientAcc,   balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT' },
          ],
        },
      ],
    }), `seed-cli-${i}`);

    if (!okA || !okB) bad++;
    if (bad > 5) {
      console.error(`FATAL: ${setupFailures} client setup failures. Aborting.`);
      throw new Error('Client setup failed');
    }
  }

  if (setupFailures > 0) {
    console.warn(`SETUP WARNING: ${setupFailures} step(s) failed but continuing`);
  } else {
    console.log(`Account initialization complete — ${NUM_CLIENTS} clients, 0 failures`);
  }
  return { initialized: true, numClients: NUM_CLIENTS };
}

// ---------------------- Random helpers ----------------------
function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}
function randFloat(min, max, dp) {
  const v = Math.random() * (max - min) + min;
  return v.toFixed(dp);
}

// ---------------------- Main test: random fund in/out ----------------------
export default function () {
  const vu = __VU;
  const iter = __ITER;
  const clientIdx = randInt(1, NUM_CLIENTS);
  const clientAcc = `${CLIENT_PREFIX}${String(clientIdx).padStart(4, '0')}`;
  const isDeposit = Math.random() < DEPOSIT_RATIO;
  const pickUsdt  = Math.random() < 0.5;
  const currency  = pickUsdt ? 'USDT' : 'BTC';
  const dp        = pickUsdt ? USDT_DP : BTC_DP;
  const min       = pickUsdt ? AMOUNT_MIN_USDT : AMOUNT_MIN_BTC;
  const max       = pickUsdt ? AMOUNT_MAX_USDT : AMOUNT_MAX_BTC;
  const amount    = randFloat(min, max, dp);

  const reqId = `fundio-${vu}-${iter}-${Date.now()}`;
  const eventType = isDeposit ? 'FUND_IN' : 'FUND_OUT';
  const refId     = isDeposit ? `IN-${vu}-${iter}` : `OUT-${vu}-${iter}`;
  const postingType = isDeposit ? 'DEPOSIT' : 'WITHDRAWAL';

  let payload;
  if (isDeposit) {
    // FUND IN: EXCHANGE debit → CLIENT credit
    payload = JSON.stringify({
      requestId: reqId,
      businessEventType: eventType,
      businessEventRef: refId,
      valueDate: '2026-05-27',
      legs: [{
        legId: 'leg-1',
        postingType: postingType,
        amount: amount,
        currency: currency,
        lines: [
          { accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT',  description: 'Fund-in from exchange' },
          { accountId: clientAcc,   balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'Fund-in to client' },
        ],
      }],
    });
  } else {
    // FUND OUT: CLIENT debit → EXCHANGE credit
    payload = JSON.stringify({
      requestId: reqId,
      businessEventType: eventType,
      businessEventRef: refId,
      valueDate: '2026-05-27',
      legs: [{
        legId: 'leg-1',
        postingType: postingType,
        amount: amount,
        currency: currency,
        lines: [
          { accountId: clientAcc,   balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT',  description: 'Fund-out from client' },
          { accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'Fund-out to exchange' },
        ],
      }],
    });
  }

  // Retry with backoff
  let res = null;
  let lastStatus = 0;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    if (attempt > 0) {
      sleep(RETRY_BACKOFF_MS[attempt - 1] / 1000);
      if (lastStatus === 0 || lastStatus === 504) {
        refreshLeader();
      }
    }
    res = http.post(`${getLeaderUrl()}/ledger/postings`, payload, {
      headers: { 'Content-Type': 'application/json' },
      timeout: '10s',
    });
    lastStatus = res.status;
    if (res.status === 200 || res.status === 400 || res.status === 404 || res.status === 409) break;
    if (res.status !== 429 && res.status !== 503 && res.status !== 504 && res.status !== 0) break;
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'outcome COMPLETED': (r) => r.json('status') === 'COMPLETED',
  });
  sleep(0.02);
}
