# CareSlot

CareSlot is the operations side of a hospital: who sees which doctor, in which room, with which machine, at what time, and what happens on the day. It books appointments that need several resources at once, overbooks a little when a clinic's own history says people won't turn up, refills cancelled slots from a waitlist, runs the token queue at the OPD desk, checks prescriptions against what the patient already takes, and sends reminders.

It is a portfolio project. Every patient, doctor and ID in it is made up, and the drug data is a small **sample that is not clinically validated**. Don't use it for real prescribing.

How it differs from [MediGraph](https://github.com/dkunall/medigraph): MediGraph is about the patient record (identity, a master patient index, consent, a Merkle-tree ledger replicated across hospitals). CareSlot doesn't try to be a record system. It cares about capacity and contention: slots, rooms, queues and the moment a doctor signs. It reads the medication and allergy list it needs for safety checks through a port, so it could sit next to a record system like MediGraph instead of replacing it.

Java 21, Spring Boot 3.5, PostgreSQL 16, Kafka (KRaft), Redis.

## What it does

**Booking that doesn't double-book.** A slot row has a `capacity` and a `booked` counter, and a booking claims it with one conditional `UPDATE ... SET booked = booked + 1 WHERE booked < limit`. A procedure can need a doctor plus one of several rooms plus one of several machines. CareSlot builds the candidate combinations (capped, see limitations), locks slots in id order so two bookings can't deadlock, and tries each combination in its own savepoint. Either every resource is claimed or none is. A patient can't hold two overlapping appointments either.

**Idempotency.** Booking, rescheduling, check-in and signing need an `Idempotency-Key` header. Requests with the same key are serialised with a Postgres advisory lock. A retry returns the original result with `Idempotent-Replayed: true`, and the same key used for a different request is a `422 idempotency-key-reused`. Booking keys are scoped per patient, so two clients that happen to pick the same key for different patients don't collide.

**Overbooking from the clinic's own no-show rate.** Once a clinic has at least 50 finished visits, the booking limit for a slot is the largest `n` for which the binomial probability that more than `capacity` patients actually show up stays under 25%, capped at +50% of capacity. With no history the limit is the capacity.

**Waitlist auto-fill.** A cancellation writes an event to an outbox in the same transaction. A relay publishes it to Kafka (`careslot.appointments`), and the waitlist consumer books the highest-priority waiting patient whose window fits. Consumers record processed event ids, so a redelivered event doesn't book anyone twice. Patients can join the waitlist themselves but can't give themselves priority.

**Availability.** Weekly templates (an RRULE, day start and end, slot length, capacity) generate slots 28 days ahead, minus holidays and leave. Free slots per doctor and day are cached in Redis as a bitmap, and the database stays the source of truth.

**OPD queue.** Check-in opens 2 hours before the appointment and hands out the next token number for that doctor and day. Checking in twice gives back the same token. `call-next` moves one patient into consult, and only one token per doctor can be called at a time. The ETA uses an exponentially weighted average of that doctor's recent consult lengths. The queue board shows token numbers only, no names.

**Prescribing with safety checks.** A doctor drafts a prescription, and CareSlot checks it against the patient's active medications and allergies (drug–drug, drug–class allergy, duplicates). A severe finding blocks signing unless the doctor gives an override reason of at least 15 characters, which is audited. If the records source is down or times out, the check reports *unchecked* and signing is blocked instead of passing silently. Signing is idempotent: ten concurrent sign requests with the same key produce one signature. A signed prescription renders to a PDF that carries a content SHA-256 and a "sample, not for real prescribing" banner.

**Records port.** `careslot.records.mode=demo` keeps sample meds and allergies in a local table. `fhir` reads `MedicationStatement` and `AllergyIntolerance` from a FHIR R4 server by health-ID identifier, with a timeout.

**FHIR R4 read facade.** `GET /fhir/r4/metadata`, `Patient/{id}`, `Appointment/{id}` and `MedicationRequest?prescription={id}` (a searchset bundle with one resource per line) return `application/fhir+json`. Errors come back as `OperationOutcome`.

**Reminders.** Bookings get reminders 24 h and 2 h before (one whose time has already passed is skipped). Workers claim due reminders with `FOR UPDATE SKIP LOCKED`, so several instances can run without sending twice. Sends are rate-capped and each one runs in its own savepoint. Reminders for cancelled or past appointments are cancelled. The message has the facility and local time only, with no doctor, clinic or patient name. The demo gateway writes to a table with the recipient masked.

**Security and PHI.** It's an OAuth2 resource server (JWT), and roles are scopes: `careslot.admin`, `careslot.front-desk`, `careslot.nurse`, `careslot.doctor`, `careslot.patient`. Patients can only see and book their own appointments. Doctors see their own patients' prescriptions. Name, phone, email and health ID are stored AES-256-GCM encrypted, with the column name as associated data, and search uses an HMAC blind index. Every read of patient details goes to `phi_access`. Bookings are rate-limited per patient (`429` with `Retry-After`).

## Layout

The code is hexagonal, by module: `directory` (facilities, clinics, resources, patients), `scheduling`, `queue`, `prescribing`, `notify`, `interop` (FHIR), and `shared`. Each module has `domain` (plain Java), `application` (services and ports) and `adapter` (web, persistence, gateways). ArchUnit enforces these rules in the build:

- the domain depends only on the JDK;
- application code never imports an adapter;
- one module never touches another module's adapters;
- `shared` depends on no business module;
- there are no cycles between modules and no field injection.

## Running it

You need Docker with Compose, plus `curl`, `jq` and `openssl` for the scripts.

```bash
./scripts/up.sh          # builds the image and starts Postgres, Redis, Kafka, a mock OAuth server and the app
./scripts/demo.sh        # walks the main flows and fails loudly on any unexpected answer
./scripts/bench.sh       # optional, see below
docker compose --env-file ~/.config/careslot/compose.env down -v
```

`up.sh` generates the database password, the Redis password and the PHI key on first run into `~/.config/careslot/compose.env` (mode 600), outside the repo, and reuses them afterwards. Ports bind to 127.0.0.1 only: the API is on 7890 and the mock OAuth server on 7891. The mock server hands a token to any client id, which is fine for a demo and nothing else.

The demo covers:

- 20 clerks racing for one slot: one wins and 19 get `409`;
- a replay, and a key reused for a different request (`422`);
- a cancellation refilled from the waitlist through Kafka;
- a patient booking her own slot, and another patient getting `403`;
- check-in twice returning the same token, call-next, and a queue board without names;
- aspirin drafted on top of warfarin, blocked, then signed with an override, with a replayed sign and the PDF;
- the FHIR reads.

## Tests

`mvn verify` runs 58 unit, property (jqwik) and ArchUnit tests, plus 26 integration tests that start the whole app against real Postgres, Kafka and Redis with Testcontainers. The integration tests include:

- concurrent bookings on one slot never exceed capacity;
- two needs-combinations contending for shared rooms;
- 10 concurrent requests with one idempotency key give one appointment;
- a patient racing herself across two doctors;
- a reschedule that fails keeps the original;
- waitlist priority, and redelivered events;
- 20 concurrent check-ins give distinct tokens;
- concurrent call-next calls one patient;
- concurrent signs give one signature;
- six concurrent reminder workers send each reminder once;
- the rate limit, token rejection, ciphertext at rest and PHI access auditing.

CI (`.github/workflows/ci.yml`) runs the tests, builds the image, and fails on fixable HIGH or CRITICAL findings from Trivy. It then brings up the compose stack and runs the demo and a small bench.

## Numbers

These were measured once on an 8-vCPU dev box, with the app, its dependencies and the curl client all on the same machine, and one app instance. Treat them as a sanity check, not a capacity claim.

- 950 bookings on distinct slots at concurrency 32 took 3.2 s, about 298 req/s. All returned `201`. Latency was p50 82 ms, p95 133 ms, p99 150 ms.
- 200 patients raced for one slot with capacity 30: 30 got `201` and 170 got `409`. Afterwards a query over all 1,112 slots with bookings found none above capacity, and every `booked` counter matched the live appointment rows.

`SimulateUtilisation` (`java -cp target/classes com.careslot.tools.SimulateUtilisation`) compares policies on a simulated clinic: 250 days × 8 slots × capacity 6, seed 42. Its inputs are assumptions, not data from a real clinic.

| policy | no-show 20%, cancel 10%, waitlist fills 60% | no-show 10%, cancel 5%, fills 60% |
|---|---|---|
| no overbooking, no waitlist | 72.2% utilisation | 85.7% |
| waitlist only | 77.4% | 88.7% |
| binomial overbooking only | 82.5% (10.1% of slots overflow) | 85.7% (limit stays at 6) |
| both | 87.4% (16.6% of slots overflow) | 88.7% |

The overflow column is the real cost: on those slots more patients turned up than the slot had room for, and someone waited.

## Limitations

These are known limitations, not a roadmap:

- **Slot granularity.** Rooms and machines are booked per slot of their own template. A procedure that spans two room slots isn't modelled.
- **Combination cap.** At most 3 alternatives per need and 4 combinations per slot are tried. A large "any of these 10 rooms" request can miss a free combination.
- **Availability cache.** The cache can be briefly stale after a booking on another instance. It is only used to display slots; bookings always go to Postgres.
- **No-show counters.** They are lifetime totals and never decay, so a clinic that improves keeps being overbooked for a while.
- **Rate limiter.** It is a fixed one-minute window, `INCR` and `EXPIRE` are not atomic, and it fails open when Redis is down.
- **PHI key.** There is one PHI key and no rotation. The login subject (the IdP's `sub`) is stored in plaintext because tokens are matched on it, and the mock IdP uses an email address as `sub`.
- **FHIR.** The FHIR JSON is hand-written, not HAPI. The facade is read-only and minimal (no search beyond the one parameter, no paging), and the FHIR records adapter reads only the first page of results.
- **PDF.** The PDF is hand-built and the SHA-256 is a fingerprint, not a digital signature.
- **ETA.** The queue ETA is an estimate from recent consult lengths.
- **Reminders.** With a real SMS or email gateway, sending is at-least-once: a crash between the send and the commit resends.
- **Drug data.** The catalogue (25 drugs, 18 interactions) is a sample, not clinically validated.
- **Known CVEs.** `.trivyignore` lists two Spring MVC CVEs whose fix exists only in Spring Framework 7. They need XSLT views or SSE view fragments, and CareSlot uses neither.
