#!/bin/bash
# Chaos Test: Raft Network Partition — L5 Fault Injection
# Usage:
#   ./chaos-raft-partition.sh partition ledger-1   # isolate node 1
#   ./chaos-raft-partition.sh heal ledger-1        # restore node 1
#   ./chaos-raft-partition.sh partition-half       # split 1+2 vs 3
#   ./chaos-raft-partition.sh heal-all             # restore all

set -e

NETWORK="${LEDGER_NETWORK:-jraft_ledger_default}"
ACTION="${1:-}"
TARGET="${2:-}"

partition_node() {
  local node="$1"
  echo "[CHAOS] Partitioning $node from Raft network"
  docker network disconnect "$NETWORK" "$node" 2>/dev/null || true
}

heal_node() {
  local node="$1"
  echo "[CHAOS] Restoring $node to network"
  docker network connect "$NETWORK" "$node" 2>/dev/null || true
}

case "$ACTION" in
  partition)
    if [ -z "$TARGET" ]; then echo "Usage: $0 partition <node>"; exit 1; fi
    partition_node "$TARGET"
    ;;
  heal)
    if [ -z "$TARGET" ]; then echo "Usage: $0 heal <node>"; exit 1; fi
    heal_node "$TARGET"
    ;;
  partition-half)
    echo "[CHAOS] Split-brain: ledger-1 + ledger-2 vs ledger-3"
    partition_node "ledger-3"
    ;;
  partition-leader)
    echo "[CHAOS] Isolating current leader (assumed ledger-1)"
    partition_node "ledger-1"
    ;;
  heal-all)
    for n in ledger-1 ledger-2 ledger-3; do
      heal_node "$n"
    done
    ;;
  latency)
    # Requires toxiproxy running
    if [ -z "$TARGET" ]; then echo "Usage: $0 latency <millis>"; exit 1; fi
    for proxy in ledger-1-raft ledger-2-raft ledger-3-raft; do
      curl -s -X POST "http://localhost:8474/proxies/$proxy/toxics" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"latency\",\"type\":\"latency\",\"attributes\":{\"latency\":$TARGET,\"jitter\":10}}" >/dev/null || true
    done
    echo "[CHAOS] Added ${TARGET}ms latency to all Raft proxies"
    ;;
  reset-toxics)
    for proxy in ledger-1-raft ledger-2-raft ledger-3-raft; do
      curl -s -X DELETE "http://localhost:8474/proxies/$proxy/toxics/latency" >/dev/null || true
    done
    echo "[CHAOS] Cleared toxics"
    ;;
  *)
    echo "Usage: $0 {partition|heal|partition-half|partition-leader|heal-all|latency|reset-toxics} [target]"
    exit 1
    ;;
esac
