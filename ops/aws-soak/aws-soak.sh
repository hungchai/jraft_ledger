#!/usr/bin/env bash
#
# aws-soak.sh — single-entrypoint, on-demand jraft_ledger soak harness on AWS.
#
# Provisions a fresh multi-host cluster (3 Raft nodes + 1 mgmt/observability+driver),
# deploys jraft_ledger in no-Kafka lean mode, runs a load test, collects results, and
# tears everything down. Does NOT touch scripts/test-cycle.sh (that one is single-host);
# the `test` subcommand runs the multi-host analog (k6 from mgmt + cross-node recon).
#
# ── Credentials (env vars, never written by this script except into the AWS CLI cache) ──
#   export AWS_ACCESS_KEY_ID=...
#   export AWS_SECRET_ACCESS_KEY=...
#   export AWS_SESSION_TOKEN=...        # optional (recommended: STS short-lived)
#
# ── Usage ──
#   ./aws-soak.sh up        [--nodes 3] [--node-type c7i-flex.large] [--mgmt-type c7i-flex.large]
#                           [--branch v3-fix] [--ttl-hours 6]
#   ./aws-soak.sh test      [--vus 10] [--duration 2m] [--scenario k6-posting-stress]
#   ./aws-soak.sh status
#   ./aws-soak.sh collect                       # pull k6 summary + prometheus snapshot to ./results/<run>/
#   ./aws-soak.sh ssh  node-1|node-2|node-3|mgmt
#   ./aws-soak.sh down                          # terminate THIS run (by tag) + delete SG/key
#   ./aws-soak.sh sweep                         # terminate ALL Project=jraft-soak (safety net)
#
# State for the active run is kept in ./.aws-soak/<run-id>/ (instance ids, ips, key pem).
#
set -euo pipefail

# ────────────────────────────── config / defaults ──────────────────────────────
REGION="${AWS_REGION:-ap-southeast-1}"
PROJECT_TAG="jraft-soak"
REPO_URL="${REPO_URL:-https://github.com/hungchai/jraft_ledger.git}"
BRANCH="${BRANCH:-v3-fix}"
NODES=3
NODE_TYPE="c7i-flex.large"
MGMT_TYPE="c7i-flex.large"
ROOT_GB=30
TTL_HOURS=6                      # failsafe: instances self-shutdown after this many hours
VUS=10
DURATION="2m"
SCENARIO="k6-posting-stress"
SSH_USER="ubuntu"
RAFT_PORT=28080
HTTP_PORT=8080
GRAFANA_PORT=3000
PROM_PORT=9090
STATE_ROOT="$(cd "$(dirname "$0")" && pwd)/.aws-soak"

AWSCLI=(aws --region "$REGION" --output json)   # uses creds from env

log()  { echo -e "\033[0;36m[$(date +%H:%M:%S)]\033[0m $*"; }
ok()   { echo -e "  \033[0;32m✅ $*\033[0m"; }
warn() { echo -e "  \033[1;33m⚠️  $*\033[0m"; }
die()  { echo -e "\033[0;31m❌ $*\033[0m" >&2; exit 1; }

# ────────────────────────────── preflight ──────────────────────────────
preflight() {
  command -v aws >/dev/null || die "aws CLI not found. Install awscli v2."
  command -v jq  >/dev/null || die "jq not found. Install jq."
  command -v ssh >/dev/null || die "ssh not found."
  [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ] \
    || die "Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (env)."
  "${AWSCLI[@]}" sts get-caller-identity >/dev/null 2>&1 \
    || die "AWS credentials invalid / no access in region $REGION."
  ok "AWS identity: $("${AWSCLI[@]}" sts get-caller-identity --query Arn --output text)"
}

my_ip() { curl -fsS https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]'; }

ubuntu2404_ami() {
  "${AWSCLI[@]}" ec2 describe-images --owners 099720109477 \
    --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*" \
              "Name=state,Values=available" "Name=architecture,Values=x86_64" \
    --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text 2>/dev/null \
  || "${AWSCLI[@]}" ec2 describe-images --owners 099720109477 \
       --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-noble-24.04-amd64-server-*" \
                 "Name=state,Values=available" \
       --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text
}

default_subnet() {
  "${AWSCLI[@]}" ec2 describe-subnets \
    --filters "Name=default-for-az,Values=true" \
    --query 'Subnets[0].SubnetId' --output text
}
subnet_vpc() { "${AWSCLI[@]}" ec2 describe-subnets --subnet-ids "$1" --query 'Subnets[0].VpcId' --output text; }

# ────────────────────────────── state helpers ──────────────────────────────
runid_file() { echo "$STATE_ROOT/current"; }
current_run() { cat "$(runid_file)" 2>/dev/null || die "No active run. Run 'up' first."; }
run_dir() { echo "$STATE_ROOT/$1"; }
kv_get() { grep -E "^$2=" "$(run_dir "$1")/run.env" 2>/dev/null | cut -d= -f2-; }
# UserKnownHostsFile=/dev/null + StrictHostKeyChecking=no: public IPs get reused across runs, so a
# stale known_hosts entry would otherwise fail host-key verification on a later run.
SSHK() { echo "-i $(run_dir "$1")/key.pem -o IdentitiesOnly=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 -o ServerAliveInterval=10"; }
# The `-n` matters: rssh runs inside `while read ... < hosts` loops. Without it, ssh reads its stdin
# from the loop's hosts file and swallows the remaining lines → only the first host is processed
# (the bug that left only node-1 launched / node-2 shipped). -n ties ssh stdin to /dev/null.
rssh() { local run=$1 ip=$2; shift 2; ssh -n $(SSHK "$run") "$SSH_USER@$ip" "$@"; }

# ────────────────────────────── up ──────────────────────────────
cmd_up() {
  preflight
  local RUN="soak-$(date +%Y%m%d-%H%M%S)"
  local RD; RD="$(run_dir "$RUN")"; mkdir -p "$RD"; echo "$RUN" > "$(runid_file)"
  # Ingress CIDR for ssh/obs/http. Default = detected public IP /32. Override with --ssh-cidr (or
  # SSH_CIDR env) when your raw-TCP egress differs from your HTTPS egress (NAT/proxy/corp net make
  # checkip report a different address than your real ssh source — pass your real CIDR, or 0.0.0.0/0).
  local IP CIDR
  if [ -n "${SSH_CIDR:-}" ]; then
    CIDR="$SSH_CIDR"
  else
    IP="$(my_ip)"; [ -n "$IP" ] || die "Could not detect your public IP. Pass --ssh-cidr <your-cidr> (e.g. 0.0.0.0/0)."
    CIDR="$IP/32"
  fi
  local AMI; AMI="$(ubuntu2404_ami)"; [ "$AMI" != "None" ] || die "No Ubuntu 24.04 AMI found."
  local SUBNET; SUBNET="$(default_subnet)"; [ "$SUBNET" != "None" ] || die "No default subnet."
  local VPC; VPC="$(subnet_vpc "$SUBNET")"
  log "Run=$RUN  AMI=$AMI  subnet=$SUBNET  vpc=$VPC  ingress-cidr=$CIDR"

  # --- key pair ---
  local KEY="${PROJECT_TAG}-${RUN}"
  "${AWSCLI[@]}" ec2 create-key-pair --key-name "$KEY" \
    --tag-specifications "ResourceType=key-pair,Tags=[{Key=Project,Value=$PROJECT_TAG},{Key=Run,Value=$RUN}]" \
    --query 'KeyMaterial' --output text > "$RD/key.pem"
  chmod 600 "$RD/key.pem"; ok "key pair $KEY"

  # --- security group ---
  local SG; SG="$("${AWSCLI[@]}" ec2 create-security-group --group-name "${PROJECT_TAG}-${RUN}" \
    --description "jraft soak $RUN" --vpc-id "$VPC" \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=Project,Value=$PROJECT_TAG},{Key=Run,Value=$RUN}]" \
    --query 'GroupId' --output text)"
  # intra-SG: Raft + HTTP between cluster members
  "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
    "IpProtocol=tcp,FromPort=$RAFT_PORT,ToPort=$RAFT_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=raft}]" \
    "IpProtocol=tcp,FromPort=$HTTP_PORT,ToPort=$HTTP_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=http-intra}]" >/dev/null
  # from your IP only: ssh + observability + http (so you can curl/k6 from elsewhere if needed)
  "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
    "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$CIDR,Description=ssh}]" \
    "IpProtocol=tcp,FromPort=$HTTP_PORT,ToPort=$HTTP_PORT,IpRanges=[{CidrIp=$CIDR,Description=http-you}]" \
    "IpProtocol=tcp,FromPort=$GRAFANA_PORT,ToPort=$GRAFANA_PORT,IpRanges=[{CidrIp=$CIDR,Description=grafana}]" \
    "IpProtocol=tcp,FromPort=$PROM_PORT,ToPort=$PROM_PORT,IpRanges=[{CidrIp=$CIDR,Description=prometheus}]" >/dev/null
  ok "security group $SG (raft/http intra-SG; 22/3000/9090/8080 from $CIDR)"

  # --- launch instances ---
  local TTL_EPOCH=$(( $(date +%s) + TTL_HOURS*3600 ))
  local BDM="DeviceName=/dev/sda1,Ebs={VolumeSize=$ROOT_GB,VolumeType=gp3,DeleteOnTermination=true}"
  launch_one() { # role name type
    "${AWSCLI[@]}" ec2 run-instances --image-id "$AMI" --instance-type "$3" --count 1 \
      --key-name "$KEY" --security-group-ids "$SG" --subnet-id "$SUBNET" --associate-public-ip-address \
      --block-device-mappings "$BDM" \
      --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=$PROJECT_TAG},{Key=Run,Value=$RUN},{Key=Role,Value=$1},{Key=Name,Value=$2},{Key=TTL,Value=$TTL_EPOCH}]" \
      --query 'Instances[0].InstanceId' --output text
  }
  : > "$RD/instances"
  for i in $(seq 1 "$NODES"); do echo "node-$i $(launch_one node "${PROJECT_TAG}-$RUN-node-$i" "$NODE_TYPE")" >> "$RD/instances"; done
  echo "mgmt $(launch_one mgmt "${PROJECT_TAG}-$RUN-mgmt" "$MGMT_TYPE")" >> "$RD/instances"
  ok "launched $((NODES+1)) instances"

  # --- wait running + collect IPs ---
  local IDS; IDS=$(awk '{print $2}' "$RD/instances" | tr '\n' ' ')
  "${AWSCLI[@]}" ec2 wait instance-running --instance-ids $IDS
  : > "$RD/hosts"
  while read -r name id; do
    local pub priv
    pub=$("${AWSCLI[@]}" ec2 describe-instances --instance-ids "$id" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
    priv=$("${AWSCLI[@]}" ec2 describe-instances --instance-ids "$id" --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)
    echo "$name $id $pub $priv" >> "$RD/hosts"
  done < "$RD/instances"
  {
    echo "RUN=$RUN"; echo "REGION=$REGION"; echo "SG=$SG"; echo "KEY=$KEY"
    echo "PEERS=$(awk '$1!="mgmt"{printf "%s%s:'"$RAFT_PORT"'",sep,$4; sep=","}' "$RD/hosts")"
    echo "NODE_URLS=$(awk '$1!="mgmt"{printf "%shttp://%s:'"$HTTP_PORT"'",sep,$4; sep=","}' "$RD/hosts")"
    echo "MGMT_PUB=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")"
    echo "MGMT_PRIV=$(awk '$1=="mgmt"{print $4}' "$RD/hosts")"
  } > "$RD/run.env"
  cat "$RD/hosts" | sed 's/^/  /'

  wait_ssh "$RUN"
  install_all "$RUN"
  deploy_cluster "$RUN"
  deploy_obs "$RUN"
  verify_cluster "$RUN"

  local MGMT_PUB; MGMT_PUB="$(kv_get "$RUN" MGMT_PUB)"
  echo
  ok "Cluster UP (run $RUN). Grafana: http://$MGMT_PUB:$GRAFANA_PORT (admin/admin123)  Prometheus: http://$MGMT_PUB:$PROM_PORT"
  echo "  Run a test:   $0 test --vus $VUS --duration $DURATION"
  echo "  Tear down:    $0 down"
}

wait_ssh() {
  local run=$1; log "Waiting for SSH on all hosts..."
  while read -r name id pub priv; do
    for i in $(seq 1 30); do
      rssh "$run" "$pub" 'echo ok' >/dev/null 2>&1 && { ok "ssh $name ($pub)"; break; }
      [ "$i" = 30 ] && die "SSH timeout for $name ($pub)"; sleep 6
    done
  done < "$(run_dir "$run")/hosts"
}

install_all() {
  local run=$1 ttl_min=$(( TTL_HOURS*60 ))
  log "Installing Docker (+ k6 on mgmt), cloning repo@$BRANCH, setting TTL shutdown..."
  while read -r name id pub priv; do
    ( rssh "$run" "$pub" "
        set -e
        sudo shutdown -h +$ttl_min >/dev/null 2>&1 || true   # TTL failsafe
        if ! command -v docker >/dev/null; then curl -fsSL https://get.docker.com | sudo sh >/dev/null 2>&1; sudo usermod -aG docker $SSH_USER; sudo systemctl enable --now docker; fi
        test -d jraft_ledger/.git || git clone -q $REPO_URL
        cd jraft_ledger && git fetch -q origin && git checkout -q $BRANCH && git pull -q
        if [ '$name' = 'mgmt' ] && ! command -v k6 >/dev/null; then
          curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg 2>/dev/null
          echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list >/dev/null
          sudo apt-get update -q >/dev/null 2>&1 && sudo apt-get install -y k6 >/dev/null 2>&1
        fi
        echo '$name ready'
      " 2>&1 | tail -1 | sed 's/^/  /' ) &
  done < "$(run_dir "$run")/hosts"
  wait; ok "install complete"
}

deploy_cluster() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  local node1_pub node1_priv; node1_pub=$(awk '$1=="node-1"{print $3}' "$RD/hosts"); node1_priv=$(awk '$1=="node-1"{print $4}' "$RD/hosts")
  local PEERS; PEERS="$(kv_get "$run" PEERS)"

  # build image once on node-1, ship to the other nodes over the private network
  log "Building image on node-1 (maven-in-docker, ~5-10min)..."
  rssh "$run" "$node1_pub" "cd jraft_ledger && docker build -q -t ledger-node:latest -f Dockerfile . >/dev/null && echo built" | sed 's/^/  /'
  # push key to node-1 temporarily so it can scp the image to peers over private IPs (fast intra-AWS)
  scp $(SSHK "$run") "$RD/key.pem" "$SSH_USER@$node1_pub:~/.ssh/soak.pem" >/dev/null
  rssh "$run" "$node1_pub" "chmod 600 ~/.ssh/soak.pem"
  while read -r name id pub priv; do
    if [ "$name" = "node-1" ] || [ "$name" = "mgmt" ]; then continue; fi
    log "Shipping image node-1 → $name ($priv)..."
    rssh "$run" "$node1_pub" "docker save ledger-node:latest | gzip -1 | ssh -i ~/.ssh/soak.pem -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new $SSH_USER@$priv 'gunzip | docker load' >/dev/null && echo shipped" | sed 's/^/  /'
  done < "$RD/hosts"
  rssh "$run" "$node1_pub" "rm -f ~/.ssh/soak.pem"   # remove temp key

  # launch each node: --network host, private-IP raft peers, no-Kafka, add-opens, chown 999 data
  local JOPTS='-Xms1g -Xmx2g -XX:+UseZGC -XX:MaxGCPauseMillis=5 -Dmanagement.endpoints.web.exposure.include=health,prometheus,metrics,info -Dmanagement.metrics.export.prometheus.enabled=true --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED'
  while read -r name id pub priv; do
    if [ "$name" = "mgmt" ]; then continue; fi
    rssh "$run" "$pub" "
      docker rm -f ledger-node >/dev/null 2>&1 || true
      sudo rm -rf ~/ledger-data; mkdir -p ~/ledger-data; sudo chown -R 999:999 ~/ledger-data
      docker run -d --name ledger-node --restart unless-stopped --network host \
        -e NODE_ID=$priv -e PEER_NODES=$PEERS -e RAFT_SERVER_PORT=$RAFT_PORT \
        -e LEDGER_ADVERTISE_URL=http://$priv:$HTTP_PORT \
        -e LEDGER_RAFT_DATA_PATH=/var/lib/ledger/raft -e LEDGER_ROCKSDB_PATH=/var/lib/ledger/rocksdb \
        -e KAFKA_BOOTSTRAP_SERVERS=kafka.invalid:9092 -e LEDGER_KAFKA_REQUIRED=false \
        -e JAVA_OPTS='$JOPTS' -v /home/$SSH_USER/ledger-data:/var/lib/ledger ledger-node:latest >/dev/null && echo '$name launched'
    " | sed 's/^/  /'
  done < "$RD/hosts"
  ok "cluster containers launched"
}

deploy_obs() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  local mgmt_pub; mgmt_pub=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")
  local targets; targets=$(awk '$1!="mgmt"{printf "%s\"%s:'"$HTTP_PORT"'\"",sep,$4; sep=","}' "$RD/hosts")
  log "Deploying Prometheus + Grafana on mgmt..."
  rssh "$run" "$mgmt_pub" "mkdir -p ~/obs && cat > ~/obs/prometheus.yml <<EOF
global: { scrape_interval: 10s }
scrape_configs:
  - job_name: ledger-nodes
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [$targets]
        labels: { app: ledger, component: state-machine }
  - job_name: prometheus
    static_configs: [{ targets: ['localhost:9090'] }]
EOF
cat > ~/obs/docker-compose.yml <<EOF
services:
  prometheus:
    image: prom/prometheus:latest
    container_name: ledger-prometheus
    command: ['--config.file=/etc/prometheus/prometheus.yml','--storage.tsdb.path=/prometheus','--web.enable-remote-write-receiver']
    ports: ['$PROM_PORT:9090']
    volumes: ['~/obs/prometheus.yml:/etc/prometheus/prometheus.yml:ro']
    restart: unless-stopped
  grafana:
    image: grafana/grafana:latest
    container_name: ledger-grafana
    ports: ['$GRAFANA_PORT:3000']
    environment: { GF_SECURITY_ADMIN_PASSWORD: admin123, GF_USERS_ALLOW_SIGN_UP: 'false' }
    volumes: ['~/jraft_ledger/grafana/provisioning:/etc/grafana/provisioning:ro']
    restart: unless-stopped
EOF
cd ~/obs && docker compose up -d >/dev/null 2>&1 && echo 'obs up'" | sed 's/^/  /'
  ok "observability up"
}

verify_cluster() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  log "Waiting for leader election..."
  local urls; urls="$(kv_get "$run" NODE_URLS | tr ',' ' ')"
  # query via mgmt (private net) to avoid public-IP dependence
  local mgmt_pub; mgmt_pub=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")
  for i in $(seq 1 30); do
    for u in $urls; do
      rssh "$run" "$mgmt_pub" "curl -s --max-time 3 $u/health 2>/dev/null | grep -q LEADER" && { ok "leader: $u"; return 0; }
    done; sleep 6
  done
  warn "No leader after 180s — check '$0 status' / node logs"
}

# ────────────────────────────── test (multi-host analog of test-cycle) ──────────────────────────────
cmd_test() {
  local run; run="$(current_run)"; local RD; RD="$(run_dir "$run")"
  local mgmt_pub; mgmt_pub=$(kv_get "$run" MGMT_PUB)
  local node_urls; node_urls="$(kv_get "$run" NODE_URLS)"
  local testid="t-$(date +%H%M%S)"
  log "k6 load test: vus=$VUS duration=$DURATION scenario=$SCENARIO testid=$testid"

  # ensure the cloned k6 script supports multi-host leader discovery (NODES env). Patch idempotently
  # WITHOUT touching test-cycle.sh — only the k6 scenario, only on the remote mgmt copy.
  rssh "$run" "$mgmt_pub" "cd ~/jraft_ledger && grep -q 'NODE_URLS' scripts/${SCENARIO}.js || sed -i 's#for (const port of LEADER_PORTS) {#const NODE_URLS = __ENV.NODES ? __ENV.NODES.split(\",\").map(s=>s.trim()).filter(Boolean) : LEADER_PORTS.map(p=>\"http://localhost:\"+p);\n  for (const url of [].concat(NODE_URLS)) { const port=0;#' scripts/${SCENARIO}.js || true"

  # run detached on mgmt so long soaks survive this script exiting; push k6 metrics to mgmt Prometheus
  rssh "$run" "$mgmt_pub" "cd ~/jraft_ledger && cat > /tmp/run-k6.sh <<EOF
#!/usr/bin/env bash
cd ~/jraft_ledger
export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:$PROM_PORT/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99)'
exec k6 run --vus $VUS --duration $DURATION -o experimental-prometheus-rw --tag testid=$testid \
  --summary-export=/tmp/k6-$testid.json -e NODES=$node_urls scripts/${SCENARIO}.js
EOF
chmod +x /tmp/run-k6.sh; nohup /tmp/run-k6.sh >/tmp/k6-$testid.log 2>&1 </dev/null & disown; sleep 3; echo started"
  ok "k6 started on mgmt (testid=$testid). Grafana k6-test-cycle → filter testid=$testid"
  echo "$testid" > "$RD/last_testid"
  echo "  Watch:  $0 status        Collect when done:  $0 collect"
}

# ────────────────────────────── status ──────────────────────────────
cmd_status() {
  local run; run="$(current_run)"; local RD; RD="$(run_dir "$run")"
  local mgmt_pub; mgmt_pub=$(kv_get "$run" MGMT_PUB)
  local urls; urls="$(kv_get "$run" NODE_URLS | tr ',' ' ')"
  echo "Run: $run"
  log "Cluster health + raft parity (via mgmt):"
  for u in $urls; do
    echo -n "  $u: "; rssh "$run" "$mgmt_pub" "curl -s --max-time 3 $u/health 2>/dev/null" 2>/dev/null || echo "(down)"; echo
  done
  rssh "$run" "$mgmt_pub" "for u in $urls; do curl -s --max-time 3 \$u/ledger/cluster/raft-status 2>/dev/null | python3 -c 'import sys,json;d=json.load(sys.stdin);print(\"  \",d[\"nodeId\"],\"applied=\",d.get(\"lastAppliedIndex\"),\"smJnl=\",d.get(\"smJournalSeq\"),\"leader=\",d.get(\"isLeader\"))' 2>/dev/null; done" 2>/dev/null || true
  if [ -f "$RD/last_testid" ]; then
    local tid; tid=$(cat "$RD/last_testid")
    echo "Last test $tid:"; rssh "$run" "$mgmt_pub" "pgrep -f 'k6 run' >/dev/null && echo '  k6 RUNNING' || echo '  k6 done'; grep -E 'running \(' /tmp/k6-$tid.log 2>/dev/null | tail -1 | sed 's/^/  /'" 2>/dev/null || true
  fi
}

# ────────────────────────────── collect ──────────────────────────────
cmd_collect() {
  local run; run="$(current_run)"; local RD; RD="$(run_dir "$run")"
  local mgmt_pub; mgmt_pub=$(kv_get "$run" MGMT_PUB)
  local out; out="$(cd "$(dirname "$0")" && pwd)/results/$run"; mkdir -p "$out"
  log "Collecting results → $out"
  scp $(SSHK "$run") "$SSH_USER@$mgmt_pub:/tmp/k6-*.json" "$out/" 2>/dev/null || warn "no k6 summary yet"
  scp $(SSHK "$run") "$SSH_USER@$mgmt_pub:/tmp/k6-*.log"  "$out/" 2>/dev/null || true
  # snapshot Prometheus TSDB for offline analysis
  rssh "$run" "$mgmt_pub" "curl -s -XPOST http://localhost:$PROM_PORT/api/v1/admin/tsdb/snapshot" 2>/dev/null | sed 's/^/  prom-snapshot: /' || true
  ok "collected to $out"
}

# ────────────────────────────── ssh helper ──────────────────────────────
cmd_ssh() {
  local run; run="$(current_run)"; local target="${1:-mgmt}"
  local ip; ip=$(awk -v t="$target" '$1==t{print $3}' "$(run_dir "$run")/hosts")
  [ -n "$ip" ] || die "unknown target: $target (use node-1|node-2|node-3|mgmt)"
  exec ssh $(SSHK "$run") "$SSH_USER@$ip"
}

# ────────────────────────────── down / sweep ──────────────────────────────
cmd_down() {
  preflight
  local run; run="$(current_run)"; local RD; RD="$(run_dir "$run")"
  local SG; SG="$(kv_get "$run" SG)"; local KEY; KEY="$(kv_get "$run" KEY)"
  local IDS; IDS=$(awk '{print $2}' "$RD/instances" | tr '\n' ' ')
  log "Terminating run $run instances: $IDS"
  "${AWSCLI[@]}" ec2 terminate-instances --instance-ids $IDS >/dev/null
  "${AWSCLI[@]}" ec2 wait instance-terminated --instance-ids $IDS
  "${AWSCLI[@]}" ec2 delete-security-group --group-id "$SG" 2>/dev/null || warn "SG $SG delete deferred (eni detach)"
  "${AWSCLI[@]}" ec2 delete-key-pair --key-name "$KEY" >/dev/null 2>&1 || true
  rm -f "$(runid_file)"
  ok "run $run torn down"
}

# Delete EVERY resource this harness ever created (any run), to stop all spend.
cmd_sweep() {
  preflight
  warn "Sweeping ALL Project=$PROJECT_TAG resources in $REGION (instances + security groups + key pairs)"
  # 1. instances
  local IDS; IDS=$("${AWSCLI[@]}" ec2 describe-instances \
    --filters "Name=tag:Project,Values=$PROJECT_TAG" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text)
  if [ -n "$IDS" ]; then
    echo "  terminating instances: $IDS"
    "${AWSCLI[@]}" ec2 terminate-instances --instance-ids $IDS >/dev/null
    "${AWSCLI[@]}" ec2 wait instance-terminated --instance-ids $IDS || true   # wait so ENIs free → SG deletable
    ok "instances terminated"
  else ok "no instances"; fi
  # 2. security groups (must be after instance ENIs detach)
  local SGS; SGS=$("${AWSCLI[@]}" ec2 describe-security-groups \
    --filters "Name=tag:Project,Values=$PROJECT_TAG" --query 'SecurityGroups[].GroupId' --output text)
  for sg in $SGS; do "${AWSCLI[@]}" ec2 delete-security-group --group-id "$sg" 2>/dev/null \
    && echo "  deleted SG $sg" || warn "SG $sg not deletable yet (retry sweep)"; done
  # 3. key pairs
  local KEYS; KEYS=$("${AWSCLI[@]}" ec2 describe-key-pairs \
    --filters "Name=tag:Project,Values=$PROJECT_TAG" --query 'KeyPairs[].KeyName' --output text)
  for k in $KEYS; do "${AWSCLI[@]}" ec2 delete-key-pair --key-name "$k" >/dev/null 2>&1 \
    && echo "  deleted key $k" || true; done
  rm -f "$(runid_file)"
  ok "sweep complete — all $PROJECT_TAG resources removed"
}

# ────────────────────────────── arg parse ──────────────────────────────
CMD="${1:-}"; shift || true
while [ $# -gt 0 ]; do case "$1" in
  --nodes) NODES="$2"; shift 2;;
  --node-type) NODE_TYPE="$2"; shift 2;;
  --mgmt-type) MGMT_TYPE="$2"; shift 2;;
  --branch) BRANCH="$2"; shift 2;;
  --ttl-hours) TTL_HOURS="$2"; shift 2;;
  --vus) VUS="$2"; shift 2;;
  --duration) DURATION="$2"; shift 2;;
  --scenario) SCENARIO="$2"; shift 2;;
  --ssh-cidr) SSH_CIDR="$2"; shift 2;;
  --region) REGION="$2"; AWSCLI=(aws --region "$REGION" --output json); shift 2;;
  *) break;;
esac; done

case "$CMD" in
  up)       cmd_up;;
  test)     cmd_test;;
  status)   cmd_status;;
  collect)  cmd_collect;;
  ssh)      cmd_ssh "${1:-mgmt}";;
  down)     cmd_down;;
  sweep)    cmd_sweep;;
  *) cat <<EOF
aws-soak.sh — on-demand jraft_ledger multi-host soak on AWS

  Set creds first:  export AWS_ACCESS_KEY_ID=...  AWS_SECRET_ACCESS_KEY=...  [AWS_SESSION_TOKEN=...]

  up        provision 3 nodes + mgmt, deploy cluster (no-Kafka) + Prometheus/Grafana
  test      run k6 load test from mgmt against the cluster (k6 metrics → Grafana)
  status    cluster health + raft parity + k6 progress
  collect   pull k6 summary + prometheus snapshot to ./results/<run>/
  ssh <t>   ssh into node-1|node-2|node-3|mgmt
  down      delete THIS run (instances + SG + key)
  sweep     delete ALL Project=$PROJECT_TAG resources in the region (instances + SGs + keys) — saves credits

  Flags: --nodes --node-type --mgmt-type --branch --ttl-hours --vus --duration --scenario --region
         --ssh-cidr <cidr>   ingress CIDR for ssh/grafana/prometheus (default: detected IP/32).
                             Use if your TCP egress != HTTPS egress, or pass 0.0.0.0/0.
EOF
  ;;
esac
