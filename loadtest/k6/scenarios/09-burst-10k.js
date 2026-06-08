// 10k tps push — 2× the burst target. Stress test, not an SLO commitment.
// Use constant-vus so k6 can actually saturate the service even if iterations
// run slower than 1/RATE seconds (constant-arrival-rate caps at preallocated).

import { buildPosting, expect2xx, pickPair, postLeader, uuid } from '../lib/data.js';

const VUS = Number(__ENV.LT_VUS || 400);
const DURATION = __ENV.LT_DURATION || '60s';

export const options = {
    scenarios: {
        push: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        // Stress test — keep gates loose. Anything that doesn't time out or
        // 5xx is interesting data.
        http_req_failed: ['rate<0.05'],
        checks: ['rate>0.95'],
    },
};

export default function () {
    const [from, to] = pickPair();
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-burst-10k',
    });
    const res = postLeader('/ledger/postings', body, '10k');
    expect2xx(res, '10k');
}
