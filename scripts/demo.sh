#!/usr/bin/env bash
# End-to-end walk through the running compose stack (scripts/up.sh). Every person and ID here is synthetic.
# Exits non-zero on the first unexpected answer, so CI uses it as the smoke test.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
command -v jq > /dev/null || { echo "needs jq" >&2; exit 1; }
TAG=$(date +%s)-$RANDOM
ZONE=Asia/Kolkata
plus() { date -u -d "@$(( $(date -d "$1" +%s) + $2 ))" +%FT%TZ; }  # plus ISO_INSTANT SECONDS
ADMIN=$(bearer "$(token "admin-$TAG@careslot.test" careslot.admin)")
DESK=$(bearer "$(token "desk-$TAG@careslot.test" careslot.front-desk)")
NURSE=$(bearer "$(token "nurse-$TAG@careslot.test" careslot.nurse)")

echo "== setup: one synthetic hospital, one clinic, two doctors"
FAC="fac-$TAG"; CLI="cli-$TAG"
expect 201 "$ADMIN" POST /v1/facilities "{\"id\":\"$FAC\",\"name\":\"Synthetic General $TAG\",\"zone\":\"$ZONE\"}" > /dev/null
expect 201 "$ADMIN" POST /v1/clinics "{\"id\":\"$CLI\",\"facilityId\":\"$FAC\",\"name\":\"General Medicine\"}" > /dev/null
doctor() { # doctor NAME SUBJECT -> id
  expect 201 "$ADMIN" POST /v1/resources "{\"facilityId\":\"$FAC\",\"clinicId\":\"$CLI\",\"kind\":\"PRACTITIONER\",\"name\":\"$1\",\"loginSubject\":\"$2\"}" | jq -r .id
}
patient() { # patient SUBJECT -> id
  expect 201 "$DESK" POST /v1/patients "{\"healthId\":\"$(fake_health_id)\",\"name\":\"Demo Patient ${1%%@*}\",\"phone\":\"+910000000000\",\"email\":\"$1\",\"birthYear\":1990,\"loginSubject\":\"$1\"}" | jq -r .id
}
DR_RACE_SUB="dr-race-$TAG@careslot.test"; DR_OPD_SUB="dr-opd-$TAG@careslot.test"
DR_RACE=$(doctor "Dr Demo Race" "$DR_RACE_SUB")
DR_OPD=$(doctor "Dr Demo Opd" "$DR_OPD_SUB")
TODAY=$(TZ=$ZONE date +%F)
expect 201 "$ADMIN" POST "/v1/resources/$DR_RACE/templates" "{\"rrule\":\"FREQ=DAILY\",\"dayStart\":\"09:00\",\"dayEnd\":\"10:00\",\"slotMinutes\":15,\"capacity\":1,\"validFrom\":\"$TODAY\"}" > /dev/null
expect 201 "$ADMIN" POST "/v1/resources/$DR_OPD/templates" "{\"rrule\":\"FREQ=DAILY\",\"dayStart\":\"00:00\",\"dayEnd\":\"23:55\",\"slotMinutes\":5,\"capacity\":30,\"validFrom\":\"$TODAY\"}" > /dev/null

echo "== 20 front-desk clerks race for the one 09:00 slot tomorrow (capacity 1)"
TOMORROW=$(TZ=$ZONE date -d tomorrow +%F)
FROM=$(date -u -d "@$(date -d "TZ=\"$ZONE\" $TOMORROW 09:00" +%s)" +%FT%TZ)
TO=$(plus "$FROM" 900)
PIDS=(); for i in $(seq 1 20); do PIDS+=("$(patient "race$i-$TAG@example.test")"); done
OUT=$(mktemp -d)
for i in $(seq 0 19); do
  idem "race-$TAG-$i" "$DESK" POST /v1/appointments \
    "{\"patientId\":\"${PIDS[$i]}\",\"clinicId\":\"$CLI\",\"practitionerId\":\"$DR_RACE\",\"from\":\"$FROM\",\"to\":\"$TO\"}" > "$OUT/$i" &
done
wait
WON=$(for f in "$OUT"/*; do tail -1 "$f"; echo; done | grep -c '^201$' || true)
LOST=$(for f in "$OUT"/*; do tail -1 "$f"; echo; done | grep -c '^409$' || true)
check "exactly one booking won" "$WON" 1
check "the other 19 got 409 no-capacity" "$LOST" 19
WINNER_FILE=$(grep -l '^201$' "$OUT"/*); APPT=$(head -1 "$WINNER_FILE" | jq -r .id)
WIN_I=$(basename "$WINNER_FILE")

# Keys are scoped per patient, so the conflict case reuses the key for the same patient with another doctor.
echo "== retrying the winner's request with the same Idempotency-Key replays it"
R=$(idem "race-$TAG-$WIN_I" "$DESK" POST /v1/appointments \
  "{\"patientId\":\"${PIDS[$WIN_I]}\",\"clinicId\":\"$CLI\",\"practitionerId\":\"$DR_RACE\",\"from\":\"$FROM\",\"to\":\"$TO\"}")
check "replay is 200 with the same appointment" "${R##*$'\n'}:$(head -1 <<< "$R" | jq -r .id)" "200:$APPT"
R=$(idem "race-$TAG-$WIN_I" "$DESK" POST /v1/appointments \
  "{\"patientId\":\"${PIDS[$WIN_I]}\",\"clinicId\":\"$CLI\",\"practitionerId\":\"$DR_OPD\",\"from\":\"$FROM\",\"to\":\"$TO\"}")
check "same key, different request -> 422" "${R##*$'\n'}:$(head -1 <<< "$R" | jq -r .code)" "422:idempotency-key-reused"

echo "== a waitlisted patient gets the slot automatically when it is cancelled"
WAITER=$(patient "waiter-$TAG@example.test")
WFROM=$(plus "$FROM" -3600); WTO=$(plus "$TO" 3600)
expect 201 "$DESK" POST /v1/waitlist "{\"patientId\":\"$WAITER\",\"practitionerId\":\"$DR_RACE\",\"from\":\"$WFROM\",\"to\":\"$WTO\",\"priority\":1}" > /dev/null
expect 200 "$DESK" POST "/v1/appointments/$APPT/cancel" '{"reason":"demo cancellation"}' > /dev/null
filled() { expect 200 "$DESK" GET "/v1/patients/$WAITER/appointments" | jq -e '[.[] | select(.status == "BOOKED")] | length == 1'; }
wait_for 30 filled
check "waitlist filled the freed slot" "$(expect 200 "$DESK" GET "/v1/patients/$WAITER/appointments" | jq -r '.[0].status')" BOOKED

echo "== OPD day: book the next slot, check in, call the patient, prescribe"
PT_SUB="opd-$TAG@example.test"; PT=$(patient "$PT_SUB")
PATIENT=$(bearer "$(token "$PT_SUB" careslot.patient)")
DOCTOR=$(bearer "$(token "$DR_OPD_SUB" careslot.doctor)")
START=$(expect 200 "$DESK" GET "/v1/practitioners/$DR_OPD/availability?date=$TODAY" | jq -r '.starts[0] // empty')
[[ -n $START ]] || START=$(expect 200 "$DESK" GET "/v1/practitioners/$DR_OPD/availability?date=$TOMORROW" | jq -r '.starts[0]')
END=$(plus "$START" 300)
OPD=$(idem "self-$TAG" "$PATIENT" POST /v1/appointments "{\"patientId\":\"$PT\",\"clinicId\":\"$CLI\",\"practitionerId\":\"$DR_OPD\",\"from\":\"$START\",\"to\":\"$END\"}" | head -1 | jq -r .id)
check "patient booked her own slot" "$(expect 200 "$PATIENT" GET "/v1/appointments/$OPD" | jq -r .status)" BOOKED
check "another patient cannot see it" "$(status "$(bearer "$(token "race1-$TAG@example.test" careslot.patient)")" GET "/v1/appointments/$OPD")" 403
TOKEN_NO=$(expect 201 "$DESK" POST "/v1/appointments/$OPD/check-in" '{}' | jq -r .number)
check "check-in again returns the same token" "$(call "$NURSE" POST "/v1/appointments/$OPD/check-in" '{}' | head -1 | jq -r .number)" "$TOKEN_NO"
expect 200 "$DOCTOR" POST "/v1/practitioners/$DR_OPD/queue/call-next" '{}' > /dev/null
check "status after call-next" "$(expect 200 "$DESK" GET "/v1/appointments/$OPD" | jq -r .status)" IN_CONSULT
BOARD=$(expect 200 "$DESK" GET "/v1/practitioners/$DR_OPD/queue?date=$(TZ=$ZONE date -d "$START" +%F)")
check "queue board carries no patient names" "$(grep -c 'Demo Patient' <<< "$BOARD" || true)" 0

expect 204 "$DOCTOR" PUT "/v1/patients/$PT/demo-records" '{"medications":["RX-WARF"],"allergies":[]}' > /dev/null
RX=$(expect 201 "$DOCTOR" POST /v1/prescriptions "{\"patientId\":\"$PT\",\"appointmentId\":\"$OPD\",\"items\":[{\"drugCode\":\"RX-ASPI\",\"dose\":\"75 mg\",\"frequency\":\"OD\",\"days\":5}]}")
RXID=$(jq -r .id <<< "$RX")
check "aspirin on top of warfarin is flagged" "$(jq -r '.safety.findings[0].severity' <<< "$RX")" SEVERE
R=$(idem "sign-$TAG" "$DOCTOR" POST "/v1/prescriptions/$RXID/sign" '{}')
check "signing is blocked without a reason" "${R##*$'\n'}:$(head -1 <<< "$R" | jq -r .code)" "422:blocked-severe"
R=$(idem "sign-$TAG" "$DOCTOR" POST "/v1/prescriptions/$RXID/sign" '{"overrideReason":"demo: short course, INR monitored daily"}')
check "override with a reason signs" "${R##*$'\n'}" 200
REPLAY=$(curl -s -o /dev/null -D - -X POST "$BASE/v1/prescriptions/$RXID/sign" -H "$DOCTOR" -H "Idempotency-Key: sign-$TAG" \
  -H 'Content-Type: application/json' -d '{"overrideReason":"demo: short course, INR monitored daily"}' | tr -d '\r' | awk -F': ' 'tolower($1)=="idempotent-replayed"{print $2}')
check "a retried sign is a replay" "$REPLAY" true
curl -sf -H "$DOCTOR" "$BASE/v1/prescriptions/$RXID/pdf" -o "$OUT/rx.pdf"
check "prescription PDF" "$(head -c 5 "$OUT/rx.pdf")" "%PDF-"
check "PDF is marked as sample output" "$(grep -ac 'not clinically validated' "$OUT/rx.pdf")" 1
expect 200 "$DOCTOR" POST "/v1/appointments/$OPD/complete" '{"note":"Synthetic note: demo consult."}' > /dev/null

echo "== FHIR R4 read facade"
FA=$(curl -sf -H "$DESK" "$BASE/fhir/r4/Appointment/$OPD")
check "FHIR Appointment status" "$(jq -r '.resourceType + "/" + .status' <<< "$FA")" "Appointment/fulfilled"
FB=$(curl -sf -H "$DESK" "$BASE/fhir/r4/MedicationRequest?prescription=$RXID")
check "MedicationRequest bundle" "$(jq -r '.type + "/" + (.entry | length | tostring)' <<< "$FB")" "searchset/1"
check "FHIR without a token" "$(status "X-None: 1" GET /fhir/r4/metadata)" 401

rm -rf "$OUT"
echo "demo passed"
