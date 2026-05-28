// Internal transfer (RFQ-style 2-line posting) — the perf benchmark.
// 1k tps sustained for 10 min by default; override via LT_DURATION / LT_RATE.

import { buildPosting, expectCompleted, pickPair, postLeader, uuid } from '../lib/data.js';
import { writePathThresholds } from '../lib/thresholds.js';

export const options = {
    scenarios: {
        steady: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.LT_RATE || 1000),
            timeUnit: '1s',
            duration: __ENV.LT_DURATION || '10m',
            preAllocatedVUs: 200,
            maxVUs: 500,
        },
    },
    thresholds: writePathThresholds,
};

export default function () {
    const [from, to] = pickPair();
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-internal-transfer',
    });
    const res = postLeader('/ledger/postings', body, 'internal-transfer');
    expectCompleted(res, 'internal-transfer');
}
