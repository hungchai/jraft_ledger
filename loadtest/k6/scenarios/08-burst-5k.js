// 5k tps burst — confirms the 5k peak target.
// Ramps 500 → 5000 over 30s, holds 30s, drains 30s.

import { buildPosting, expect2xx, pickPair, postLeader, uuid } from '../lib/data.js';

export const options = {
    scenarios: {
        burst: {
            executor: 'ramping-arrival-rate',
            startRate: 500,
            timeUnit: '1s',
            preAllocatedVUs: 500,
            maxVUs: 2000,
            stages: [
                { target: 5000, duration: '30s' },
                { target: 5000, duration: '30s' },
                { target: 500,  duration: '30s' },
            ],
        },
    },
    thresholds: {
        http_req_duration: [
            { threshold: 'p(99)<100',  abortOnFail: false },
            { threshold: 'p(99.9)<500', abortOnFail: false },
        ],
        http_req_failed: ['rate<0.005'],
        checks: ['rate>0.995'],
    },
};

export default function () {
    const [from, to] = pickPair();
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-burst-5k',
    });
    const res = postLeader('/ledger/postings', body, 'burst');
    expect2xx(res, 'burst');
}
