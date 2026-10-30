-- CareSlot schema. Times are timestamptz; ranges are half-open [start, end).
create extension if not exists btree_gist;

create table facility (
    id   text primary key,
    name text not null,
    zone text not null
);

create table holiday (
    facility_id text not null references facility (id),
    day         date not null,
    name        text not null,
    primary key (facility_id, day)
);

create table clinic (
    id          text primary key,
    facility_id text not null references facility (id),
    name        text not null
);

-- Finished-visit counters per clinic: imported history plus every COMPLETED / NO_SHOW since.
create table clinic_attendance (
    clinic_id  text primary key references clinic (id),
    attended   bigint not null default 0 check (attended >= 0),
    no_shows   bigint not null default 0 check (no_shows >= 0),
    updated_at timestamptz not null default now()
);

create table resource (
    id            uuid primary key,
    facility_id   text not null references facility (id),
    clinic_id     text references clinic (id),
    kind          text not null check (kind in ('PRACTITIONER', 'ROOM', 'EQUIPMENT')),
    name          text not null,
    login_subject text unique,
    created_at    timestamptz not null default now(),
    check (kind <> 'PRACTITIONER' or clinic_id is not null)
);

create table schedule_template (
    id            uuid primary key,
    resource_id   uuid not null references resource (id),
    rrule         text not null,
    day_start     time not null,
    day_end       time not null,
    slot_minutes  int  not null,
    capacity      int  not null,
    valid_from    date not null,
    valid_to      date,
    created_at    timestamptz not null default now(),
    superseded_at timestamptz
);

-- A resource cannot be on two overlapping leaves; the database refuses it, not just the service.
create table resource_leave (
    id          uuid primary key,
    resource_id uuid not null references resource (id),
    period      tstzrange not null,
    reason      text not null,
    created_by  text not null,
    created_at  timestamptz not null default now(),
    exclude using gist (resource_id with =, period with &&)
);

-- One resource never has two overlapping slots. booked may exceed capacity only by the overbooking limit,
-- which the booking statement applies (booked < limit); capacity itself is what the clinic can see at once.
create table slot (
    id          bigserial primary key,
    resource_id uuid      not null references resource (id),
    template_id uuid references schedule_template (id),
    period      tstzrange not null,
    capacity    int       not null check (capacity > 0),
    booked      int       not null default 0 check (booked >= 0),
    blocked     boolean   not null default false,
    version     int       not null default 0,
    exclude using gist (resource_id with =, period with &&)
);
create index slot_resource_start on slot (resource_id, lower(period));

-- PHI columns are AES-GCM ciphertext. health_id_hash is an HMAC blind index so lookups by health ID work
-- without storing it in clear.
create table patient (
    id             uuid primary key,
    health_id_hash bytea not null unique,
    health_id_enc  bytea not null,
    name_enc       bytea not null,
    phone_enc      bytea,
    email_enc      bytea,
    birth_year     int,
    login_subject  text unique,
    created_at     timestamptz not null default now()
);

create table appointment (
    id                uuid primary key,
    patient_id        uuid      not null references patient (id),
    practitioner_id   uuid      not null references resource (id),
    clinic_id         text      not null references clinic (id),
    kind              text      not null check (kind in ('CONSULT', 'PROCEDURE')),
    period            tstzrange not null,
    status            text      not null check (status in ('REQUESTED', 'BOOKED', 'CHECKED_IN', 'IN_CONSULT',
                                                           'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    overbooked        boolean   not null default false,
    idempotency_scope text      not null,
    idempotency_key   text      not null,
    request_hash      text      not null,
    rescheduled_from  uuid references appointment (id),
    cancel_reason     text,
    created_by        text      not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    version           int       not null default 0,
    unique (idempotency_scope, idempotency_key),
    -- a patient cannot hold two overlapping live appointments, however the requests race
    exclude using gist (patient_id with =, period with &&)
        where (status in ('REQUESTED', 'BOOKED', 'CHECKED_IN', 'IN_CONSULT'))
);
create index appointment_patient on appointment (patient_id, lower(period));
create index appointment_practitioner on appointment (practitioner_id, lower(period));
create index appointment_due_no_show on appointment (upper(period)) where status = 'BOOKED';

create table appointment_slot (
    appointment_id uuid   not null references appointment (id),
    slot_id        bigint not null references slot (id),
    released_at    timestamptz,
    primary key (appointment_id, slot_id)
);
create index appointment_slot_slot on appointment_slot (slot_id);

create table waitlist_entry (
    id              uuid primary key,
    patient_id      uuid      not null references patient (id),
    practitioner_id uuid      not null references resource (id),
    "window"        tstzrange not null,
    priority        int       not null check (priority between 0 and 9),
    status          text      not null check (status in ('WAITING', 'BOOKED', 'WITHDRAWN', 'EXPIRED')),
    appointment_id  uuid references appointment (id),
    created_by      text      not null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
create index waitlist_waiting on waitlist_entry (practitioner_id, priority desc, created_at) where status = 'WAITING';
create unique index waitlist_one_per_patient on waitlist_entry (patient_id, practitioner_id) where status = 'WAITING';

create table queue_counter (
    practitioner_id uuid not null references resource (id),
    day             date not null,
    last_number     int  not null,
    primary key (practitioner_id, day)
);

create table queue_token (
    id              uuid primary key,
    appointment_id  uuid not null unique references appointment (id),
    practitioner_id uuid not null references resource (id),
    day             date not null,
    number          int  not null,
    status          text not null check (status in ('WAITING', 'CALLED', 'DONE')),
    checked_in_at   timestamptz not null,
    called_at       timestamptz,
    done_at         timestamptz,
    unique (practitioner_id, day, number)
);
create index queue_waiting on queue_token (practitioner_id, day, number) where status = 'WAITING';

create table consult_stats (
    practitioner_id uuid primary key references resource (id),
    mean_seconds    double precision not null,
    samples         bigint not null
);

create table consult_note (
    appointment_id uuid primary key references appointment (id),
    note_enc       bytea not null,
    author         text  not null,
    created_at     timestamptz not null default now()
);

-- SAMPLE drug data (see V2). Not clinically validated.
create table drug (
    code              text primary key,
    name              text not null,
    therapeutic_class text not null
);

create table drug_interaction (
    drug_a   text not null references drug (code),
    drug_b   text not null references drug (code),
    severity text not null check (severity in ('MINOR', 'MODERATE', 'SEVERE')),
    note     text not null,
    primary key (drug_a, drug_b),
    check (drug_a < drug_b)
);

-- Stand-in for the record system (MediGraph) in demo mode: active medicines and allergies per patient.
create table demo_clinical_fact (
    patient_id uuid not null references patient (id),
    kind       text not null check (kind in ('MEDICATION', 'ALLERGY')),
    code       text not null,
    primary key (patient_id, kind, code)
);

create table prescription (
    id              uuid primary key,
    patient_id      uuid not null references patient (id),
    appointment_id  uuid references appointment (id),
    prescriber      text not null,
    status          text not null check (status in ('DRAFT', 'SIGNED')),
    safety          jsonb not null,
    override_reason text,
    sign_key        text unique,
    content_sha256  text,
    signed_at       timestamptz,
    created_at      timestamptz not null default now(),
    version         int not null default 0
);

create table prescription_item (
    prescription_id uuid not null references prescription (id),
    line            int  not null,
    drug_code       text not null references drug (code),
    dose            text not null,
    frequency       text not null,
    days            int  not null,
    primary key (prescription_id, line)
);

-- Transactional outbox: written in the same transaction as the change, relayed to Kafka afterwards.
create table outbox (
    id           bigserial primary key,
    event_id     uuid not null unique,
    topic        text not null,
    msg_key      text not null,
    payload      jsonb not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);
create index outbox_unpublished on outbox (id) where published_at is null;

-- Consumers record what they have handled, so a redelivered event is a no-op.
create table processed_event (
    consumer     text not null,
    event_id     uuid not null,
    processed_at timestamptz not null default now(),
    primary key (consumer, event_id)
);

create table reminder (
    id             bigserial primary key,
    appointment_id uuid not null references appointment (id),
    kind           text not null,
    due_at         timestamptz not null,
    status         text not null check (status in ('PENDING', 'SENT', 'CANCELLED', 'FAILED')),
    attempts       int  not null default 0,
    sent_at        timestamptz,
    unique (appointment_id, kind)
);
create index reminder_due on reminder (due_at) where status = 'PENDING';

-- What the demo notification gateway "sent". A real deployment replaces the gateway, not this table's readers.
create table notification_log (
    id          bigserial primary key,
    reminder_id bigint not null unique references reminder (id),
    channel     text not null,
    recipient   text not null,
    body        text not null,
    sent_at     timestamptz not null default now()
);

-- Who read or changed which patient's data.
create table phi_access (
    id         bigserial primary key,
    actor      text not null,
    roles      text not null,
    patient_id uuid,
    action     text not null,
    at         timestamptz not null default now()
);
create index phi_access_patient on phi_access (patient_id, at);
