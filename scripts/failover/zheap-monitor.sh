#!/usr/bin/env bash
# Poll JVM ZHeap used (the real OOM signal under ZGC — RSS misleads) on every
# ledger node every 15s. Flags container exit / OOM.
# Usage: scripts/failover/zheap-monitor.sh <out.csv> <minutes>
set -uo pipefail
OUT="${1:?usage: zheap-monitor.sh <out.csv> <minutes>}"; MIN="${2:-10}"
NODES=(ledger-node-1 ledger-node-2 ledger-node-3)
echo "epoch,node,role,zheap_used_m,zheap_cap_m,status,exit" > "$OUT"
end=$(( $(date +%s) + MIN*60 ))
while [ "$(date +%s)" -lt "$end" ]; do
  ts=$(date +%s)
  for n in "${NODES[@]}"; do
    p=$(( 8080 + ${n##*-} ))
    st=$(docker inspect -f '{{.State.Status}}' "$n" 2>/dev/null || echo gone)
    ec=$(docker inspect -f '{{.State.ExitCode}}' "$n" 2>/dev/null || echo "")
    role=$(curl -fs -m1 "http://localhost:$p/health" 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin).get("role","?"))' 2>/dev/null || echo "-")
    zh=$(docker exec "$n" jcmd 1 GC.heap_info 2>/dev/null | grep -oE "ZHeap +used [0-9]+M, capacity [0-9]+M" | grep -oE "[0-9]+M" | tr -d M | tr '\n' ',' )
    used=${zh%%,*}; cap=$(echo "$zh"|cut -d, -f2)
    echo "$ts,$n,$role,${used:-},${cap:-},$st,$ec" >> "$OUT"
    [ "$st" != "running" ] && echo "[$(date +%H:%M:%S)] !! $n status=$st exit=$ec" | tee -a "$OUT.events"
    docker exec "$n" sh -c 'ls /var/lib/ledger/*.hprof 2>/dev/null' >/dev/null 2>&1 && echo "[$(date +%H:%M:%S)] !! $n heap dump exists (OOM)" | tee -a "$OUT.events"
  done
  sleep 15
done
echo "zheap-monitor done (${MIN}m)"
