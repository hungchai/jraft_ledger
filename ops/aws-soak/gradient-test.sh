#!/usr/bin/env bash
# gradient-test.sh — run a VU ramp against the ALREADY-UP cluster, capturing k6 + the
# server-side ledger_* timers at each step. NO teardown between steps (steps share the
# cluster — that's the point). Teardown is per-ARM, via `aws-soak.sh down`.
#
# The key comparison metric across storage arms is ledger_raft_wait_apply (the quorum-
# commit wait) at each VU level — captured here per step into results/<run>/.
#
#   ./gradient-test.sh                       # default ramp 10 50 100 200, 2m each
#   ./gradient-test.sh "10 50 100 200 400" 2m
set -uo pipefail
cd "$(dirname "$0")"
RUN="$(cat .aws-soak/current)"; RD=".aws-soak/$RUN"; KEY="$RD/key.pem"
DISK=$(grep '^DISK=' "$RD/run.env" | cut -d= -f2)
MGMT=$(grep '^MGMT_PUB=' "$RD/run.env" | cut -d= -f2)
NODE_URLS=$(grep '^NODE_URLS=' "$RD/run.env" | cut -d= -f2)
OUT="$(pwd)/results/$RUN"; mkdir -p "$OUT"
VUS_LIST="${1:-10 50 100 200}"; DUR="${2:-2m}"
export AWS_ACCESS_KEY_ID=$(aws configure get aws_access_key_id --profile jraft-soak 2>/dev/null || echo "${AWS_ACCESS_KEY_ID:-}")
export AWS_SECRET_ACCESS_KEY=$(aws configure get aws_secret_access_key --profile jraft-soak 2>/dev/null || echo "${AWS_SECRET_ACCESS_KEY:-}")
SSH() { ssh -n -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 "$@"; }
LEADER_PRIV=$(echo "$NODE_URLS" | tr ',' '\n' | sed -E 's#http://([^:]+):.*#\1#' | head -1)

# capture leader's cumulative ledger_* timers → avg per op; diff vs previous step gives per-step avg
snap_timers() {  # $1 = label file
  SSH ubuntu@$MGMT "curl -s http://$LEADER_PRIV:8080/actuator/prometheus 2>/dev/null | grep -E 'ledger_(raft|apply|rocksdb|posting)|http_server_requests_seconds' | grep -E '_(sum|count)' | grep -v bucket" > "$1" 2>/dev/null
}

echo "############ gradient test — run=$RUN  disk-arm=$DISK  ramp=[$VUS_LIST] x $DUR ############"
# re-find leader (in case of churn)
for p in $(echo "$NODE_URLS" | tr ',' '\n' | sed -E 's#http://([^:]+):.*#\1#'); do
  SSH ubuntu@$MGMT "curl -s --max-time 3 http://$p:8080/health 2>/dev/null | grep -q LEADER" && { LEADER_PRIV=$p; break; }
done
echo "leader=$LEADER_PRIV  results→ $OUT"

# WARMUP: a short run BEFORE measuring, to (a) create+seed the account universe and
# (b) warm the JVM (JIT) + RocksDB caches. Without this, a fresh/just-restarted cluster's
# first measured step has a cold-start + setup burst that inflates p95/p99 (median is
# unaffected). Its metrics are discarded — measured steps below run on a hot cluster.
echo "── warmup (30s @ ${WARMUP_VUS:-20} VU, discarded) ──"
AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
  ./aws-soak.sh test --vus "${WARMUP_VUS:-20}" --duration 30s >/dev/null 2>&1
for i in $(seq 1 40); do SSH ubuntu@$MGMT "pgrep -f 'k6 run' >/dev/null" || break; sleep 5; done
sleep 3

snap_timers "$OUT/timers-baseline.txt"
PREV="$OUT/timers-baseline.txt"

for VU in $VUS_LIST; do
  echo "── step VU=$VU ──"
  AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    ./aws-soak.sh test --vus "$VU" --duration "$DUR" >/dev/null 2>&1
  # wait for this step's k6 to finish before starting the next (no overlap)
  for i in $(seq 1 120); do
    SSH ubuntu@$MGMT "pgrep -f 'k6 run' >/dev/null" || break
    sleep 5
  done
  sleep 3
  CUR="$OUT/timers-vu$VU.txt"; snap_timers "$CUR"
  # per-step avg = Δsum/Δcount between snapshots, for the headline metrics
  python3 - "$PREV" "$CUR" "$VU" <<'PY'
import sys,re
prev,cur,vu=sys.argv[1],sys.argv[2],sys.argv[3]
def load(f):
    d={}
    for ln in open(f):
        m=re.match(r'(\S+?)(?:\{[^}]*\})?\s+([0-9.eE+-]+)$',ln.strip())
        if m: d[m.group(1)+('_C' if '_count' in ln else '_S' if '_sum' in ln else '')]=float(m.group(2))
    return d
a,b=load(prev),load(cur)
def delta_avg(base):
    cs,cc=base+'_seconds_sum_S',base+'_seconds_count_C'
    ds=b.get(cs,0)-a.get(cs,0); dc=b.get(cc,0)-a.get(cc,0)
    return (ds/dc*1000) if dc>0 else None,dc
print(f"  VU={vu} per-step avg (ms):")
for label,base in [("http(server)","http_server_requests"),("raft_total","ledger_raft_total"),
                   ("raft_wait_apply","ledger_raft_wait_apply"),("apply_total","ledger_apply_total"),
                   ("rocksdb_write","ledger_rocksdb_write")]:
    v,dc=delta_avg(base)
    if v is not None: print(f"    {label:16} {v:7.3f}   (Δops={int(dc)})")
PY
  PREV="$CUR"
done

echo "############ gradient done. per-step timer snapshots in $OUT/timers-vu*.txt ############"
echo "  Next per-arm steps:  ./consistency-check.sh   then   ./consistency-check.sh --failover   then   ./aws-soak.sh down"
