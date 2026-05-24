import http from 'k6/http';
import { check, sleep } from 'k6';

// Stress Test: RFQ Hotspot — maps to docs/STRESS-TEST-PLAN.md Phase 2
// Usage: k6 run --vus 1000 --duration 120s scripts/k6-posting-stress.js

export const options = {
  stages: [
    { duration: '30s', target: 100 },   // warm-up
    { duration: '60s', target: 1000 },  // peak RFQ load
    { duration: '30s', target: 0 },     // cool-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<3', 'p(99)<10'], // NFR-1
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const HOTSPOT_ACC = 'STRESS-HOT-CO-001';

export default function () {
  const vu = __VU;
  const iter = __ITER;
  const clientIdx = (vu % 1000) + 1;
  const clientAcc = `STRESS-CLI-${String(clientIdx).padStart(4, '0')}`;
  const reqId = `k6-${vu}-${iter}-${Date.now()}`;

  const payload = JSON.stringify({
    requestId: reqId,
    businessEventType: 'RFQ',
    businessEventRef: `RFQ-${vu}`,
    valueDate: '2026-05-24',
    legs: [
      {
        legId: 'leg-1',
        postingType: 'RFQ',
        lines: [
          {
            accountId: clientAcc,
            balanceType: 'AVAILABLE_BALANCE',
            position: 'CURRENT',
            entryType: 'DEBIT',
            amount: '1.00',
            description: 'RFQ client',
          },
          {
            accountId: HOTSPOT_ACC,
            balanceType: 'AVAILABLE_BALANCE',
            position: 'CURRENT',
            entryType: 'CREDIT',
            amount: '1.00',
            description: 'RFQ company',
          },
        ],
      },
    ],
  });

  const res = http.post(`${BASE_URL}/ledger/postings`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'outcome COMPLETED': (r) => r.json('status') === 'COMPLETED',
  });
}
