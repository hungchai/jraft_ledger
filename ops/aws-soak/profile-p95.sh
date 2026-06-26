#!/usr/bin/env bash
# profile-p95.sh — decompose the steady-state P95 tail by pipeline stage, on a WARM cluster.
# Snapshots every per-stage Micrometer histogram, runs a warm 100 VU window, snapshots again,
# and computes windowed P50/P95/P99 per stage via bucket-diff (Prometheus histogram_quantile
# chokes on Micrometer's scientific-notation le labels, so we diff buckets ourselves).
#
# Answers: of the ~5.6ms posting P95, which stage owns the tail —
#   commit (raft_wait_apply) | FSM apply (apply_total/persist/deserialize/rocksdb) | neither (GC).
#
#   ./profile-p95.sh            # run after `up` + at least one warmup
set -uo pipefail
cd "$(dirname "$0")"
RUN="$(cat .aws-soak/current)"; RD=".aws-soak/$RUN"; KEY="$RD/key.pem"
SSH(){ ssh -n -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=20 "$@"; }
MGMT=$(grep '^MGMT_PUB=' "$RD/run.env"|cut -d= -f2)
# bash 3.2 (macOS) has no mapfile — read into an array portably
NODE_PRIVS=()
while IFS= read -r line; do [ -n "$line" ] && NODE_PRIVS+=("$line"); done < <(grep '^NODE_URLS=' "$RD/run.env"|cut -d= -f2|tr ',' '\n'|sed -E 's#http://([^:]+):.*#\1#')
N1=${NODE_PRIVS[0]} N2=${NODE_PRIVS[1]} N3=${NODE_PRIVS[2]}
LP=$N1; for p in "${NODE_PRIVS[@]}"; do SSH ubuntu@$MGMT "curl -s --max-time 3 http://$p:8080/health 2>/dev/null|grep -q LEADER" && { LP=$p; break; }; done
echo "leader=$LP"

# warmup so JIT + caches are hot and accounts exist (discarded)
echo "warmup 30s @ 20 VU (discarded)..."
SSH ubuntu@$MGMT "cd ~/jraft_ledger && nohup k6 run --vus 20 --duration 30s -e NODES=http://$N1:8080,http://$N2:8080,http://$N3:8080 scripts/k6-posting-stress.js >/tmp/k6-warm.log 2>&1 </dev/null & disown; echo ok" >/dev/null
for i in $(seq 1 20); do SSH ubuntu@$MGMT "pgrep -f 'k6 run' >/dev/null" && sleep 5 || break; done

STAGES="ledger_posting_duration ledger_raft_wait_apply ledger_apply_total ledger_apply_persist ledger_apply_deserialize ledger_rocksdb_write ledger_raft_enqueue"
grab(){ SSH ubuntu@$MGMT "curl -s http://$LP:8080/actuator/prometheus 2>/dev/null | grep -E '($(echo $STAGES|tr ' ' '|'))_seconds_bucket'"; }

echo "snapshot PRE..."; grab > /tmp/p95_pre.txt
LPUB=$(awk -v p="$LP" '$4==p{print $3}' "$RD/hosts")
SSH ubuntu@$LPUB "command -v mpstat >/dev/null || sudo apt-get install -y sysstat >/dev/null 2>&1; nohup mpstat 1 95 >/tmp/mpstat.out 2>&1 </dev/null & disown" >/dev/null  # CPU during measure
echo "measure: 100 VU 90s (warm)..."
SSH ubuntu@$MGMT "cd ~/jraft_ledger && nohup k6 run --vus 100 --duration 90s -e NODES=http://$N1:8080,http://$N2:8080,http://$N3:8080 scripts/k6-posting-stress.js >/tmp/k6-p95.log 2>&1 </dev/null & disown; echo ok" >/dev/null
for i in $(seq 1 40); do SSH ubuntu@$MGMT "pgrep -f 'k6 run' >/dev/null" && sleep 5 || break; done
echo "snapshot POST..."; grab > /tmp/p95_post.txt

echo "=== leader CPU under load (mpstat avg) + cores ==="
SSH ubuntu@$LPUB "nproc | sed 's/^/  vCPU=/'; awk '/Average:/ && /all/{print \"  CPU avg: usr=\"\$3\" sys=\"\$5\" iowait=\"\$6\" steal=\"\$8\" idle=\"\$NF}' /tmp/mpstat.out | tail -1"
echo "=== JVM memory + GC on leader (from actuator) ==="
SSH ubuntu@$MGMT "curl -s http://$LP:8080/actuator/prometheus 2>/dev/null | grep -E 'jvm_memory_(used|committed)_bytes|process_resident_memory_bytes|jvm_gc_pause_seconds_(count|sum)|jvm_gc_pause_seconds_max|jvm_memory_max_bytes' | grep -v '^#'" | python3 -c "
import sys,re
heap_u=nonheap_u=heap_c=0.0; rss=None; gc_count=gc_sum=gc_max=0.0
for ln in sys.stdin:
    m=re.match(r'(\S+?)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)',ln.strip())
    if not m: continue
    name,lbl,val=m.group(1),m.group(2) or '',float(m.group(3))
    if name=='jvm_memory_used_bytes': (heap_u:=heap_u+val) if 'heap' in lbl and 'nonheap' not in lbl else (nonheap_u:=nonheap_u+val)
    elif name=='jvm_memory_committed_bytes' and 'heap' in lbl and 'nonheap' not in lbl: heap_c+=val
    elif name=='process_resident_memory_bytes': rss=val
    elif name=='jvm_gc_pause_seconds_count': gc_count+=val
    elif name=='jvm_gc_pause_seconds_sum': gc_sum+=val
    elif name=='jvm_gc_pause_seconds_max': gc_max=max(gc_max,val)
mb=lambda b: round(b/1048576,1)
print(f'  heap used={mb(heap_u)}MB  heap committed={mb(heap_c)}MB  nonheap used={mb(nonheap_u)}MB')
print(f'  process RSS={mb(rss) if rss else \"n/a\"}MB')
print(f'  GC pauses: count={int(gc_count)}  total={round(gc_sum*1000,1)}ms  max={round(gc_max*1000,2)}ms')
" 2>/dev/null

echo "=== per-stage windowed P50/P95/P99 (bucket diff) ==="
python3 - "$STAGES" <<'PY'
import re,sys
stages=sys.argv[1].split()
def load(f):
    d={}  # metric -> {le: count}
    for ln in open(f):
        mm=re.match(r'(\w+)_seconds_bucket\{.*?le="([0-9.eE+-]+|\+Inf)".*?\}\s+([0-9.]+)',ln)
        if mm:
            met,le,c=mm.group(1),mm.group(2),mm.group(3)
            le=float('inf') if le=='+Inf' else float(le)
            d.setdefault(met,{}); d[met][le]=d[met].get(le,0)+float(c)  # sum across labels (RFQ+DEPOSIT etc)
    return d
pre,post=load('/tmp/p95_pre.txt'),load('/tmp/p95_post.txt')
print(f"  {'stage':24}{'count':>9}{'P50':>9}{'P95':>9}{'P99':>9}")
for met in stages:
    if met not in post: print(f"  {met:24} (absent)"); continue
    diff=sorted((le, post[met].get(le,0)-pre.get(met,{}).get(le,0)) for le in post[met])
    total=max((c for _,c in diff), default=0)
    if total<=0: print(f"  {met:24} (no traffic)"); continue
    def q(p):
        t=p*total
        for le,c in diff:
            if c>=t: return round(le*1000,3)
        return None
    print(f"  {met.replace('ledger_',''):24}{int(total):>9}{q(.5):>9}{q(.95):>9}{q(.99):>9}")
PY
echo DONE_P95
