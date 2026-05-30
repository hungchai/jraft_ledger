import http from 'k6/http';
import { check, sleep } from 'k6';

// Stress Test: RFQ Hotspot — maps to docs/STRESS-TEST-PLAN.md Phase 2
// RFQ BUY/SELL scenarios with BTC/USD pair
// BUY: Client buys USD, sells BTC
// SELL: Client sells USD, buys BTC
// Usage: k6 run --vus 1000 --duration 120s scripts/k6-posting-stress.js

export const options = {
  stages: [
    { duration: '30s', target: 100 },   // warm-up
    { duration: '60s', target: 1000 },  // peak RFQ load
    { duration: '30s', target: 0 },     // cool-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<50', 'p(99)<100'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

const HOTSPOT_ACC = __ENV.HOTSPOT_ACC || 'STRESS-HOT-CO-001';
const CLIENT_PREFIX = __ENV.CLIENT_PREFIX || 'STRESS-CLI-';
const BTC_USD_RATE = 100000;
const LEADER_PORTS = [8081, 8082, 8083];
const MAX_RETRIES = 3;
const RETRY_BACKOFF_MS = [100, 200, 400];

// Shared mutable leader URL — refreshed on connection failure or leader change
let leaderUrl = __ENV.BASE_URL || null;
let leaderUrlTimestamp = 0;
const LEADER_TTL_MS = 10_000; // re-probe leader every 10s

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

// ============================================================
// Setup helpers — #6: check every setup POST, abort on failure
// ============================================================

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
  console.log('=== SETUP: Initializing accounts ===');

  // 1. Create hotspot account
  const ok1 = setupPost('/ledger/accounts', JSON.stringify({
    requestId: `init-hotspot-${Date.now()}`,
    accountId: HOTSPOT_ACC,
    accountType: 'COMPANY',
    displayName: 'Hotspot Co',
    ownerId: 'CO-HOTSPOT',
    balanceInitializations: [
      {balanceType: 'AVAILABLE_BALANCE', currency: 'USD'},
      {balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'},
    ],
  }), 'create-hotspot');

  // 2. Seed hotspot 100 BTC (via SYSTEM_SEED)
  const ok2 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-btc-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-BTC',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: '100.00000000',
      currency: 'BTC',
      lines: [
        {accountId: 'SYSTEM_SEED', balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
      ],
    }],
  }), 'seed-hotspot-BTC');

  // 3. Seed hotspot 20M USD
  const ok3 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-usd-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-USD',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: '20000000.00',
      currency: 'USD',
      lines: [
        {accountId: 'SYSTEM_SEED', balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
      ],
    }],
  }), 'seed-hotspot-USD');

  // Abort if hotspot seeding failed — remaining steps depend on it
  setupCheckDie(ok1 && ok2 && ok3);

  // 4. Create + seed 100 client accounts
  let clientOk = true;
  for (let i = 1; i <= 100; i++) {
    const clientAcc = `${CLIENT_PREFIX}${String(i).padStart(4, '0')}`;
    clientOk = setupPost('/ledger/accounts', JSON.stringify({
      requestId: `init-client-${i}-${Date.now()}`,
      accountId: clientAcc,
      accountType: 'CLIENT',
      displayName: `Client ${i}`,
      ownerId: `CUST-${i}`,
      balanceInitializations: [
        {balanceType: 'AVAILABLE_BALANCE', currency: 'USD'},
        {balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'},
      ],
    }), `create-client-${i}`) && clientOk;

    clientOk = setupPost('/ledger/postings', JSON.stringify({
      requestId: `seed-client-${i}-${Date.now()}`,
      businessEventType: 'DEPOSIT',
      businessEventRef: `SEED-CLIENT-${i}`,
      valueDate: '2026-05-27',
      legs: [
        {
          legId: 'leg-1',
          postingType: 'DEPOSIT',
          amount: '10000.00',
          currency: 'USD',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'DEPOSIT',
          amount: '0.10000000',
          currency: 'BTC',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
          ],
        },
      ],
    }), `seed-client-${i}`) && clientOk;

    if (!clientOk && setupFailures > 5) {
      console.error(`FATAL: ${setupFailures} client setup failures. Aborting.`);
      throw new Error('Client setup failed');
    }
  }

  if (setupFailures > 0) {
    console.warn(`SETUP WARNING: ${setupFailures} step(s) failed but continuing`);
  } else {
    console.log('Account initialization complete — 0 failures');
  }

  return { initialized: true };
}

// ============================================================
// Main test — #7: leader refresh, #8: retry/backpressure
// ============================================================

export default function () {
  const vu = __VU;
  const iter = __ITER;
  const clientIdx = (vu % 100) + 1;
  const clientAcc = `${CLIENT_PREFIX}${String(clientIdx).padStart(4, '0')}`;
  const reqId = `k6-${vu}-${iter}-${Date.now()}`;

  const isBuy = (vu % 2) === 0;
  const amountUsd = '100.00';
  const amountBtc = (parseFloat(amountUsd) / BTC_USD_RATE).toFixed(8);

  let payload;
  if (isBuy) {
    payload = JSON.stringify({
      requestId: reqId,
      businessEventType: 'RFQ_SETTLEMENT',
      businessEventRef: `RFQ-BUY-${vu}-${iter}`,
      valueDate: '2026-05-27',
      legs: [
        {
          legId: 'leg-1',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountUsd,
          currency: 'USD',
          lines: [
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Client USD sell'},
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Company USD receive'},
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountBtc,
          currency: 'BTC',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Company BTC pay'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Client BTC receive'},
          ],
        },
      ],
    });
  } else {
    payload = JSON.stringify({
      requestId: reqId,
      businessEventType: 'RFQ_SETTLEMENT',
      businessEventRef: `RFQ-SELL-${vu}-${iter}`,
      valueDate: '2026-05-27',
      legs: [
        {
          legId: 'leg-1',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountBtc,
          currency: 'BTC',
          lines: [
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Client BTC sell'},
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Company BTC receive'},
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountUsd,
          currency: 'USD',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Company USD pay'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Client USD receive'},
          ],
        },
      ],
    });
  }

  // #8: Retry with backoff for transient errors (QUEUE_FULL, leader unavailable)
  let res = null;
  let lastStatus = 0;

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    if (attempt > 0) {
      sleep(RETRY_BACKOFF_MS[attempt - 1] / 1000);
      if (lastStatus === 0 || lastStatus === 504) {
        refreshLeader(); // #7: re-probe leader on connection failure
      }
    }

    res = http.post(`${getLeaderUrl()}/ledger/postings`, payload, {
      headers: { 'Content-Type': 'application/json' },
      timeout: '10s',
    });

    lastStatus = res.status;

    // Success or non-retriable application error — stop retrying
    if (res.status === 200 || res.status === 400 || res.status === 404 || res.status === 409) {
      break;
    }

    // Only retry on QUEUE_FULL (429), SERVICE_UNAVAILABLE (503), GATEWAY_TIMEOUT (504), connection refused (0)
    if (res.status !== 429 && res.status !== 503 && res.status !== 504 && res.status !== 0) {
      break;
    }
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'outcome COMPLETED': (r) => r.json('status') === 'COMPLETED',
  });
  sleep(0.05);
}
