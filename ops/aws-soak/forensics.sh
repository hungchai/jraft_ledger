#!/usr/bin/env bash
# Periodic in-vivo forensics for a ledger node under soak. Kernel OOM kills leave no
# dumps (SIGKILL, no JVM hook) — rolling snapshots are the only usable post-mortem.
# Captures every 5min into /var/log/ledger-forensics/ (ring of last 48 = 4h):
#   - MemAvailable + container RSS
#   - jcmd VM.native_memory summary (needs -XX:NativeMemoryTracking=summary)
#   - jmap -histo top 20 (no :live — avoids a full GC)
# Usage: nohup ./forensics.sh >/dev/null 2>&1 &
set -u
OUT=/var/log/ledger-forensics
sudo mkdir -p "$OUT"; sudo chown "$(whoami)" "$OUT"
while true; do
  TS=$(date -u +%Y%m%dT%H%M%S)
  F="$OUT/$TS.txt"
  {
    echo "== $TS host memory:"
    grep -E 'MemAvailable|Dirty' /proc/meminfo
    echo "== container RSS:"
    docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' 2>/dev/null | head -3
    echo "== NMT:"
    docker exec ledger-node jcmd 1 VM.native_memory summary 2>/dev/null | grep -E 'Total|Java Heap|Class|Thread|Code|GC|Internal|Other|Symbol' | head -12
    echo "== heap histo top:"
    docker exec ledger-node jmap -histo 1 2>/dev/null | head -20
  } > "$F" 2>&1
  ls -t "$OUT" | tail -n +49 | while read -r old; do rm -f "$OUT/$old"; done
  sleep 300
done
