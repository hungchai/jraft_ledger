// Sustained-rate soak wrapper over k6-posting-stress.js. RATE (default 3000/s) and
// DUR (default 24h) via env.
//
// Long-run k6 footguns this config avoids (all hit during the 2026-07-05/06 soaks):
//   - experimental-prometheus-rw output: blocked output goroutine wedged the engine
//     42min in (TPS→0, process alive). Drive WITHOUT it; report from the server-side
//     Prometheus (ledger_posting_duration has TPS + percentiles).
//   - preAllocatedVUs: each VU is a full JS VM (~10MB). 300 VUs OOM-killed k6 on the
//     4GiB mgmt host. A 3k arrival rate at ~3ms/iter needs ~10 busy VUs.
//   - run with --no-summary --no-thresholds: end-of-run Trend summaries retain raw
//     samples (~19MB/min at 3k iter/s) and OOM multi-hour runs.
import postingFn, { setup as origSetup } from './k6-posting-stress.js';

export const setup = origSetup;

export const options = {
  scenarios: {
    posting_soak: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 3000),
      timeUnit: '1s',
      duration: __ENV.DUR || '24h',
      preAllocatedVUs: 32,
      maxVUs: 64,
    },
  },
};

export default postingFn;
