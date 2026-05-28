#!/usr/bin/env bash
# Launch 3 ledger JVMs on localhost — no Docker for the ledger nodes.
# Mysql + Kafka still in Docker (compose up -d mysql kafka).
#
# Bench-mode env flags match docker-compose.bench.yml:
#   LEDGER_SKIP_PERSIST=1  LEDGER_SKIP_EVENTS=1  RAFT_APPLY_BATCH=128
#   RAFT_MAX_INFLIGHT=1024  LEDGER_RAFT_SUBMIT_TIMEOUT_MS=5000
#
# Each node runs under nohup and writes logs to logs/local-cluster/node-N.log.

set -euo pipefail
cd "$(dirname "$0")/.."

JAR="ledger-restful/target/ledger-restful-0.1.0-SNAPSHOT.jar"
[[ -f "$JAR" ]] || { echo "JAR not found: $JAR — run: JAVA_HOME=~/.sdkman/candidates/java/21.0.2-open mvn -DskipTests package" >&2; exit 1; }

JAVA=$HOME/.sdkman/candidates/java/21.0.2-open/bin/java
[[ -x "$JAVA" ]] || { echo "Java 21 not found at $JAVA" >&2; exit 1; }
"$JAVA" -version 2>&1 | head -1 | grep -q '"21' || { echo "Wrong Java: $($JAVA -version 2>&1 | head -1)" >&2; exit 1; }

mkdir -p logs/local-cluster
mkdir -p /tmp/jraft-ledger/node-1/rocksdb /tmp/jraft-ledger/node-1/raft
mkdir -p /tmp/jraft-ledger/node-2/rocksdb /tmp/jraft-ledger/node-2/raft
mkdir -p /tmp/jraft-ledger/node-3/rocksdb /tmp/jraft-ledger/node-3/raft

JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+ExitOnOutOfMemoryError"
PEERS="localhost:28081,localhost:28082,localhost:28083"

# Bench-mode env (override by exporting before running this script).
export LEDGER_SKIP_PERSIST=${LEDGER_SKIP_PERSIST:-1}
export LEDGER_SKIP_EVENTS=${LEDGER_SKIP_EVENTS:-1}
export LEDGER_PER_APPLY_SNAPSHOT=${LEDGER_PER_APPLY_SNAPSHOT:-0}
export RAFT_APPLY_BATCH=${RAFT_APPLY_BATCH:-128}
export RAFT_MAX_INFLIGHT=${RAFT_MAX_INFLIGHT:-1024}
export RAFT_LOG_SYNC=${RAFT_LOG_SYNC:-true}
export LEDGER_RAFT_SUBMIT_TIMEOUT_MS=${LEDGER_RAFT_SUBMIT_TIMEOUT_MS:-5000}

# Mysql is in docker, exposed on localhost:3306.
export SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:mysql://localhost:3306/ledger_view}
# Kafka is in docker, exposed on localhost:9092.
export KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}

launch() {
    local id=$1 http=$2 raft=$3
    local log="logs/local-cluster/node-${id}.log"
    echo ">>> node-$id http=:$http raft=:$raft log=$log"
    nohup env \
        SERVER_PORT=$http \
        RAFT_SERVER_PORT=$raft \
        NODE_ID=ledger-node-$id \
        PEER_NODES="$PEERS" \
        LEDGER_ROCKSDB_PATH=/tmp/jraft-ledger/node-$id/rocksdb \
        LEDGER_RAFT_DATA_PATH=/tmp/jraft-ledger/node-$id/raft \
        LEDGER_ADVERTISE_URL=http://localhost:$http \
        KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
        SPRING_DATASOURCE_URL="$SPRING_DATASOURCE_URL" \
        LEDGER_SKIP_PERSIST="$LEDGER_SKIP_PERSIST" \
        LEDGER_SKIP_EVENTS="$LEDGER_SKIP_EVENTS" \
        LEDGER_PER_APPLY_SNAPSHOT="$LEDGER_PER_APPLY_SNAPSHOT" \
        RAFT_APPLY_BATCH="$RAFT_APPLY_BATCH" \
        RAFT_MAX_INFLIGHT="$RAFT_MAX_INFLIGHT" \
        RAFT_LOG_SYNC="$RAFT_LOG_SYNC" \
        LEDGER_RAFT_SUBMIT_TIMEOUT_MS="$LEDGER_RAFT_SUBMIT_TIMEOUT_MS" \
        "$JAVA" $JAVA_OPTS -jar "$JAR" \
        > "$log" 2>&1 &
    echo $! > logs/local-cluster/node-$id.pid
    sleep 0.5
}

launch 1 8081 28081
launch 2 8082 28082
launch 3 8083 28083

echo ""
echo "Waiting for leader..."
for i in $(seq 1 60); do
    for n in 8081 8082 8083; do
        if curl -fs -m 1 "http://localhost:$n/health" 2>/dev/null | grep -q LEADER; then
            curl -fs http://localhost:$n/health | python3 -m json.tool || true
            echo ""
            echo "Cluster up. Stop with: scripts/stop-local-cluster.sh"
            exit 0
        fi
    done
    sleep 2
done
echo "No leader after 120s" >&2
exit 1
