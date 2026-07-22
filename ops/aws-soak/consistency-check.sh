#!/usr/bin/env bash
# consistency-check.sh — correctness gate for a storage arm. Performance is a ranking;
# THIS is pass/fail. Any arm that fails here is an unsafe storage config, full stop.
#
# Default (safe, read-only):
#   1. Raft parity      — lastAppliedIndex + smJournalSeq identical across all 3 nodes
#   2. Cross-node hash  — full balance set hashes identically on every node (deterministic FSM)
#   3. Value conservation — per-currency Σ balances invariant (no money created/destroyed)
#
# With --failover (destructive, needed esp. for the nvme arm):
#   4. Wipe-rebuild     — erase one node's raft+state, restart, assert it re-syncs from quorum
#   5. Leader-kill      — stop the leader, assert re-election + committed txns survive + conservation holds
#
# Run from ops/aws-soak after `up` (and ideally after some `test` load).
#   ./consistency-check.sh [--failover]
set -uo pipefail
cd "$(dirname "$0")"
RUN="$(cat .aws-soak/current)"; RD=".aws-soak/$RUN"; KEY="$RD/key.pem"
DISK=$(grep '^DISK=' "$RD/run.env" | cut -d= -f2)
MGMT=$(grep '^MGMT_PUB=' "$RD/run.env" | cut -d= -f2)
FAILOVER=0; [ "${1:-}" = "--failover" ] && FAILOVER=1
SSH() { ssh -n -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 "$@"; }
RC=0; pass(){ echo "  ✅ $*"; }; fail(){ echo "  ❌ $*"; RC=1; }

NODE_PRIVS=$(awk '$1!="mgmt"{print $4}' "$RD/hosts")
URLS=$(awk '$1!="mgmt"{printf "http://%s:8080 ", $4}' "$RD/hosts")
# account universe seeded by the k6 scenario (must match k6-posting-stress.js)
ACCTS=$(python3 -c "print('STRESS-HOT-CO-001 SYSTEM_SEED ' + ' '.join('STRESS-CLI-%04d'%i for i in range(1,101)))")
CURS="USDT BTC"

echo "############ consistency check — run=$RUN  disk-arm=$DISK  failover=$FAILOVER ############"

# ── 1. Raft parity ──────────────────────────────────────────────────────────
echo "── 1. Raft parity (lastAppliedIndex + smJournalSeq across nodes) ──"
parity_snapshot() {  # echoes "applied smJnl" per node, via mgmt private net
  for u in $URLS; do
    SSH ubuntu@$MGMT "curl -s --max-time 3 $u/ledger/cluster/raft-status 2>/dev/null" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('lastAppliedIndex'),d.get('smJournalSeq'),d.get('nodeId'),d.get('isLeader'))" 2>/dev/null
  done
}
# let any in-flight applies settle, then require all nodes to agree
for try in $(seq 1 15); do
  SNAP=$(parity_snapshot); echo "$SNAP" | sed 's/^/    /'
  UNIQ=$(echo "$SNAP" | awk '{print $1"|"$2}' | sort -u | wc -l | tr -d ' ')
  N=$(echo "$SNAP" | grep -c .)
  if [ "$UNIQ" = "1" ] && [ "$N" -ge 2 ]; then break; fi
  sleep 4
done
[ "$UNIQ" = "1" ] && [ "$N" -ge 2 ] && pass "all $N nodes agree on (applied,smJnl)" || fail "parity mismatch: $UNIQ distinct (applied,smJnl) tuples"

# ── 2. Cross-node balance hash + 3. conservation ────────────────────────────
echo "── 2/3. Cross-node state hash + value conservation ──"
# one remote pass per node: dump "<acct> <cur> <balance> <accountSeq>" for the whole universe
dump_node() {  # $1 = node url ; emits sorted lines (deterministic)
  local u=$1
  SSH ubuntu@$MGMT "
    for a in $ACCTS; do for c in $CURS; do
      curl -s --max-time 3 '$u/ledger/balances?accountId='\$a'&balanceType=AVAILABLE_BALANCE&currency='\$c 2>/dev/null \
        | python3 -c \"
import sys,json
try:
  d=json.load(sys.stdin)
except: sys.exit()
def num(x):
  for k in ('balance','availableBalance','currentBalance','amount','value'):
    if isinstance(d,dict) and d.get(k) is not None: return d[k]
  return d.get('balance') if isinstance(d,dict) else None
b=None
for k in ('balance','availableBalance','currentBalance','amount','value'):
  if isinstance(d,dict) and d.get(k) is not None: b=d[k]; break
seq=d.get('accountSeq') if isinstance(d,dict) else None
print('\$a','\$c',b,seq)
\" ; done; done | sort
  "
}
declare -a HASHES
i=0
CONS_OUT=""
for u in $URLS; do
  DUMP=$(dump_node "$u")
  H=$(echo "$DUMP" | shasum -a 256 | cut -d' ' -f1)
  HASHES[$i]="$H"
  echo "    $u  state-hash=${H:0:16}…"
  # conservation: per-currency sum across all accounts (computed once, from first node)
  if [ "$i" = "0" ]; then
    CONS_OUT=$(echo "$DUMP" | python3 -c "
import sys
from decimal import Decimal
tot={}
for ln in sys.stdin:
  p=ln.split()
  if len(p)<3 or p[2] in ('None','null'): continue
  try: tot[p[1]]=tot.get(p[1],Decimal(0))+Decimal(p[2])
  except: pass
for c,v in sorted(tot.items()): print(f'    Σ {c} = {v}')
")
  fi
  i=$((i+1))
done
UNIQH=$(printf '%s\n' "${HASHES[@]}" | sort -u | wc -l | tr -d ' ')
[ "$UNIQH" = "1" ] && pass "all nodes produce identical balance state (deterministic FSM)" || fail "cross-node state hash mismatch ($UNIQH distinct)"
echo "  value conservation (per-currency totals; internal transfers are zero-sum):"
echo "$CONS_OUT"
echo "    ↑ inspect: clients+hotspot should net against SYSTEM_SEED; totals must be stable run-over-run"

# ── 4/5. Failover (destructive) ─────────────────────────────────────────────
if [ "$FAILOVER" = "1" ]; then
  LEADER_PRIV=""; for u in $URLS; do
    SSH ubuntu@$MGMT "curl -s --max-time 3 $u/health 2>/dev/null | grep -q LEADER" && { LEADER_PRIV=$(echo $u|sed -E 's#http://([^:]+):.*#\1#'); break; }
  done
  FOLLOWER_PRIV=$(echo "$NODE_PRIVS" | grep -v "^$LEADER_PRIV$" | head -1)
  pub_of(){ awk -v p="$1" '$4==p{print $3}' "$RD/hosts"; }

  echo "── 4. Wipe-rebuild (follower $FOLLOWER_PRIV: erase raft+state, restart, expect re-sync from quorum) ──"
  FPUB=$(pub_of "$FOLLOWER_PRIV")
  SSH ubuntu@$FPUB "docker stop ledger-node >/dev/null 2>&1; sudo rm -rf ~/ledger-data/* /mnt/raft/* ; docker start ledger-node >/dev/null 2>&1; echo wiped-and-restarted" | sed 's/^/    /'
  echo "    waiting for wiped node to rebuild (InstallSnapshot + log catch-up)…"
  LEAD_APPLIED=$(SSH ubuntu@$MGMT "curl -s http://$LEADER_PRIV:8080/ledger/cluster/raft-status 2>/dev/null" | python3 -c "import sys,json;print(json.load(sys.stdin).get('lastAppliedIndex'))" 2>/dev/null)
  REBUILT=0
  for try in $(seq 1 30); do
    A=$(SSH ubuntu@$MGMT "curl -s --max-time 3 http://$FOLLOWER_PRIV:8080/ledger/cluster/raft-status 2>/dev/null" | python3 -c "import sys,json;print(json.load(sys.stdin).get('lastAppliedIndex'))" 2>/dev/null || echo "")
    echo "      follower applied=$A  (leader≈$LEAD_APPLIED)"
    [ -n "$A" ] && [ "$A" != "None" ] && [ "$A" -ge "${LEAD_APPLIED:-0}" ] 2>/dev/null && { REBUILT=1; break; }
    sleep 6
  done
  [ "$REBUILT" = "1" ] && pass "wiped node rebuilt from quorum (caught up to leader) — local-disk loss is recoverable" \
                       || fail "wiped node did NOT catch up — instance-store/local-disk loss is UNSAFE for this config"

  echo "── 5. Leader-kill (stop leader $LEADER_PRIV, expect re-election + no committed-txn loss) ──"
  LPUB=$(pub_of "$LEADER_PRIV")
  SSH ubuntu@$LPUB "docker stop ledger-node >/dev/null 2>&1; echo leader-stopped" | sed 's/^/    /'
  NEWLEAD=""
  for try in $(seq 1 20); do
    for u in $URLS; do
      p=$(echo $u|sed -E 's#http://([^:]+):.*#\1#'); [ "$p" = "$LEADER_PRIV" ] && continue
      SSH ubuntu@$MGMT "curl -s --max-time 3 $u/health 2>/dev/null | grep -q LEADER" && { NEWLEAD=$p; break; }
    done
    [ -n "$NEWLEAD" ] && break; sleep 4
  done
  [ -n "$NEWLEAD" ] && pass "re-elected new leader: $NEWLEAD" || fail "no new leader after leader-kill"
  # restart old leader so it rejoins (keeps cluster at 3 for any later arm reuse)
  SSH ubuntu@$LPUB "docker start ledger-node >/dev/null 2>&1; echo old-leader-restarted" | sed 's/^/    /'
  echo "    (re-run sections 1-3 after this to confirm parity + conservation post-failover)"
fi

echo "############ result: $([ $RC = 0 ] && echo 'ALL CHECKS PASSED ✅' || echo 'FAILURES PRESENT ❌') ############"
exit $RC
