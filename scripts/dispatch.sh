#!/bin/bash
# Dispatch a task to a ledger agent.
# Creates a dispatch file that the agent picks up on next invocation.
#
# Usage:
#   ./scripts/dispatch.sh ledger-test-writer "Add cross-node checks to smoke-test.sh"
#
# Optional:
#   --from AGENT     Source agent (default: ledger-orchestrator)
#   --body FILE      Task body from file (otherwise reads from stdin)

set -e

AGENT="$1"
TITLE="$2"
FROM="ledger-orchestrator"
BODY_FILE=""

shift 2 2>/dev/null || true
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --body) BODY_FILE="$2"; shift 2 ;;
    *) echo "Unknown flag: $1"; exit 1 ;;
  esac
done

if [ -z "$AGENT" ] || [ -z "$TITLE" ]; then
  echo "Usage: dispatch.sh <agent-name> <title> [--from AGENT] [--body FILE]"
  echo ""
  echo "Available agents:"
  for f in .claude/agents/*.md; do
    name=$(basename "$f" .md)
    desc=$(head -3 "$f" | grep description | sed 's/.*description: *>//' | tr '\n' ' ' | xargs)
    echo "  $name — ${desc:-no description}"
  done
  exit 1
fi

AGENT_FILE=".claude/agents/${AGENT}.md"
if [ ! -f "$AGENT_FILE" ]; then
  echo "ERROR: Agent not found: $AGENT_FILE"
  echo "Available: $(ls .claude/agents/*.md | sed 's|.claude/agents/||;s|\.md||' | tr '\n' ' ')"
  exit 1
fi

DISPATCH_FILE=".claude/dispatch-${AGENT}.md"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat > "$DISPATCH_FILE" << EOF
# Dispatch: ${AGENT}

**From**: ${FROM}
**Task**: ${TITLE}
**Agent**: ${AGENT} (see \`.claude/agents/${AGENT}.md\`)
**Dispatched**: ${TIMESTAMP}

## Context

EOF

if [ -n "$BODY_FILE" ]; then
  cat "$BODY_FILE" >> "$DISPATCH_FILE"
else
  echo "(Task body — read from stdin)" >> "$DISPATCH_FILE"
  cat >> "$DISPATCH_FILE"
fi

cat >> "$DISPATCH_FILE" << EOF

## Status

Pending — awaiting agent pickup.
EOF

echo "Dispatch created: $DISPATCH_FILE"
echo "Agent: $AGENT"
echo "From:  $FROM"
