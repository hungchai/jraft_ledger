// SLO bars used by every scenario. Single source of truth so the perf gate
// is reviewable in one place.
//
// Ported from backend-ledger/loadtest. jraft-ledger NFR for write path is
// stricter (Posting P95 ≤ 3 ms per CLAUDE.md §2.10) but we keep the
// backend-ledger bars here so cross-platform comparison is apples-to-apples.
// Override with LT_LOOSE_THRESHOLDS=1 for early experiments where 503s on
// leadership churn would otherwise abort the run before any data is gathered.

const loose = __ENV.LT_LOOSE_THRESHOLDS === '1';

export const writePathThresholds = {
    http_req_duration: [
        { threshold: 'p(50)<10', abortOnFail: false },
        { threshold: 'p(95)<30', abortOnFail: false },
        { threshold: 'p(99)<50', abortOnFail: false },
        { threshold: 'p(99.9)<200', abortOnFail: false },
    ],
    http_req_failed: [loose ? 'rate<0.5' : 'rate<0.001'],
    checks: [loose ? 'rate>0.5' : 'rate>0.999'],
};

export const readPathThresholds = {
    http_req_duration: [
        { threshold: 'p(50)<5', abortOnFail: false },
        { threshold: 'p(99)<10', abortOnFail: false },
        { threshold: 'p(99.9)<50', abortOnFail: false },
    ],
    http_req_failed: [loose ? 'rate<0.5' : 'rate<0.001'],
    checks: [loose ? 'rate>0.5' : 'rate>0.999'],
};
