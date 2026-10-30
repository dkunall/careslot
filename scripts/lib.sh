# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:7890}
OAUTH=${OAUTH:-http://127.0.0.1:7891}

# The app validates the issuer as http://oauth:8080/careslot (its in-network name), and
# mock-oauth2-server derives the issuer from the Host header. The client_id becomes the subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    --data-urlencode "scope=$2" "$OAUTH/careslot/token" | jq -r .access_token
}

# call AUTH_HEADER METHOD PATH [BODY]; prints the body, then the status on its own line.
call() {
  local auth=$1 method=$2 path=$3 body=${4:-}
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H "$auth" -H 'Content-Type: application/json' ${body:+-d "$body"}
}

bearer() { echo "Authorization: Bearer $1"; }

# expect STATUS AUTH_HEADER METHOD PATH [BODY]; prints the body or fails loudly.
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

# status AUTH_HEADER METHOD PATH [BODY]; prints only the HTTP status.
status() {
  local out
  out=$(call "$@")
  echo "${out##*$'\n'}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# wait_for SECONDS COMMAND...; retries the command every second until it succeeds.
wait_for() {
  local limit=$1 i
  shift
  for ((i = 0; i < limit; i++)); do
    if "$@" > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "FAIL: timed out after ${limit}s waiting for: $*" >&2
  exit 1
}

# percentile FILE P -> the P-th percentile of one number per line (nearest rank).
percentile() {
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# idem KEY AUTH_HEADER METHOD PATH [BODY]; like call, with an Idempotency-Key header.
idem() {
  local key=$1 auth=$2 method=$3 path=$4 body=${5:-}
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H "$auth" -H "Idempotency-Key: $key" \
    -H 'Content-Type: application/json' ${body:+-d "$body"}
}

# Synthetic 14-digit health ID; never a real one.
fake_health_id() { printf '9%013d' "$(( (RANDOM << 15 | RANDOM) % 10000000000000 ))"; }
