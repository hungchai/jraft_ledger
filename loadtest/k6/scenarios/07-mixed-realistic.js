// Realistic traffic mix proving steady-state SLO: 70% postings, 30% reads.
// This is the "can we ship?" gate.

import { CURRENCY, accounts, buildPosting, expect2xx, expectCompleted, getJson, pickPair, postLeader, uuid } from '../lib/data.js';
import { writePathThresholds } from '../lib/thresholds.js';

export const options = {
    scenarios: {
        writes: {
            executor: 'constant-arrival-rate',
            exec: 'transferOnly',
            rate: Number(__ENV.LT_WRITE_RATE || 700),
            timeUnit: '1s',
            duration: __ENV.LT_MIXED_DURATION || '30m',
            preAllocatedVUs: 200,
            maxVUs: 500,
        },
        reads: {
            executor: 'constant-arrival-rate',
            exec: 'readOnly',
            rate: Number(__ENV.LT_READ_RATE || 300),
            timeUnit: '1s',
            duration: __ENV.LT_MIXED_DURATION || '30m',
            preAllocatedVUs: 100,
            maxVUs: 300,
        },
    },
    thresholds: writePathThresholds,
};

export function transferOnly() {
    const [from, to] = pickPair();
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-mixed',
    });
    const res = postLeader('/ledger/postings', body, 'mixed.transfer');
    expectCompleted(res, 'mixed.transfer');
}

export function readOnly() {
    const acct = accounts[Math.floor(Math.random() * accounts.length)];
    const res = getJson(`/ledger/balances?accountId=${acct.id}&balanceType=AVAILABLE_BALANCE&currency=${CURRENCY}`);
    expect2xx(res, 'mixed.read');
}
