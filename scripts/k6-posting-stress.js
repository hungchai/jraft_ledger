import http from 'k6/http';
import { check, sleep } from 'k6';

// Stress Test: RFQ Hotspot — maps to docs/STRESS-TEST-PLAN.md Phase 2
// RFQ BUY/SELL scenarios with BTC/USDT pair (BTC 8dp, USDT 16dp)
// BUY: Client buys USDT, sells BTC
// SELL: Client sells USDT, buys BTC
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
const BTC_USDT_RATE = 73091.09; // 1 BTC = 73091.09 USDT

// Precision: BTC = 8dp, USDT = 16dp
const BTC_DP = 8;

const LEADER_PORTS = [8081, 8082, 8083];
const MAX_RETRIES = 3;
const RETRY_BACKOFF_MS = [100, 200, 400];

// Shared mutable leader URL — refreshed on connection failure or TTL expiry
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

// ============================================================
// Setup helpers — check every POST, abort on critical failure
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
  console.log('=== SETUP: Initializing accounts (BTC 8dp, USDT 16dp) ===');

  // 1. Create hotspot account (USDT + BTC)
  const ok1 = setupPost('/ledger/accounts', JSON.stringify({
    requestId: `init-hotspot-${Date.now()}`,
    accountId: HOTSPOT_ACC,
    accountType: 'COMPANY',
    displayName: 'Hotspot Co',
    ownerId: 'CO-HOTSPOT',
    balanceInitializations: [
      {balanceType: 'AVAILABLE_BALANCE', currency: 'USDT'},
      {balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'},
    ],
  }), 'create-hotspot');

  // 2. Seed hotspot 1000 BTC from SYSTEM_SEED (8dp)
  const ok2 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-btc-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-BTC',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: '5000.00000000',
      currency: 'BTC',
      lines: [
        {accountId: 'SYSTEM_SEED', balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
      ],
    }],
  }), 'seed-hotspot-BTC');

  // 3. Seed hotspot 200M USDT from SYSTEM_SEED (16dp)
  const ok3 = setupPost('/ledger/postings', JSON.stringify({
    requestId: `seed-usdt-${Date.now()}`,
    businessEventType: 'DEPOSIT',
    businessEventRef: 'SEED-USDT',
    valueDate: '2026-05-27',
    legs: [{
      legId: 'leg-1',
      postingType: 'DEPOSIT',
      amount: '200000000.0000000000000000',
      currency: 'USDT',
      lines: [
        {accountId: 'SYSTEM_SEED', balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
      ],
    }],
  }), 'seed-hotspot-USDT');

  // Abort if hotspot seeding failed
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
        {balanceType: 'AVAILABLE_BALANCE', currency: 'USDT'},
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
          amount: '1000000.0000000000000000',
          currency: 'USDT',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'},
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'DEPOSIT',
          amount: '10.00000000',
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
// Main test — RFQ BUY/SELL with BTC/USDT pair
// USDT: 16dp precision, BTC: 8dp precision
// ============================================================

export default function () {
  const vu = __VU;
  const iter = __ITER;
  const clientIdx = (vu % 100) + 1;
  const clientAcc = `${CLIENT_PREFIX}${String(clientIdx).padStart(4, '0')}`;
  const reqId = `k6-${vu}-${iter}-${Date.now()}`;

  const isBuy = (vu % 2) === 0;
  // Trade: 100 USDT (16dp) for equivalent BTC (8dp)
  const amountUsdt = '100.0000000000000000';
  const amountBtc = (100.0 / BTC_USDT_RATE).toFixed(BTC_DP); // "0.00100000"

  let payload;
  if (isBuy) {
    // RFQ BUY: Client buys USDT, sells BTC
    // Leg 1 (USDT): Client DEBIT USDT → Company CREDIT USDT
    // Leg 2 (BTC):  Company DEBIT BTC  → Client CREDIT BTC
    payload = JSON.stringify({
      requestId: reqId,
      businessEventType: 'RFQ_SETTLEMENT',
      businessEventRef: `RFQ-BUY-${vu}-${iter}`,
      valueDate: '2026-05-27',
      legs: [
        {
          legId: 'leg-1',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountUsdt,
          currency: 'USDT',
          lines: [
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Client USDT sell'},
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Company USDT receive'},
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
    // RFQ SELL: Client sells USDT, buys BTC
    // Leg 1 (BTC):  Client DEBIT BTC  → Company CREDIT BTC
    // Leg 2 (USDT): Company DEBIT USDT → Client CREDIT USDT
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
          amount: amountUsdt,
          currency: 'USDT',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT', description: 'RFQ Company USDT pay'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT', description: 'RFQ Client USDT receive'},
          ],
        },
      ],
    });
  }

  // Retry with backoff for transient errors
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

    // Success or non-retriable application error
    if (res.status === 200 || res.status === 400 || res.status === 404 || res.status === 409) {
      break;
    }

    // Retry on QUEUE_FULL / SERVICE_UNAVAILABLE / GATEWAY_TIMEOUT / connection refused
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
