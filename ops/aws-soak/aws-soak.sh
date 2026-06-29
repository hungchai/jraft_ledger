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
# Self-contained branch: has the harness + the k6 NODES (multi-host leader discovery) patch.
# Switch to v3-fix once PR #15 merges (then v3-fix carries the k6 patch too).
BRANCH="${BRANCH:-feat/aws-soak-harness}"
NODES=3
# c7i.large (NOT c7i-flex): non-burstable, sustained full CPU — flex throttles
# under sustained load and inflates latency. Same 2 vCPU / 4 GiB as flex, so the
# only changed variable vs the earlier flex runs is burstable→sustained.
NODE_TYPE="c7i.large"
MGMT_TYPE="c7i.large"
ROOT_GB=30
# Raft-log storage arm (controls ONLY where /var/lib/ledger/raft lives; state RocksDB
# stays on the root gp3 so the raft disk can be measured in isolation by fio/iostat):
#   gp3  — raft log on root gp3 (baseline; no extra volume)
#   io2  — raft log on a dedicated provisioned-IOPS io2 volume (low-latency, durable)
#   nvme — raft log on the instance-store local NVMe (needs an *d instance type, e.g. c6id.large)
DISK="gp3"
RAFT_GB=20                       # size of the dedicated raft-log volume (io2 arm)
RAFT_IOPS=3000                   # provisioned IOPS for the io2 raft-log volume
TTL_HOURS=6                      # failsafe: instances self-shutdown after this many hours
VUS=10
DURATION="2m"
SCENARIO="k6-posting-stress"
SSH_USER="ubuntu"
RAFT_PORT=28080
HTTP_PORT=8080
GRAFANA_PORT=3000
PROM_PORT=9090
# --full: also provision a dedicated `svc` host running mysql + kafka + projection
# (docker-compose.aws-svc.yml), and wire the ledger nodes to publish to that Kafka.
FULL=false
SVC_TYPE="c7i.xlarge"            # services host (mysql+kafka+projection); needs ~4 vCPU / 8 GiB
KAFKA_EXT_PORT=29092             # Kafka EXTERNAL advertised listener (off-host: ledger nodes)
MYSQL_PORT=3306
PROJECTION_PORT=8089
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

# Map an instance type to its CPU architecture. AWS Graviton (ARM) types carry a 'g' right
# after the generation digit: c8gd, c7gd, c6g, m7g, r8g → arm64; c6id, c7i, m6a, c5 → x86_64.
instance_arch() {
  local fam="${1%%.*}"   # strip the size suffix
  if echo "$fam" | grep -qE '^[a-z]+[0-9]+g'; then echo arm64; else echo x86_64; fi
}

# Resolve the latest Ubuntu 24.04 AMI for the given arch ($1 = arm64 | x86_64). The Ubuntu
# image name uses 'arm64'/'amd64'; the EC2 architecture filter uses 'arm64'/'x86_64'.
ubuntu2404_ami() {
  local arch="${1:-x86_64}" namearch
  [ "$arch" = "arm64" ] && namearch="arm64" || namearch="amd64"
  "${AWSCLI[@]}" ec2 describe-images --owners 099720109477 \
    --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-${namearch}-server-*" \
              "Name=state,Values=available" "Name=architecture,Values=${arch}" \
    --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text 2>/dev/null \
  || "${AWSCLI[@]}" ec2 describe-images --owners 099720109477 \
       --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-noble-24.04-${namearch}-server-*" \
                 "Name=state,Values=available" "Name=architecture,Values=${arch}" \
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
# Direct local→node public-IP SSH is unreliable from some networks (per-IP route blackholes
# in the runner's ISP path to the region). mgmt is the one host always reachable, and it can
# reach every node over the private network, so route all node SSH through mgmt as a ProxyJump
# to the node's PRIVATE ip. mgmt itself is still reached directly. Same keypair authenticates
# both hops (-i applies to the jump too), so no key needs to live on mgmt.
rssh() {
  local run=$1 ip=$2; shift 2
  local hf; hf="$(run_dir "$run")/hosts"
  local mpub; mpub=$(awk '$1=="mgmt"{print $3}' "$hf" 2>/dev/null)
  if [ -n "$mpub" ] && [ "$ip" != "$mpub" ]; then
    local priv; priv=$(awk -v p="$ip" '$3==p{print $4}' "$hf")
    if [ -n "$priv" ]; then
      # ProxyCommand (not -J): -J's host-key handling ignores our UserKnownHostsFile=/dev/null
      # on the jump hop and fails verification. -W with the same opts on both hops works.
      ssh -n $(SSHK "$run") -o ProxyCommand="ssh $(SSHK "$run") -W %h:%p $SSH_USER@$mpub" "$SSH_USER@$priv" "$@"; return
    fi
  fi
  ssh -n $(SSHK "$run") "$SSH_USER@$ip" "$@"
}

# ────────────────────────────── up ──────────────────────────────
cmd_up() {
  preflight
  local RUN="soak-$(date +%Y%m%d-%H%M%S)"
  local RD; RD="$(run_dir "$RUN")"; mkdir -p "$RD"; echo "$RUN" > "$(runid_file)"
  # Ingress CIDR for ssh/grafana/prometheus/http. Defaults to 0.0.0.0/0 (allow all) so the harness
  # "just works" regardless of where it runs — auto-detecting the runner's IP is unreliable when the
  # raw-TCP egress differs from the HTTPS egress (NAT/proxy/corp net). This is a throwaway test rig
  # (ephemeral, tagged, TTL-shutdown), so open access is an accepted tradeoff. Narrow it with
  # --ssh-cidr <cidr> (or SSH_CIDR env) for a locked-down run.
  local CIDR="${SSH_CIDR:-0.0.0.0/0}"
  [ "$CIDR" = "0.0.0.0/0" ] && warn "SG opens ssh/grafana/prometheus/http to 0.0.0.0/0 (public). Use --ssh-cidr to restrict."
  # resolve a per-architecture AMI: node and mgmt types may differ in arch (e.g. ARM nodes +
  # x86 mgmt), and an arm64 instance MUST boot an arm64 AMI. Resolve only the arches in use.
  local NODE_ARCH MGMT_ARCH SVC_ARCH; NODE_ARCH="$(instance_arch "$NODE_TYPE")"; MGMT_ARCH="$(instance_arch "$MGMT_TYPE")"
  SVC_ARCH=""; [ "$FULL" = true ] && SVC_ARCH="$(instance_arch "$SVC_TYPE")"
  local AMI_X86="" AMI_ARM=""
  case "$NODE_ARCH $MGMT_ARCH $SVC_ARCH" in *x86_64*) AMI_X86="$(ubuntu2404_ami x86_64)"; [ "$AMI_X86" != "None" ] || die "No x86_64 Ubuntu 24.04 AMI in $REGION.";; esac
  case "$NODE_ARCH $MGMT_ARCH $SVC_ARCH" in *arm64*)  AMI_ARM="$(ubuntu2404_ami arm64)";  [ "$AMI_ARM" != "None" ] || die "No arm64 Ubuntu 24.04 AMI in $REGION.";; esac
  local SUBNET; SUBNET="$(default_subnet)"; [ "$SUBNET" != "None" ] || die "No default subnet."
  local VPC; VPC="$(subnet_vpc "$SUBNET")"
  log "Run=$RUN  node=$NODE_TYPE($NODE_ARCH:$AMI_ARM$AMI_X86)  mgmt=$MGMT_TYPE($MGMT_ARCH)  subnet=$SUBNET  vpc=$VPC  ingress-cidr=$CIDR"

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
  # intra-SG: Raft + HTTP + node_exporter (9100, so mgmt Prometheus can scrape host CPU) between members
  "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
    "IpProtocol=tcp,FromPort=$RAFT_PORT,ToPort=$RAFT_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=raft}]" \
    "IpProtocol=tcp,FromPort=$HTTP_PORT,ToPort=$HTTP_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=http-intra}]" \
    "IpProtocol=tcp,FromPort=9100,ToPort=9100,UserIdGroupPairs=[{GroupId=$SG,Description=node-exporter}]" >/dev/null
  # from your IP only: ssh + observability + http (so you can curl/k6 from elsewhere if needed)
  "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
    "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$CIDR,Description=ssh}]" \
    "IpProtocol=tcp,FromPort=$HTTP_PORT,ToPort=$HTTP_PORT,IpRanges=[{CidrIp=$CIDR,Description=http-you}]" \
    "IpProtocol=tcp,FromPort=$GRAFANA_PORT,ToPort=$GRAFANA_PORT,IpRanges=[{CidrIp=$CIDR,Description=grafana}]" \
    "IpProtocol=tcp,FromPort=$PROM_PORT,ToPort=$PROM_PORT,IpRanges=[{CidrIp=$CIDR,Description=prometheus}]" >/dev/null
  if [ "$FULL" = true ]; then
    # intra-SG: ledger nodes → kafka EXTERNAL listener; projection → mysql/kafka are in-bridge (no SG hop)
    "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
      "IpProtocol=tcp,FromPort=$KAFKA_EXT_PORT,ToPort=$KAFKA_EXT_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=kafka-ext}]" \
      "IpProtocol=tcp,FromPort=$MYSQL_PORT,ToPort=$MYSQL_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=mysql-intra}]" \
      "IpProtocol=tcp,FromPort=$PROJECTION_PORT,ToPort=$PROJECTION_PORT,UserIdGroupPairs=[{GroupId=$SG,Description=projection-intra}]" >/dev/null
    # from your IP: projection actuator/health + mysql (so you can inspect ledger_view)
    "${AWSCLI[@]}" ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
      "IpProtocol=tcp,FromPort=$PROJECTION_PORT,ToPort=$PROJECTION_PORT,IpRanges=[{CidrIp=$CIDR,Description=projection-you}]" \
      "IpProtocol=tcp,FromPort=$MYSQL_PORT,ToPort=$MYSQL_PORT,IpRanges=[{CidrIp=$CIDR,Description=mysql-you}]" >/dev/null
  fi
  ok "security group $SG (raft/http intra-SG; 22/3000/9090/8080 from $CIDR)"

  # --- launch instances ---
  local TTL_EPOCH=$(( $(date +%s) + TTL_HOURS*3600 ))
  local BDM="DeviceName=/dev/sda1,Ebs={VolumeSize=$ROOT_GB,VolumeType=gp3,DeleteOnTermination=true}"
  launch_one() { # role name type
    local arch ami; arch="$(instance_arch "$3")"; [ "$arch" = "arm64" ] && ami="$AMI_ARM" || ami="$AMI_X86"
    "${AWSCLI[@]}" ec2 run-instances --image-id "$ami" --instance-type "$3" --count 1 \
      --key-name "$KEY" --security-group-ids "$SG" --subnet-id "$SUBNET" --associate-public-ip-address \
      --block-device-mappings "$BDM" \
      --tag-specifications "ResourceType=instance,Tags=[{Key=Project,Value=$PROJECT_TAG},{Key=Run,Value=$RUN},{Key=Role,Value=$1},{Key=Name,Value=$2},{Key=TTL,Value=$TTL_EPOCH}]" \
      --query 'Instances[0].InstanceId' --output text
  }
  : > "$RD/instances"
  for i in $(seq 1 "$NODES"); do echo "node-$i $(launch_one node "${PROJECT_TAG}-$RUN-node-$i" "$NODE_TYPE")" >> "$RD/instances"; done
  echo "mgmt $(launch_one mgmt "${PROJECT_TAG}-$RUN-mgmt" "$MGMT_TYPE")" >> "$RD/instances"
  local NINST=$((NODES+1))
  if [ "$FULL" = true ]; then
    echo "svc $(launch_one svc "${PROJECT_TAG}-$RUN-svc" "$SVC_TYPE")" >> "$RD/instances"; NINST=$((NINST+1))
  fi
  ok "launched $NINST instances"

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
    echo "RUN=$RUN"; echo "REGION=$REGION"; echo "SG=$SG"; echo "KEY=$KEY"; echo "DISK=$DISK"
    echo "PEERS=$(awk '$1~/^node-/{printf "%s%s:'"$RAFT_PORT"'",sep,$4; sep=","}' "$RD/hosts")"
    echo "NODE_URLS=$(awk '$1~/^node-/{printf "%shttp://%s:'"$HTTP_PORT"'",sep,$4; sep=","}' "$RD/hosts")"
    echo "MGMT_PUB=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")"
    echo "MGMT_PRIV=$(awk '$1=="mgmt"{print $4}' "$RD/hosts")"
    echo "FULL=$FULL"
    echo "SVC_PUB=$(awk '$1=="svc"{print $3}' "$RD/hosts")"
    echo "SVC_PRIV=$(awk '$1=="svc"{print $4}' "$RD/hosts")"
  } > "$RD/run.env"
  cat "$RD/hosts" | sed 's/^/  /'

  wait_ssh "$RUN"
  setup_raft_disk "$RUN"
  install_all "$RUN"
  [ "$FULL" = true ] && deploy_svc "$RUN"    # kafka+mysql+projection first, so required=true nodes find Kafka ready
  deploy_cluster "$RUN"
  deploy_obs "$RUN"
  verify_cluster "$RUN"

  local MGMT_PUB; MGMT_PUB="$(kv_get "$RUN" MGMT_PUB)"
  echo
  ok "Cluster UP (run $RUN). Grafana: http://$MGMT_PUB:$GRAFANA_PORT (admin/admin123)  Prometheus: http://$MGMT_PUB:$PROM_PORT"
  if [ "$FULL" = true ]; then
    local SVC_PUB; SVC_PUB="$(kv_get "$RUN" SVC_PUB)"
    ok "Full stack: projection http://$SVC_PUB:$PROJECTION_PORT/actuator/health  mysql $SVC_PUB:$MYSQL_PORT (ledger/ledger123 db=ledger_view)  kafka $SVC_PUB:$KAFKA_EXT_PORT"
  fi
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
          # Install k6 from the GitHub release binary (arch-aware). The apt repo (dl.k6.io/deb) has
          # no arm64 package, and older k6 builds reject the scenario's numeric separators (3_000),
          # so pin a recent version and pick the binary by arch.
          K6V=v0.54.0; case \$(uname -m) in aarch64) K6A=arm64;; *) K6A=amd64;; esac
          curl -fsSL https://github.com/grafana/k6/releases/download/\$K6V/k6-\$K6V-linux-\$K6A.tar.gz -o /tmp/k6.tgz \
            && tar xzf /tmp/k6.tgz -C /tmp \
            && sudo mv /tmp/k6-\$K6V-linux-\$K6A/k6 /usr/local/bin/k6 && sudo chmod +x /usr/local/bin/k6
        fi
        echo '$name ready'
      " 2>&1 | tail -1 | sed 's/^/  /' ) &
  done < "$(run_dir "$run")/hosts"
  wait; ok "install complete"
}

# Provision + mount the Raft-log disk per node according to $DISK. The raft log always
# lands at host path /mnt/raft, which deploy_cluster bind-mounts to the container's
# /var/lib/ledger/raft. State RocksDB stays on the root volume → the raft disk is
# isolated and measurable with fio/iostat against /mnt/raft.
#   gp3  : /mnt/raft is just a dir on the root gp3 (baseline; no extra volume)
#   io2  : a dedicated io2 volume is created, attached, formatted, mounted at /mnt/raft
#   nvme : the instance-store local NVMe is formatted + mounted at /mnt/raft
setup_raft_disk() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  : > "$RD/volumes"
  log "Setting up Raft-log disk: DISK=$DISK"
  while read -r name id pub priv; do
    { [ "$name" = "mgmt" ] || [ "$name" = "svc" ]; } && continue
    case "$DISK" in
      gp3)
        rssh "$run" "$pub" "sudo mkdir -p /mnt/raft && sudo chown 999:999 /mnt/raft && echo '$name: raft on root gp3'" | sed 's/^/  /'
        ;;
      io2)
        local az; az=$("${AWSCLI[@]}" ec2 describe-instances --instance-ids "$id" \
          --query 'Reservations[0].Instances[0].Placement.AvailabilityZone' --output text)
        local vol; vol=$("${AWSCLI[@]}" ec2 create-volume --availability-zone "$az" \
          --volume-type io2 --size "$RAFT_GB" --iops "$RAFT_IOPS" \
          --tag-specifications "ResourceType=volume,Tags=[{Key=Project,Value=$PROJECT_TAG},{Key=Run,Value=$run},{Key=Role,Value=raft-log}]" \
          --query 'VolumeId' --output text)
        "${AWSCLI[@]}" ec2 wait volume-available --volume-ids "$vol"
        "${AWSCLI[@]}" ec2 attach-volume --volume-id "$vol" --instance-id "$id" --device /dev/sdf >/dev/null
        "${AWSCLI[@]}" ec2 wait volume-in-use --volume-ids "$vol"
        echo "$name $vol" >> "$RD/volumes"
        # nitro renames /dev/sdf → an nvme device; resolve it via the by-id serial (vol id minus dashes)
        local ser; ser="$(echo "$vol" | tr -d '-')"
        rssh "$run" "$pub" "
          for i in \$(seq 1 30); do DEV=\$(readlink -f /dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${ser} 2>/dev/null); [ -b \"\$DEV\" ] && break; sleep 2; done
          [ -b \"\$DEV\" ] || { echo '$name: io2 device not found'; exit 1; }
          sudo mkfs.ext4 -F -q \$DEV && sudo mkdir -p /mnt/raft && sudo mount \$DEV /mnt/raft && sudo chown 999:999 /mnt/raft && echo \"$name: raft on io2 \$DEV ($vol)\"
        " | sed 's/^/  /'
        ;;
      nvme)
        rssh "$run" "$pub" "
          for i in \$(seq 1 15); do DEV=\$(readlink -f \$(ls /dev/disk/by-id/nvme-Amazon_EC2_NVMe_Instance_Storage_* 2>/dev/null | head -1) 2>/dev/null); [ -b \"\$DEV\" ] && break; sleep 2; done
          [ -b \"\$DEV\" ] || { echo '$name: instance-store NVMe not found — use a *d instance type (e.g. c6id.large)'; exit 1; }
          sudo mkfs.ext4 -F -q \$DEV && sudo mkdir -p /mnt/raft && sudo mount \$DEV /mnt/raft && sudo chown 999:999 /mnt/raft && echo \"$name: raft on local NVMe \$DEV\"
        " | sed 's/^/  /'
        ;;
      *) die "unknown --disk '$DISK' (use gp3|io2|nvme)";;
    esac
  done < "$RD/hosts"
  ok "raft-log disk ready ($DISK)"
}

# --full: bring up the services tier (mysql + kafka + projection) on the svc host via
# docker-compose.aws-svc.yml. Kafka's EXTERNAL listener is advertised on the svc private IP
# so the ledger nodes (other hosts) can publish; projection↔mysql↔kafka stay on the bridge net.
deploy_svc() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  local svc_pub svc_priv; svc_pub=$(awk '$1=="svc"{print $3}' "$RD/hosts"); svc_priv=$(awk '$1=="svc"{print $4}' "$RD/hosts")
  log "Bringing up services tier on svc ($svc_priv): mysql + kafka + projection (maven build ~5min)..."
  rssh "$run" "$svc_pub" "cd jraft_ledger && SVC_PRIV=$svc_priv docker compose -f docker-compose.aws-svc.yml up -d --build 2>&1 | tail -3 && echo svc-compose-up" | sed 's/^/  /'
  log "Waiting for mysql healthy + projection health..."
  rssh "$run" "$svc_pub" '
    for i in $(seq 1 40); do
      mysql_ok=$(docker inspect -f "{{.State.Health.Status}}" ledger-mysql 2>/dev/null)
      proj=$(curl -s --max-time 3 http://localhost:8089/actuator/health 2>/dev/null | grep -o "\"status\":\"UP\"" | head -1)
      [ "$mysql_ok" = healthy ] && [ -n "$proj" ] && { echo "  mysql=healthy projection=UP"; exit 0; }
      sleep 6
    done
    echo "  WARN: services not fully healthy after wait (mysql=$mysql_ok projection=${proj:-down}); check docker logs on svc"
  ' | sed 's/^/  /'
  ok "services tier up (projection http://$svc_pub:$PROJECTION_PORT, mysql/kafka on $svc_priv)"
}

deploy_cluster() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  local node1_pub node1_priv; node1_pub=$(awk '$1=="node-1"{print $3}' "$RD/hosts"); node1_priv=$(awk '$1=="node-1"{print $4}' "$RD/hosts")
  local PEERS; PEERS="$(kv_get "$run" PEERS)"

  # build image once on node-1, ship to the other nodes over the private network
  log "Building image on node-1 (maven-in-docker, ~5-10min)..."
  rssh "$run" "$node1_pub" "cd jraft_ledger && docker build -q -t ledger-node:latest -f Dockerfile . >/dev/null && echo built" | sed 's/^/  /'
  # push key to node-1 temporarily so it can scp the image to peers over private IPs (fast intra-AWS).
  # Route via mgmt ProxyJump to node-1's private ip (local→node public path can be blackholed).
  local mgmt_pub; mgmt_pub=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")
  scp $(SSHK "$run") -o ProxyCommand="ssh $(SSHK "$run") -W %h:%p $SSH_USER@$mgmt_pub" "$RD/key.pem" "$SSH_USER@$node1_priv:~/.ssh/soak.pem" >/dev/null
  rssh "$run" "$node1_pub" "chmod 600 ~/.ssh/soak.pem"
  while read -r name id pub priv; do
    if [ "$name" = "node-1" ] || [ "$name" = "mgmt" ] || [ "$name" = "svc" ]; then continue; fi
    log "Shipping image node-1 → $name ($priv)..."
    rssh "$run" "$node1_pub" "docker save ledger-node:latest | gzip -1 | ssh -i ~/.ssh/soak.pem -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new $SSH_USER@$priv 'gunzip | docker load' >/dev/null && echo shipped" | sed 's/^/  /'
  done < "$RD/hosts"
  rssh "$run" "$node1_pub" "rm -f ~/.ssh/soak.pem"   # remove temp key

  # launch each node: --network host, private-IP raft peers, add-opens, chown 999 data.
  # Kafka: lean mode points at an invalid broker with required=false (graceful degrade);
  # --full points at the svc host's Kafka EXTERNAL listener with required=true (real outbox path).
  local KAFKA_BOOT="kafka.invalid:9092" KAFKA_REQ="false"
  if [ "$FULL" = true ]; then KAFKA_BOOT="$(kv_get "$run" SVC_PRIV):$KAFKA_EXT_PORT"; KAFKA_REQ="true"; fi
  local JOPTS="${LEDGER_JAVA_OPTS:--Xms4g -Xmx4g -XX:+UseZGC} -Dmanagement.endpoints.web.exposure.include=health,prometheus,metrics,info -Dmanagement.metrics.export.prometheus.enabled=true --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED"
  while read -r name id pub priv; do
    if [ "$name" = "mgmt" ] || [ "$name" = "svc" ]; then continue; fi
    rssh "$run" "$pub" "
      docker rm -f ledger-node node-exporter >/dev/null 2>&1 || true
      sudo rm -rf ~/ledger-data; mkdir -p ~/ledger-data; sudo chown -R 999:999 ~/ledger-data
      sudo mkdir -p /mnt/raft; sudo rm -rf /mnt/raft/*; sudo chown 999:999 /mnt/raft   # clean raft-log disk (setup_raft_disk mounted it)
      docker run -d --name node-exporter --restart unless-stopped --network host --pid host \
        -v /proc:/host/proc:ro -v /sys:/host/sys:ro -v /:/rootfs:ro \
        prom/node-exporter:latest --path.procfs=/host/proc --path.sysfs=/host/sys --path.rootfs=/rootfs >/dev/null 2>&1 || true
      docker run -d --name ledger-node --restart unless-stopped --network host \
        -e NODE_ID=$priv -e PEER_NODES=$PEERS -e RAFT_SERVER_PORT=$RAFT_PORT \
        -e LEDGER_ADVERTISE_URL=http://$priv:$HTTP_PORT \
        -e LEDGER_RAFT_DATA_PATH=/var/lib/ledger/raft -e LEDGER_ROCKSDB_PATH=/var/lib/ledger/rocksdb \
        -e KAFKA_BOOTSTRAP_SERVERS=$KAFKA_BOOT -e LEDGER_KAFKA_REQUIRED=$KAFKA_REQ \
        -e LEDGER_ROCKSDB_FSYNC=false -e LEDGER_RAFT_LOG_FSYNC=true \
        -e LEDGER_COMMAND_QUEUE_BATCH_WAIT_MS=0 \
        -e LEDGER_POSTING_TRACE_SAMPLE=${LEDGER_POSTING_TRACE_SAMPLE:-0} \
        -e JAVA_OPTS='$JOPTS' \
        -v /home/$SSH_USER/ledger-data:/var/lib/ledger -v /mnt/raft:/var/lib/ledger/raft \
        ledger-node:latest >/dev/null && echo '$name launched'
    " | sed 's/^/  /'
  done < "$RD/hosts"
  ok "cluster containers launched"
}

deploy_obs() {
  local run=$1; local RD; RD="$(run_dir "$run")"
  local mgmt_pub; mgmt_pub=$(awk '$1=="mgmt"{print $3}' "$RD/hosts")
  # ledger raft nodes only (exclude mgmt AND svc — svc runs no ledger-node / node_exporter)
  local targets; targets=$(awk '$1~/^node-/{printf "%s\"%s:'"$HTTP_PORT"'\"",sep,$4; sep=","}' "$RD/hosts")
  # host-CPU targets (node_exporter on each ledger node, port 9100 — deploy_cluster runs it)
  local node_targets; node_targets=$(awk '$1~/^node-/{printf "%s\"%s:9100\"",sep,$4; sep=","}' "$RD/hosts")
  # --full: scrape the projection service (Micrometer at :8089) too
  local proj_job=""
  if [ "$FULL" = true ]; then
    local svc_priv; svc_priv=$(awk '$1=="svc"{print $4}' "$RD/hosts")
    proj_job="  - job_name: projection
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['$svc_priv:$PROJECTION_PORT']
        labels: { app: ledger, component: projection }"
  fi
  log "Deploying Prometheus + Grafana on mgmt..."
  rssh "$run" "$mgmt_pub" "mkdir -p ~/obs && cat > ~/obs/prometheus.yml <<EOF
# 2s scrape so CPU/JVM tail behaviour is visible at fine resolution (bursty core
# contention that drives P95 is invisible at 10s).
global: { scrape_interval: 2s, scrape_timeout: 1s }
scrape_configs:
  - job_name: ledger-nodes
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [$targets]
        labels: { app: ledger, component: state-machine }
  - job_name: node-exporter            # host CPU per-core, steal, iowait, run-queue
    static_configs:
      - targets: [$node_targets]
        labels: { app: ledger }
$proj_job
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

  # The k6 scenario must support multi-host leader discovery via the NODES env. That patch ships in
  # this branch's scripts/${SCENARIO}.js (and lands in v3-fix once PR #15 merges), so the cloned repo
  # already has it — no in-place patching here. Guard: fail fast with a clear message if a branch
  # without NODES support was deployed (e.g. --branch pointed at an old ref).
  rssh "$run" "$mgmt_pub" "grep -q 'NODE_URLS' ~/jraft_ledger/scripts/${SCENARIO}.js" \
    || die "k6 scenario ${SCENARIO}.js on mgmt lacks NODES support — deploy a branch that has it (this harness branch, or v3-fix after PR #15)."

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
  # dedicated raft-log io2 volumes (attached separately → not DeleteOnTermination; delete explicitly)
  if [ -s "$RD/volumes" ]; then
    local VOLS; VOLS=$(awk '{print $2}' "$RD/volumes" | tr '\n' ' ')
    for v in $VOLS; do
      "${AWSCLI[@]}" ec2 wait volume-available --volume-ids "$v" 2>/dev/null || true
      "${AWSCLI[@]}" ec2 delete-volume --volume-id "$v" >/dev/null 2>&1 && echo "  deleted raft volume $v" || warn "raft volume $v delete deferred"
    done
  fi
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
  # 4. dedicated raft-log volumes (io2 arm) — must be after instances terminate so they detach
  local VOLS; VOLS=$("${AWSCLI[@]}" ec2 describe-volumes \
    --filters "Name=tag:Project,Values=$PROJECT_TAG" "Name=status,Values=available" \
    --query 'Volumes[].VolumeId' --output text)
  for v in $VOLS; do "${AWSCLI[@]}" ec2 delete-volume --volume-id "$v" >/dev/null 2>&1 \
    && echo "  deleted volume $v" || warn "volume $v not deletable yet (retry sweep)"; done
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
  --disk) DISK="$2"; shift 2;;
  --full) FULL=true; shift;;
  --svc-type) SVC_TYPE="$2"; shift 2;;
  --raft-gb) RAFT_GB="$2"; shift 2;;
  --raft-iops) RAFT_IOPS="$2"; shift 2;;
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
         --disk gp3|io2|nvme  raft-log storage arm (default gp3). io2 → dedicated provisioned-IOPS
                              volume (--raft-gb --raft-iops); nvme → instance-store local disk
                              (requires a *d instance type, e.g. --node-type c6id.large).
         --full              also provision a dedicated svc host running mysql + kafka + projection
                             (docker-compose.aws-svc.yml) and wire ledger nodes to publish to it
                             (KAFKA_REQUIRED=true → real outbox→Kafka→projection→MySQL path).
         --svc-type <type>   instance type for the --full services host (default c7i.xlarge).
         --ssh-cidr <cidr>   ingress CIDR for ssh/grafana/prometheus/http (default: 0.0.0.0/0, public).
                             Pass your office CIDR to lock it down.
EOF
  ;;
esac
