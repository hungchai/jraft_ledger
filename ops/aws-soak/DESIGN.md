# On-Demand AWS Soak Harness — Design

Spin up a fresh N-node jraft_ledger cluster + observability/driver, run a load/soak test,
collect results, and **tear everything down** — from one command. No-Kafka lean mode by default.

## Goals / non-goals
- **Goal:** reproducible, parameterized, self-cleaning load tests on real multi-host EC2.
- **Goal:** never orphan billable infra (auto-teardown + TTL failsafe + tag-scoped destroy).
- **Non-goal:** production deployment / HA ops. This is a test rig.

## Identity & guardrails
- Run as a **scoped IAM user** (see `iam-policy.json`) — NOT root. Region-locked (ap-southeast-1),
  destroy actions gated on `Project=jraft-soak` tag, RunInstances must carry that tag.
- Prefer **STS short-lived creds**. Set a **Budgets alarm** out of band.
- Every resource tagged: `Project=jraft-soak`, `Run=<run-id>`, `TTL=<epoch-seconds>`, `Owner=<you>`.
- **TTL failsafe:** each instance gets `sudo shutdown -h` at `duration + buffer` via `at`/cron, so a
  crashed/abandoned harness still self-destructs (stopped instances are then swept by `teardown`).

## Topology (default)
```
N × node      (default 3)  c7i.large   HTTP :8080  raft :28080   tag Role=node
1 × mgmt                   c7i-flex.large  prometheus :9090  grafana :3000  k6   tag Role=mgmt
```
- Reuse the **default VPC** (no VPC creation → smaller IAM surface). One dedicated SG per run.
- All instances in one subnet/AZ → Raft + scrape over **private IPs** (low latency, no public hairpin).

## Security group (created per run, deleted on teardown)
- `28080` + `8080` from the **SG itself** (self-referencing) → node↔node Raft + mgmt→node.
- `22`, `3000`, `9090` from **your IP/32 only** (the invoking machine's public IP, auto-detected).
- Egress: all (for `get.docker.com`, apt, image pulls).

## Parameters
| Flag | Default | Meaning |
|---|---|---|
| `--nodes` | 3 | Raft voting nodes (odd: 3 or 5) |
| `--node-type` | c7i.large | node instance type |
| `--mgmt-type` | c7i-flex.large | mgmt/driver instance type |
| `--vus` | 10 | k6 virtual users |
| `--duration` | 10m | test duration |
| `--scenario` | k6-posting-stress | k6 script |
| `--branch` | v3-fix | repo branch to deploy |
| `--region` | ap-southeast-1 | locked by IAM |
| `--keep` | false | skip teardown (debug) |
| `--ttl-buffer` | 30m | extra time before shutdown failsafe |

## Phases
1. **preflight** — verify creds (`sts get-caller-identity`), region, default VPC/subnet, detect your public IP, pick latest Ubuntu 22.04 AMI (`describe-images`), generate run-id.
2. **provision** — create key pair (save `*.pem` locally, chmod 600), create SG with rules above,
   `run-instances` N nodes + 1 mgmt (tagged), wait for `running` + SSH reachable.
3. **install** (parallel) — Docker (`get.docker.com`), clone repo@branch; mgmt also gets k6.
4. **build & ship** — build image once on node-1, `docker save | ssh | docker load` to other nodes
   over **private** IPs (fast, intra-AWS). mgmt needs no build (stock prometheus/grafana).
5. **deploy cluster** — each node: `docker run --network host` with `NODE_ID=<priv-ip>`,
   `PEER_NODES=<all priv-ips:28080>`, `LEDGER_ADVERTISE_URL`, no-Kafka (`KAFKA_BOOTSTRAP_SERVERS=kafka.invalid:9092`,
   `LEDGER_KAFKA_REQUIRED=false`), JVM `--add-opens` + bounded heap, data vol `chown 999`.
   Wait for leader; verify cross-node health.
6. **observability** — mgmt: Prometheus (scrape node priv-IPs:8080, `--web.enable-remote-write-receiver`)
   + Grafana (repo provisioning). Print Grafana URL.
7. **run test** — on mgmt: `k6 run --vus --duration -o experimental-prometheus-rw --tag testid=<run-id>
   -e NODES=<priv-urls> <scenario>`. Long runs: launched detached on mgmt (survives harness exit);
   harness either waits or returns a `status`/`collect` handle.
8. **collect** — k6 summary JSON, Prometheus snapshot (`/api/v1/admin/tsdb/snapshot`) or range-export of
   key series, optional Grafana panel render → `results/<run-id>/`.
9. **teardown** — `terminate-instances` + delete SG + key pair, all by `Project=jraft-soak,Run=<run-id>`.
   Idempotent: `aws-soak.sh teardown [--run <id>|--all]`.

## Subcommands
```
aws-soak.sh up      --nodes 3 --vus 50 --duration 240m   # provision+deploy+run (detached)
aws-soak.sh status  --run <id>                            # leader, k6 progress, prom targets
aws-soak.sh collect --run <id>                            # pull results locally
aws-soak.sh teardown --run <id> | --all                  # destroy (by tag)
aws-soak.sh sweep                                         # terminate ALL Project=jraft-soak (safety)
```

## Implementation choice
- **Primary: bash + aws-cli** — mirrors the manual deploy already proven; zero extra deps, easy to read/audit.
- **Alt: Terraform** for provision (state-tracked, clean destroy) + bash for in-instance config. Better if
  this becomes frequent; heavier setup. Recommend starting bash, migrate to TF if it sticks.

## Cost & safety notes
- Default 4× small instances for ~tens of minutes = cents–low-dollars. Long soaks scale linearly.
- Three independent stops to prevent orphans: explicit teardown, TTL `shutdown -h`, `sweep` by tag.
- Harness refuses to run if it can't detect your public IP (avoids opening 22/3000/9090 to 0.0.0.0/0).

## Known follow-ups (needed for full test-cycle parity)
- `k6-posting-stress.js` `NODES` env support — DONE (multi-host leader discovery).
- `test-cycle.sh` external-cluster mode (skip local stack mgmt, parameterize node URLs for recon) — TODO
  if you want flush→k6→cross-node-recon from mgmt instead of plain k6.
