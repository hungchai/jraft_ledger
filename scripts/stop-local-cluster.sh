#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

for f in logs/local-cluster/node-*.pid; do
    [[ -f "$f" ]] || continue
    pid=$(cat "$f")
    if kill -0 "$pid" 2>/dev/null; then
        echo "kill $pid (from $f)"
        kill "$pid" || true
    fi
    rm -f "$f"
done

# fallback: kill anything still bound to our raft ports
for port in 28081 28082 28083; do
    pid=$(lsof -ti :$port 2>/dev/null || true)
    [[ -n "$pid" ]] && { echo "lsof kill $pid on :$port"; kill -9 $pid 2>/dev/null || true; }
done
