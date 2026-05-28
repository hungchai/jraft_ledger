// Cover the major business event types at a steady 2k tps. jraft-ledger
// exposes a single multi-leg posting endpoint, so all "types" are encoded via
// `businessEventType` + the account pair selection (suspense ↔ client for
// deposits/withdrawals, master ↔ sub for transfers).

import { check } from 'k6';
import { buildPosting, pickMaster, pickSub, pickSuspense, postLeader, uuid } from '../lib/data.js';

// Equal-weighted across 8 event types so each accumulates ≥10k samples in a
// 60s run at 2k tps.
const WEIGHTS = [
    [12.5, depositDetected],
    [12.5, depositConfirm],
    [12.5, depositToHold],
    [12.5, withdrawalInitiate],
    [12.5, withdrawalExecute],
    [12.5, withdrawalToHold],
    [12.5, complianceRelease],
    [12.5, internalTransfer],
];

const DURATION = __ENV.LT_DURATION || '60s';
const VUS = Number(__ENV.LT_VUS || 30);

export const options = {
    scenarios: {
        all_types: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        http_req_duration: [
            'p(50)<10',
            'p(95)<30',
            'p(99)<50',
            'p(99.9)<200',
        ],
        http_req_failed: ['rate<0.001'],
        checks: ['rate>0.999'],
        'checks{type:deposit-detected}': ['rate>0.999'],
        'checks{type:deposit-confirm}': ['rate>0.999'],
        'checks{type:deposit-hold}': ['rate>0.999'],
        'checks{type:withdrawal-initiate}': ['rate>0.999'],
        'checks{type:withdrawal-execute}': ['rate>0.999'],
        'checks{type:withdrawal-hold}': ['rate>0.999'],
        'checks{type:compliance-release}': ['rate>0.999'],
        'checks{type:internal-transfer}': ['rate>0.999'],
    },
};

export default function () {
    pickWeighted(WEIGHTS)();
}

function pickWeighted(entries) {
    const total = entries.reduce((s, e) => s + e[0], 0);
    let r = Math.random() * total;
    for (const [w, fn] of entries) {
        r -= w;
        if (r <= 0) return fn;
    }
    return entries[entries.length - 1][1];
}

function ok(res, label) {
    return check(res, {
        '2xx': r => r.status >= 200 && r.status < 300,
    }, { type: label });
}

function postType(eventType, debit, credit, tag, label) {
    const body = buildPosting({
        debitAccountId: debit.id,
        creditAccountId: credit.id,
        requestId: uuid(),
        businessEventType: eventType,
        businessEventRef: `k6-${tag}`,
    });
    ok(postLeader('/ledger/postings', body, tag), label);
}

// suspense → client: external deposit detected, suspense holds it pre-confirm.
function depositDetected()   { postType('DEPOSIT_DETECTED',  pickSuspense(), pickSub(),     'deposit-detected',   'deposit-detected'); }
// client account credited on confirm — master (COMPANY) is the source.
function depositConfirm()    { postType('DEPOSIT_CONFIRM',   pickMaster(),   pickSub(),     'deposit-confirm',    'deposit-confirm'); }
// move to compliance hold position (still uses master as DEBIT source).
function depositToHold()     { postType('DEPOSIT_HOLD',      pickMaster(),   pickSub(),     'deposit-hold',       'deposit-hold'); }
// withdrawal initiation: client → suspense (pending out).
function withdrawalInitiate(){ postType('WITHDRAWAL_INIT',   pickMaster(),   pickSuspense(),'withdrawal-initiate','withdrawal-initiate'); }
// withdrawal execution: suspense → external (modeled as suspense DEBIT → master CREDIT).
function withdrawalExecute() { postType('WITHDRAWAL_EXEC',   pickSuspense(), pickMaster(),  'withdrawal-execute', 'withdrawal-execute'); }
function withdrawalToHold()  { postType('WITHDRAWAL_HOLD',   pickMaster(),   pickSub(),     'withdrawal-hold',    'withdrawal-hold'); }
function complianceRelease() { postType('COMPLIANCE_RELEASE',pickMaster(),   pickSub(),     'compliance-release', 'compliance-release'); }
function internalTransfer()  { postType('TRANSFER',          pickMaster(),   pickSub(),     'internal-transfer',  'internal-transfer'); }
