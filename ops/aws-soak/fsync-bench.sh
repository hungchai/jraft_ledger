#!/usr/bin/env bash
# fsync-bench.sh — isolate the Raft-log disk's fdatasync latency (fio) and CPU steal
# (mpstat) on every ledger node of the active run. Run from ops/aws-soak after `up`.
#
# Answers: "is the ~3.58ms quorum-commit cost the DISK (gp3 fsync slow) or the CPU
# (c7i-flex throttling) or neither (quorum structure)?" — by measuring the raft disk
# (/mnt/raft) directly, independent of the cluster.
#
#   ./fsync-bench.sh            # bench all raft nodes
set -uo pipefail
cd "$(dirname "$0")"
RUN="$(cat .aws-soak/current)"; RD=".aws-soak/$RUN"; KEY="$RD/key.pem"
DISK=$(grep '^DISK=' "$RD/run.env" | cut -d= -f2)
# Remote script is fed over stdin via a QUOTED heredoc → no local expansion of $NF/$i etc.
SSH_OPTS=(-i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20)

echo "############ fsync bench — run=$RUN  disk-arm=$DISK ############"
echo "(fio: 4k, iodepth=1, fdatasync=1 — one durable write at a time, mirrors Raft WAL append)"
echo

while read -r name id pub priv; do
  [ "$name" = "mgmt" ] && continue
  echo "===== $name ($pub) ====="
  ssh "${SSH_OPTS[@]}" "ubuntu@$pub" 'bash -s' <<'REMOTE'
set -uo pipefail
command -v fio    >/dev/null || { sudo apt-get update -q >/dev/null 2>&1; sudo apt-get install -y fio >/dev/null 2>&1; }
command -v mpstat >/dev/null || sudo apt-get install -y sysstat >/dev/null 2>&1
echo -n '  mount: '; findmnt -no SOURCE,FSTYPE,TARGET /mnt/raft 2>/dev/null || echo '(/mnt/raft on root vol)'
sudo mkdir -p /mnt/raft/fiotest; sudo chown "$(id -u):$(id -g)" /mnt/raft/fiotest

# mpstat in background to catch CPU steal WHILE fio runs (steal = hypervisor throttle)
mpstat 1 12 > /tmp/mpstat.out 2>/dev/null &
MPID=$!
sudo fio --name=raftfsync --directory=/mnt/raft/fiotest --rw=write --bs=4k \
  --size=256M --iodepth=1 --fdatasync=1 --runtime=10 --time_based \
  --output-format=json 2>/dev/null > /tmp/fio.json
wait $MPID 2>/dev/null

python3 - <<'PY'
import json
d=json.load(open('/tmp/fio.json'))
j=d['jobs'][0]
s=j.get('sync',{}).get('lat_ns',{})
pct=s.get('percentile',{})
def ms(ns):
    try: return round(float(ns)/1e6,3)
    except: return None
print('  fdatasync latency (ms):  mean=%s  p50=%s  p95=%s  p99=%s  max=%s' % (
    ms(s.get('mean')), ms(pct.get('50.000000')), ms(pct.get('95.000000')),
    ms(pct.get('99.000000')), ms(s.get('max'))))
print('  fsync IOPS: %s' % round(j.get('sync',{}).get('iops',0),1))
PY

echo -n '  CPU under load (avg %steal / %idle): '
awk '/Average/ && /all/ {print "steal="$(NF-1)"  idle="$NF}' /tmp/mpstat.out
sudo rm -rf /mnt/raft/fiotest
REMOTE
  echo
done < "$RD/hosts"
echo "############ interpretation ############"
echo "  fsync p50 ~0.1-0.2ms  → local NVMe / fast io2"
echo "  fsync p50 ~0.3-0.6ms  → healthy gp3 (disk is NOT the bottleneck)"
echo "  fsync p50 ~1-2ms      → slow/throttled gp3 — disk IS a real cost"
echo "  %steal > ~2-5%        → CPU is throttled (c7i-flex burst exhausted) — inflates everything"
