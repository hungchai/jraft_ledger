// Balance read path — proves the read SLO (p99 < 10ms target).
// Reads can hit any node (Learner / projection path).

import { check } from 'k6';
import { CURRENCY, accounts, getJson } from '../lib/data.js';
import { readPathThresholds } from '../lib/thresholds.js';

export const options = {
    scenarios: {
        read: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.LT_RATE || 2000),
            timeUnit: '1s',
            duration: __ENV.LT_DURATION || '5m',
            preAllocatedVUs: 100,
            maxVUs: 300,
        },
    },
    thresholds: readPathThresholds,
};

export default function () {
    const acct = accounts[Math.floor(Math.random() * accounts.length)];
    const res = getJson(`/ledger/balances?accountId=${acct.id}&balanceType=AVAILABLE_BALANCE&currency=${CURRENCY}`);
    check(res, {
        '200': r => r.status === 200,
        'json': r => r.headers['Content-Type']?.includes('application/json'),
    });
}
