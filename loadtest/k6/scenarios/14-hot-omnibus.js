// Hot-account contention probe.
//
// Other scenarios distribute writes across many independent accounts, so the
// per-account queue serialisation is never stressed. Real load concentrates
// on hot omnibus / settlement wallets, where many concurrent postings contend
// on the same accountId LinkedBlockingQueue (CLAUDE.md §2.5).
//
// By default:
//   - HOT_COUNT (5)  master accounts are hot (first N).
//   - HOT_RATIO (0.9) of iterations touch a hot account on one side.
//   - Hot side alternates DEBIT/CREDIT to hit both contention modes.

import { accounts, buildPosting, expectCompleted, pickSub, postLeader, uuid } from '../lib/data.js';
import { writePathThresholds } from '../lib/thresholds.js';

const HOT_COUNT = Number(__ENV.HOT_COUNT || 5);
const HOT_RATIO = Number(__ENV.HOT_RATIO || 0.9);

const mastersArr = accounts.filter(a => a.role === 'master');

export const options = {
    scenarios: {
        hotAccount: {
            executor: 'constant-vus',
            vus: Number(__ENV.LT_VUS || 50),
            duration: __ENV.LT_DURATION || '60s',
        },
    },
    thresholds: writePathThresholds,
};

function pickHotMaster() {
    return mastersArr[Math.floor(Math.random() * HOT_COUNT)];
}

function pickColdMaster() {
    const idx = HOT_COUNT + Math.floor(Math.random() * (mastersArr.length - HOT_COUNT));
    return mastersArr[idx];
}

function pickPair() {
    if (Math.random() >= HOT_RATIO) {
        return [pickColdMaster(), pickSub()];
    }
    const hot = pickHotMaster();
    const cold = pickSub();
    return Math.random() < 0.5 ? [hot, cold] : [cold, hot];
}

export default function () {
    const [from, to] = pickPair();
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId: uuid(),
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-hot-omnibus',
    });
    const res = postLeader('/ledger/postings', body, 'hot-omnibus');
    expectCompleted(res, 'hot-omnibus');
}

export function setup() {
    if (mastersArr.length < HOT_COUNT + 1) {
        throw new Error(
            `Need at least HOT_COUNT+1 master accounts seeded; got ${mastersArr.length}. ` +
            `Increase ACCOUNT_COUNT in seed.sh or lower HOT_COUNT.`
        );
    }
    console.log(
        `Hot master accounts (first ${HOT_COUNT}): ` +
        mastersArr.slice(0, HOT_COUNT).map(a => a.id).join(', ')
    );
    console.log(`HOT_RATIO=${HOT_RATIO} (fraction of iterations touching a hot account)`);
}
