#!/usr/bin/env bash
# Story 8.2 (addendum §G 3): demo-morning warm-up. Calls the generation and the embedding model once each
# (directly on Hugging Face, so a cold model starts loading), retries while the provider says it is loading,
# reports each latency, then checks the sql-service path through the gateway.
#
#   ./scripts/warmup.sh                       # reads HF_API_TOKEN, SQL_HF_MODEL, HF_EMBEDDING_MODEL from .env
#   WARMUP_BASE_URL=http://localhost:8099 HF_API_TOKEN=stub ./scripts/warmup.sh     # rehearsal against llm-stub
#
# Env: HF_API_TOKEN (never printed), SQL_HF_MODEL (default defog/sqlcoder-7b-2), HF_EMBEDDING_MODEL,
#      WARMUP_BASE_URL (default https://router.huggingface.co/hf-inference/models), WARMUP_ATTEMPTS (default 6),
#      WARMUP_WAIT_S (default 20), GATEWAY_URL (default http://localhost:8080; WARMUP_SKIP_GATEWAY=1 skips it).
# Exit 0 when both models answered 200 and the gateway check passed.
set -u
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  # Only the keys we need; values are never echoed.
  for k in HF_API_TOKEN SQL_HF_MODEL HF_EMBEDDING_MODEL; do
    if [ -z "${!k:-}" ]; then
      v=$(grep -E "^$k=" .env | tail -n 1 | cut -d= -f2- | tr -d '\r')
      [ -n "$v" ] && export "$k=$v"
    fi
  done
fi
BASE="${WARMUP_BASE_URL:-https://router.huggingface.co/hf-inference/models}"
GEN_MODEL="${SQL_HF_MODEL:-defog/sqlcoder-7b-2}"
EMB_MODEL="${HF_EMBEDDING_MODEL:-}"
ATTEMPTS="${WARMUP_ATTEMPTS:-6}"
WAIT_S="${WARMUP_WAIT_S:-20}"
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
rc=0

if [ -z "${HF_API_TOKEN:-}" ]; then
  echo "FAIL  HF_API_TOKEN is not set (.env or environment)"; exit 2
fi
AUTH_FILE=$(mktemp); trap 'rm -f "$AUTH_FILE"' EXIT
printf 'Authorization: Bearer %s\n' "$HF_API_TOKEN" > "$AUTH_FILE"   # header via file: not on the command line

# warm <label> <url> <json-body>
warm() {
  local label=$1 url=$2 body=$3 i out code ms
  for i in $(seq 1 "$ATTEMPTS"); do
    out=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 60 -H @"$AUTH_FILE" \
      -H 'Content-Type: application/json' -X POST "$url" -d "$body")
    code=${out%% *}; ms=$(awk "BEGIN{printf \"%d\", ${out##* } * 1000}")
    if [ "$code" = "200" ]; then
      echo "OK    $label  ${ms} ms  (attempt $i)"; return 0
    fi
    echo "..    $label  HTTP $code after ${ms} ms (attempt $i/$ATTEMPTS)"
    case "$code" in
      503|504|000) sleep "$WAIT_S" ;;          # loading / cold start / network: wait and retry
      *) break ;;                               # 401/403 token, 404 model not served, 429 quota: no point retrying
    esac
  done
  echo "FAIL  $label  last HTTP $code (401/403 token, 404 model not served, 429 quota, 503 still loading)"
  return 1
}

echo "warm-up  $(date -u +%FT%TZ)  base=$BASE"
warm "generation $GEN_MODEL" "$BASE/$GEN_MODEL" \
  '{"inputs":"### Task\nGenerate a SQL query to answer [QUESTION]count the orders[/QUESTION]\n### SQL\n","parameters":{"max_new_tokens":20,"return_full_text":false}}' || rc=1
if [ -n "$EMB_MODEL" ]; then
  warm "embedding  $EMB_MODEL" "$BASE/$EMB_MODEL/pipeline/feature-extraction" '{"inputs":"orders per region"}' || rc=1
else
  echo "FAIL  HF_EMBEDDING_MODEL is not set: RAG and indexing are off"; rc=1
fi

if [ "${WARMUP_SKIP_GATEWAY:-0}" != "1" ]; then
  # sql-service path: seed corpus present, then one generation through the gateway (warms the JVM path too).
  n=$(curl -s --max-time 10 -H 'X-User-Id: warmup' "$GATEWAY/api/sql/queries?size=1" | grep -o '"total_elements":[0-9]*' | cut -d: -f2)
  if [ -n "$n" ] && [ "$n" -gt 0 ]; then echo "OK    library  $n queries"; else echo "FAIL  library  unreachable or empty (seed not loaded?)"; rc=1; fi
  out=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 -H 'X-User-Id: warmup' \
    -H 'Content-Type: application/json' -X POST "$GATEWAY/api/sql/generate" \
    -d '{"prompt":"orders per region last week vs the week before"}')
  code=${out%% *}; ms=$(awk "BEGIN{printf \"%d\", ${out##* } * 1000}")
  if [ "$code" = "200" ]; then echo "OK    gateway generate  ${ms} ms"; else echo "FAIL  gateway generate  HTTP $code after ${ms} ms"; rc=1; fi
  echo "note  if the seed queries are not indexed: curl -X POST -H 'X-User-Id: warmup' $GATEWAY/api/sql/admin/reindex"
fi
exit $rc
