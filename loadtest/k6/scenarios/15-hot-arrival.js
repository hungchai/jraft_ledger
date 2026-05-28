// Hot-account contention at fixed arrival rate (matches backend-ledger
// lt-write-hot.js pattern). All writes CREDIT one of the first HOT_COUNT
// master accounts and DEBIT a random other master.
//
// Env: LT_RATE (target tps), LT_DURATION (default 30s), HOT_COUNT (default 1)

import { accounts, buildPosting, expectCompleted, postLeader, uuid } from '../lib/data.js';

const HOT_COUNT = Number(__ENV.HOT_COUNT || 1);
const RATE = Number(__ENV.LT_RATE || 2000);
const DURATION = __ENV.LT_DURATION || '30s';

const mastersArr = accounts.filter(a => a.role === 'master');
const subsArr = accounts.filter(a => a.role === 'sub');

export const options = {
    scenarios: {
        write: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 200,
            maxVUs: 2000,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.5'],
        checks: ['rate>0.5'],
    },
};

export default function () {
    // Credit one of the first HOT_COUNT master accounts.
    // Debit a random non-hot master (so DEBIT side has funds via auto-topup).
    const hot = mastersArr[Math.floor(Math.random() * HOT_COUNT)];
    const cold = mastersArr[HOT_COUNT + Math.floor(Math.random() * (mastersArr.length - HOT_COUNT))];

    const body = buildPosting({
        debitAccountId: cold.id,
        creditAccountId: hot.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: `hot${HOT_COUNT}`,
    });
    const res = postLeader('/ledger/postings', body, `hot${HOT_COUNT}`);
    expectCompleted(res, `hot${HOT_COUNT}`);
}

export function setup() {
    if (mastersArr.length < HOT_COUNT + 1) {
        throw new Error(`Need ≥ HOT_COUNT+1 masters; got ${mastersArr.length}`);
    }
    console.log(`HOT_COUNT=${HOT_COUNT} rate=${RATE}/s duration=${DURATION}`);
    console.log(`Hot masters: ${mastersArr.slice(0, HOT_COUNT).map(a => a.id).join(', ')}`);
}
