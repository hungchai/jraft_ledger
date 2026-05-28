// Idempotency correctness — same requestId replayed 3× must produce 1 set of
// entries. CLAUDE.md §2.2: every write path checks idempotencyStore; duplicate
// requestId returns the cached result immediately.

import { check } from 'k6';
import { buildPosting, pickPair, postLeader, uuid } from '../lib/data.js';

export const options = {
    scenarios: {
        replay: {
            executor: 'shared-iterations',
            vus: 50,
            iterations: Number(__ENV.LT_ITERATIONS || 1000),
            maxDuration: __ENV.LT_DURATION || '5m',
        },
    },
    thresholds: {
        checks: ['rate>0.999'],
        http_req_failed: ['rate<0.001'],
    },
};

export default function () {
    const [from, to] = pickPair();
    const requestId = `replay-${uuid()}`;
    const body = buildPosting({
        debitAccountId: from.id,
        creditAccountId: to.id,
        requestId,
        businessEventType: 'TRANSFER',
        businessEventRef: 'k6-idempotency-replay',
    });

    const first = postLeader('/ledger/postings', body, 'idem-first');
    check(first, { 'first 2xx': r => r.status >= 200 && r.status < 300 });
    let firstJournalId = null;
    try { firstJournalId = first.json('journalId'); } catch (_) { /* ignore */ }

    for (let i = 0; i < 2; i++) {
        const replay = postLeader('/ledger/postings', body, 'idem-replay');
        check(replay, {
            'replay 2xx': r => r.status >= 200 && r.status < 300,
            'same journalId': r => {
                try { return r.json('journalId') === firstJournalId; } catch (_) { return false; }
            },
        });
    }
}
