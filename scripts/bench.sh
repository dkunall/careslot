#!/usr/bin/env bash
# Rough booking benchmark against the compose stack, with curl as the client on the same machine.
# Prints what it measured; the numbers depend on the machine and are not a capacity claim.
#   N      bookings in the spread run (default 1000), one fresh synthetic patient each
#   C      concurrent clients (default 32)
#   HOT    requests racing for one capacity-30 slot (default 200)
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
N=${N:-1000}; C=${C:-32}; HOT=${HOT:-200}
TAG=$(date +%s)-$RANDOM; ZONE=Asia/Kolkata
ADMIN=$(bearer "$(token "admin-$TAG@careslot.test" careslot.admin)")
DESK=$(bearer "$(token "desk-$TAG@careslot.test" careslot.front-desk)")
export BASE DESK
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

FAC="fac-b$TAG"; CLI="cli-b$TAG"
expect 201 "$ADMIN" POST /v1/facilities "{\"id\":\"$FAC\",\"name\":\"Bench Hospital\",\"zone\":\"$ZONE\"}" > /dev/null
expect 201 "$ADMIN" POST /v1/clinics "{\"id\":\"$CLI\",\"facilityId\":\"$FAC\",\"name\":\"Bench Clinic\"}" > /dev/null
DAY=$(TZ=$ZONE date -d '+2 days' +%F)
DOCS=()
for d in 1 2 3 4; do
  id=$(expect 201 "$ADMIN" POST /v1/resources "{\"facilityId\":\"$FAC\",\"clinicId\":\"$CLI\",\"kind\":\"PRACTITIONER\",\"name\":\"Dr Bench $d\",\"loginSubject\":\"drb$d-$TAG@careslot.test\"}" | jq -r .id)
  expect 201 "$ADMIN" POST "/v1/resources/$id/templates" "{\"rrule\":\"FREQ=DAILY\",\"dayStart\":\"00:00\",\"dayEnd\":\"23:55\",\"slotMinutes\":5,\"capacity\":30,\"validFrom\":\"$(TZ=$ZONE date +%F)\"}" > /dev/null
  DOCS+=("$id")
done
DAY0=$(date -d "TZ=\"$ZONE\" $DAY 00:00" +%s)

echo "creating $((N + HOT)) synthetic patients (not timed)"
seq 1 $((N + HOT)) | xargs -P "$C" -I{} sh -c '
  curl -sf -X POST "$BASE/v1/patients" -H "$DESK" -H "Content-Type: application/json" -d "{\"healthId\":\"8'"$(printf %05d $(( $(date +%s) % 100000 )))"'$(printf %08d {})\",\"name\":\"Bench Patient {}\",\"phone\":\"+910000000000\",\"email\":\"b{}-'"$TAG"'@example.test\",\"birthYear\":1980}" | jq -r .id' > "$W/patients"
[[ $(wc -l < "$W/patients") -eq $((N + HOT)) ]] || { echo "patient setup failed" >&2; exit 1; }

# The hot slot is noon the day after, which the spread run never touches.
# One line per request: patient doctor fromEpoch. Spread run: 4 doctors x 288 slots, at most 30 per slot.
head -n "$N" "$W/patients" | awk -v day0="$DAY0" -v d="${DOCS[*]}" 'BEGIN{split(d,docs," ")} {i=NR-1; print $1, docs[i%4+1], day0 + 300*(int(i/4)%288)}' > "$W/spread"
tail -n "$HOT" "$W/patients" | awk -v day0="$DAY0" -v doc="${DOCS[0]}" '{print $1, doc, day0 + 86400 + 43200}' > "$W/hot"

run() { # run FILE -> lines "status seconds"
  xargs -P "$C" -L1 sh -c '
    from=$(date -u -d "@$2" +%FT%TZ); to=$(date -u -d "@$(($2 + 300))" +%FT%TZ)
    curl -s -o /dev/null -w "%{http_code} %{time_total}\n" -X POST "$BASE/v1/appointments" -H "$DESK" \
      -H "Idempotency-Key: bench-$0-$2" -H "Content-Type: application/json" \
      -d "{\"patientId\":\"$0\",\"clinicId\":\"'"$CLI"'\",\"practitionerId\":\"$1\",\"from\":\"$from\",\"to\":\"$to\"}"' < "$1"
}
report() { # report NAME RESULTS SECONDS
  awk '{print $2 * 1000}' "$2" > "$W/ms"
  printf '%s: %d requests in %.1fs (%.0f req/s), statuses: %s; latency ms p50 %.0f p95 %.0f p99 %.0f max %.0f\n' \
    "$1" "$(wc -l < "$2")" "$3" "$(awk -v n="$(wc -l < "$2")" -v t="$3" 'BEGIN{print n / t}')" \
    "$(awk '{print $1}' "$2" | sort | uniq -c | awk '{printf "%s x%s ", $2, $1}')" \
    "$(percentile "$W/ms" 50)" "$(percentile "$W/ms" 95)" "$(percentile "$W/ms" 99)" "$(percentile "$W/ms" 100)"
}
echo "warm-up: 50 bookings (not reported)"
head -n 50 "$W/spread" > "$W/warm"; tail -n +51 "$W/spread" > "$W/spread2"
run "$W/warm" > /dev/null
t0=$(date +%s.%N); run "$W/spread2" > "$W/r1"; t1=$(date +%s.%N)
report "spread (distinct slots, concurrency $C)" "$W/r1" "$(awk -v a="$t0" -v b="$t1" 'BEGIN{print b - a}')"
t0=$(date +%s.%N); run "$W/hot" > "$W/r2"; t1=$(date +%s.%N)
report "hot slot (capacity 30, $HOT patients)" "$W/r2" "$(awk -v a="$t0" -v b="$t1" 'BEGIN{print b - a}')"
WON=$(grep -c '^201 ' "$W/r2" || true)
check "hot slot never overbooked: winners" "$WON" 30
