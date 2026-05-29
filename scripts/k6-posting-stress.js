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
    http_req_duration: ['p(95)<20', 'p(99)<50'], // NFR-1: Raft consensus realistic target
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8082';
const HOTSPOT_ACC = __ENV.HOTSPOT_ACC || 'STRESS-HOT-CO-001';
const CLIENT_PREFIX = __ENV.CLIENT_PREFIX || 'STRESS-CLI-';
// FX rate: 1 BTC = 100,000 USD
const BTC_USD_RATE = 100000;

let initialized = false;

// Setup runs ONCE before iterations start — not on every iteration
export function setup() {
  console.log('=== SETUP: Initializing accounts (runs once) ===');
  initAccounts();
  return { initialized: true };
}

function initAccounts() {
  if (initialized) return;
  initialized = true;

  console.log('Initializing accounts...');

  // 1. Create hotspot account (USD + BTC)
  const hotspotPayload = JSON.stringify({
    requestId: `init-hotspot-${Date.now()}`,
    accountId: HOTSPOT_ACC,
    accountType: 'COMPANY',
    displayName: 'Hotspot Co',
    ownerId: 'CO-HOTSPOT',
    balanceInitializations: [
      {balanceType: 'AVAILABLE_BALANCE', currency: 'USD'},
      {balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'}
    ]
  });

  http.post(`${BASE_URL}/ledger/accounts`, hotspotPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  // 2. Seed hotspot with 100 BTC (amount/currency at leg level, not line level)
  const seedBtcPayload = JSON.stringify({
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
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'}
      ]
    }]
  });
  http.post(`${BASE_URL}/ledger/postings`, seedBtcPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  // 3. Seed hotspot with 20,000,000 USD
  const seedUsdPayload = JSON.stringify({
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
        {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'}
      ]
    }]
  });
  http.post(`${BASE_URL}/ledger/postings`, seedUsdPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  // 4. Create 100 client accounts with BTC + USD
  for (let i = 1; i <= 100; i++) {
    const clientAcc = `${CLIENT_PREFIX}${String(i).padStart(4, '0')}`;
    const clientPayload = JSON.stringify({
      requestId: `init-client-${i}-${Date.now()}`,
      accountId: clientAcc,
      accountType: 'CLIENT',
      displayName: `Client ${i}`,
      ownerId: `CUST-${i}`,
      balanceInitializations: [
        {balanceType: 'AVAILABLE_BALANCE', currency: 'USD'},
        {balanceType: 'AVAILABLE_BALANCE', currency: 'BTC'}
      ]
    });
    http.post(`${BASE_URL}/ledger/accounts`, clientPayload, {
      headers: { 'Content-Type': 'application/json' },
    });

    // Seed each client with 10,000 USD and 0.1 BTC
    const clientSeedPayload = JSON.stringify({
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
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'}
          ]
        },
        {
          legId: 'leg-2',
          postingType: 'DEPOSIT',
          amount: '0.10000000',
          currency: 'BTC',
          lines: [
            {accountId: HOTSPOT_ACC, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'DEBIT'},
            {accountId: clientAcc, balanceType: 'AVAILABLE_BALANCE', position: 'CURRENT', entryType: 'CREDIT'}
          ]
        }
      ]
    });
    http.post(`${BASE_URL}/ledger/postings`, clientSeedPayload, {
      headers: { 'Content-Type': 'application/json' },
    });
  }

  console.log('Account initialization complete');
}

export default function () {
  const vu = __VU;
  const iter = __ITER;
  const clientIdx = (vu % 100) + 1;
  const clientAcc = `${CLIENT_PREFIX}${String(clientIdx).padStart(4, '0')}`;
  const reqId = `k6-${vu}-${iter}-${Date.now()}`;
  
  // Alternate between BUY and SELL
  const isBuy = (vu % 2) === 0;
  const amountUsd = '100.00';
  const amountBtc = (parseFloat(amountUsd) / BTC_USD_RATE).toFixed(8);

  let payload;
  if (isBuy) {
    // RFQ BUY: Client buys USD, sells BTC
    // Leg 1 (USD): Client DEBIT USD → Company CREDIT USD
    // Leg 2 (BTC): Company DEBIT BTC → Client CREDIT BTC
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
            {
              accountId: clientAcc,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'DEBIT',
              description: 'RFQ Client USD sell',
            },
            {
              accountId: HOTSPOT_ACC,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'CREDIT',
              description: 'RFQ Company USD receive',
            },
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountBtc,
          currency: 'BTC',
          lines: [
            {
              accountId: HOTSPOT_ACC,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'DEBIT',
              description: 'RFQ Company BTC pay',
            },
            {
              accountId: clientAcc,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'CREDIT',
              description: 'RFQ Client BTC receive',
            },
          ],
        },
      ],
    });
  } else {
    // RFQ SELL: Client sells USD, buys BTC
    // Leg 1 (BTC): Client DEBIT BTC → Company CREDIT BTC
    // Leg 2 (USD): Company DEBIT USD → Client CREDIT USD
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
            {
              accountId: clientAcc,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'DEBIT',
              description: 'RFQ Client BTC sell',
            },
            {
              accountId: HOTSPOT_ACC,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'CREDIT',
              description: 'RFQ Company BTC receive',
            },
          ],
        },
        {
          legId: 'leg-2',
          postingType: 'TRADE_SETTLEMENT',
          amount: amountUsd,
          currency: 'USD',
          lines: [
            {
              accountId: HOTSPOT_ACC,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'DEBIT',
              description: 'RFQ Company USD pay',
            },
            {
              accountId: clientAcc,
              balanceType: 'AVAILABLE_BALANCE',
              position: 'CURRENT',
              entryType: 'CREDIT',
              description: 'RFQ Client USD receive',
            },
          ],
        },
      ],
    });
  }

  const res = http.post(`${BASE_URL}/ledger/postings`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  sleep(0.05);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'outcome COMPLETED': (r) => r.json('status') === 'COMPLETED',
  });
}
