#!/usr/bin/env bash
# saturation-test.sh — drive the cluster toward its TPS ceiling (k6 SLEEP_MS=0) across a VU ramp,
# and at each step capture the saturation signature: TPS, per-phase latency (queue-wait / commit /
# apply), CPU idle, command-queue depth, and host run-queue length. Finds the knee and identifies
# which component saturates first (CPU / dispatcher / FSM-apply thread).
#
#   ./saturation-test.sh "50 100 200 400 800" 90s
set -uo pipefail
cd "$(dirname "$0")"
RUN="$(cat .aws-soak/current)"; RD=".aws-soak/$RUN"; KEY="$RD/key.pem"
SSH(){ ssh -n -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=20 "$@"; }
MGMT=$(grep '^MGMT_PUB=' "$RD/run.env"|cut -d= -f2)
NODE_PRIVS=(); while IFS= read -r l; do [ -n "$l" ] && NODE_PRIVS+=("$l"); done < <(grep '^NODE_URLS=' "$RD/run.env"|cut -d= -f2|tr ',' '\n'|sed -E 's#http://([^:]+):.*#\1#')
N1=${NODE_PRIVS[0]} N2=${NODE_PRIVS[1]} N3=${NODE_PRIVS[2]}
NODES="http://$N1:8080,http://$N2:8080,http://$N3:8080"
VUS_LIST="${1:-50 100 200 400 800}"; DUR="${2:-90s}"
LP=$N1; for p in "${NODE_PRIVS[@]}"; do SSH ubuntu@$MGMT "curl -s --max-time 3 http://$p:8080/health 2>/dev/null|grep -q LEADER" && { LP=$p; break; }; done
LPUB=$(awk -v p="$LP" '$4==p{print $3}' "$RD/hosts")
echo "############ saturation test — run=$RUN  leader=$LP  ramp=[$VUS_LIST] x $DUR  SLEEP_MS=0 ############"

# warmup (creates accounts + warms JIT), discarded
echo "warmup..."; SSH ubuntu@$MGMT "cd ~/jraft_ledger && k6 run --vus 20 --duration 25s -e SLEEP_MS=0 -e NODES=$NODES scripts/k6-posting-stress.js >/tmp/k6-warm.log 2>&1" >/dev/null 2>&1 || true

# cumulative timer sum/count for a metric on the leader → echoes "sum count"
snap(){ SSH ubuntu@$MGMT "curl -s http://$LP:8080/actuator/prometheus 2>/dev/null | grep -E '^$1_seconds_(sum|count)' | awk '{s[\$1~/sum/?\"sum\":\"cnt\"]+=\$2} END{print s[\"sum\"]+0, s[\"cnt\"]+0}'"; }
prom(){ SSH ubuntu@$MGMT "curl -s 'http://localhost:9090/api/v1/query?query=$1' 2>/dev/null | grep -oE '\"value\":\[[0-9.]+,\"[0-9.eE+-]+\"\]' | grep -oE '[0-9.eE+-]+\"\]$' | tr -d '\"]' | head -20"; }

printf "%6s %9s %9s %9s %9s %9s %8s %8s %9s\n" VU "TPS" "qwait" "commit" "apply" "post" "CPUidle" "qdepth" "runq"
for VU in $VUS_LIST; do
  read P0S P0C < <(snap ledger_posting_duration)
  read Q0S Q0C < <(snap ledger_command_queue_wait)
  read R0S R0C < <(snap ledger_raft_wait_apply)
  read A0S A0C < <(snap ledger_apply_total)
  SSH ubuntu@$LPUB "nohup mpstat 1 100 >/tmp/mp.out 2>&1 </dev/null & disown" >/dev/null
  # push k6 metrics to mgmt Prometheus so the Grafana "k6 Test Cycle" dashboard shows the load live
  SSH ubuntu@$MGMT "cd ~/jraft_ledger && K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99)' nohup k6 run --vus $VU --duration $DUR -o experimental-prometheus-rw --tag testid=sat-$VU -e SLEEP_MS=0 -e NODES=$NODES scripts/k6-posting-stress.js >/tmp/k6-sat-$VU.log 2>&1 </dev/null & disown; sleep 2; echo ok" >/dev/null
  # let it run, sample gauges mid-flight
  sleep 40
  QDEPTH=$(prom "ledger_command_queue_depth" | sort -rn | head -1)
  RUNQ=$(prom "max(node_procs_running)" | head -1)
  for i in $(seq 1 30); do SSH ubuntu@$MGMT "pgrep -f 'k6 run' >/dev/null" && sleep 4 || break; done
  read P1S P1C < <(snap ledger_posting_duration)
  read Q1S Q1C < <(snap ledger_command_queue_wait)
  read R1S R1C < <(snap ledger_raft_wait_apply)
  read A1S A1C < <(snap ledger_apply_total)
  DURS=$(echo "$DUR"|tr -d 's')
  CPUIDLE=$(SSH ubuntu@$LPUB "awk '/Average/&&/all/{print \$NF}' /tmp/mp.out | tail -1")
  python3 - "$P0S $P0C $P1S $P1C" "$Q0S $Q0C $Q1S $Q1C" "$R0S $R0C $R1S $R1C" "$A0S $A0C $A1S $A1C" "$VU" "$DURS" "${CPUIDLE:-}" "${QDEPTH:-}" "${RUNQ:-}" <<'PY'
import sys
def avg(s):
    p0s,p0c,p1s,p1c=map(float,s.split())
    dc=p1c-p0c; ds=p1s-p0s
    return (ds/dc*1000) if dc>0 else 0, dc
post_ms,dpost=avg(sys.argv[1]); qw,_=avg(sys.argv[2]); rw,_=avg(sys.argv[3]); ap,_=avg(sys.argv[4])
vu=sys.argv[5]; durs=float(sys.argv[6]); cpu=sys.argv[7]; qd=sys.argv[8]; rq=sys.argv[9]
tps=dpost/durs
print("%6s %9.0f %9.3f %9.3f %9.3f %9.3f %8s %8s %9s" % (vu, tps, qw, rw, ap, post_ms, cpu or "-", qd or "-", rq or "-"))
PY
done
echo "############ done. (qwait=queue backlog, commit=raft, apply=fsm; CPUidle low → CPU-bound; ############"
echo "############  qdepth/qwait grow + CPU idle → dispatcher or apply-thread saturated)            ############"
echo "############ sampled per-request lifecycle logs: docker logs ledger-node | grep posting-lifecycle ############"
echo SAT_DONE
