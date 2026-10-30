package com.careslot.scheduling.adapter.persistence;

import com.careslot.scheduling.application.AppointmentKind;
import com.careslot.scheduling.application.AppointmentRow;
import com.careslot.scheduling.application.AppointmentStore;
import com.careslot.scheduling.domain.AppointmentStatus;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.NoShowEstimate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAppointmentStore implements AppointmentStore {

    private static final String LIVE = "('REQUESTED', 'BOOKED', 'CHECKED_IN', 'IN_CONSULT')";
    private static final String SELECT = """
            select id, patient_id, practitioner_id, clinic_id, kind, lower(period), upper(period), status, overbooked,
                   idempotency_scope, idempotency_key, request_hash, rescheduled_from, created_by, version
            from appointment""";

    private final JdbcClient jdbc;

    JdbcAppointmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockIdempotencyKey(String scope, String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))").param(scope + "\u0000" + key)
                .query((rs, n) -> 1).list();
    }

    @Override
    public Optional<AppointmentRow> byIdempotencyKey(String scope, String key) {
        return jdbc.sql(SELECT + " where idempotency_scope = ? and idempotency_key = ?").params(scope, key)
                .query(JdbcAppointmentStore::row).optional();
    }

    @Override
    public void insert(AppointmentRow a) {
        jdbc.sql("""
                insert into appointment (id, patient_id, practitioner_id, clinic_id, kind, period, status, overbooked,
                    idempotency_scope, idempotency_key, request_hash, rescheduled_from, created_by)
                values (?, ?, ?, ?, ?, tstzrange(?, ?, '[)'), ?, ?, ?, ?, ?, ?, ?)""")
                .params(a.id(), a.patientId(), a.practitionerId(), a.clinicId(), a.kind().name(),
                        Pg.ts(a.period().start()), Pg.ts(a.period().end()), a.status().name(), a.overbooked(),
                        a.idempotencyScope(), a.idempotencyKey(), a.requestHash(), a.rescheduledFrom(), a.createdBy())
                .update();
    }

    @Override
    public void linkSlots(UUID appointmentId, Collection<Long> slotIds) {
        for (Long id : slotIds) {
            jdbc.sql("insert into appointment_slot (appointment_id, slot_id) values (?, ?)").params(appointmentId, id)
                    .update();
        }
    }

    @Override
    public Optional<AppointmentRow> find(UUID id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query(JdbcAppointmentStore::row).optional();
    }

    @Override
    public Optional<AppointmentRow> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = ? for update").param(id).query(JdbcAppointmentStore::row).optional();
    }

    @Override
    public void updateStatus(UUID id, AppointmentStatus status, String reason) {
        jdbc.sql("""
                update appointment set status = ?, cancel_reason = coalesce(?, cancel_reason), updated_at = now(),
                       version = version + 1 where id = ?""").params(status.name(), reason, id).update();
    }

    @Override
    public boolean patientBusy(UUID patientId, Interval period) {
        return jdbc.sql("select exists (select 1 from appointment where patient_id = ? and status in " + LIVE
                        + " and period && tstzrange(?, ?, '[)'))")
                .params(patientId, Pg.ts(period.start()), Pg.ts(period.end())).query(Boolean.class).single();
    }

    @Override
    public List<AppointmentRow> forPatient(UUID patientId) {
        return jdbc.sql(SELECT + " where patient_id = ? order by lower(period) desc limit 200").param(patientId)
                .query(JdbcAppointmentStore::row).list();
    }

    @Override
    public List<AppointmentRow> forPractitioner(UUID practitionerId, Interval window) {
        return jdbc.sql(SELECT + " where practitioner_id = ? and period && tstzrange(?, ?, '[)') order by lower(period), id")
                .params(practitionerId, Pg.ts(window.start()), Pg.ts(window.end()))
                .query(JdbcAppointmentStore::row).list();
    }

    @Override
    public List<AppointmentRow> lockOverdue(Instant cutoff, int limit) {
        return jdbc.sql(SELECT + " where status = 'BOOKED' and upper(period) < ? order by upper(period) limit ?"
                        + " for update skip locked")
                .params(Pg.ts(cutoff), limit).query(JdbcAppointmentStore::row).list();
    }

    @Override
    public NoShowEstimate attendance(String clinicId, long minObservations) {
        return jdbc.sql("select attended, no_shows from clinic_attendance where clinic_id = ?").param(clinicId)
                .query((rs, n) -> new NoShowEstimate(rs.getLong(1), rs.getLong(2), minObservations)).optional()
                .orElse(new NoShowEstimate(0, 0, minObservations));
    }

    @Override
    public void countOutcome(String clinicId, boolean attended) {
        jdbc.sql(attended
                        ? "update clinic_attendance set attended = attended + 1, updated_at = now() where clinic_id = ?"
                        : "update clinic_attendance set no_shows = no_shows + 1, updated_at = now() where clinic_id = ?")
                .param(clinicId).update();
    }

    @Override
    public void scheduleReminder(UUID appointmentId, String kind, Instant dueAt) {
        jdbc.sql("""
                insert into reminder (appointment_id, kind, due_at, status) values (?, ?, ?, 'PENDING')
                on conflict (appointment_id, kind) do nothing""").params(appointmentId, kind, Pg.ts(dueAt)).update();
    }

    @Override
    public void cancelReminders(UUID appointmentId) {
        jdbc.sql("update reminder set status = 'CANCELLED' where appointment_id = ? and status = 'PENDING'")
                .param(appointmentId).update();
    }

    @Override
    public int liveCount(UUID practitionerId, LocalDate day, ZoneId zone) {
        return jdbc.sql("select count(*) from appointment where practitioner_id = ? and status in " + LIVE
                        + " and period && tstzrange(?, ?, '[)')")
                .params(practitionerId, Pg.ts(day.atStartOfDay(zone).toInstant()),
                        Pg.ts(day.plusDays(1).atStartOfDay(zone).toInstant()))
                .query(Integer.class).single();
    }

    private static AppointmentRow row(ResultSet rs, int n) throws SQLException {
        return new AppointmentRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), AppointmentKind.valueOf(rs.getString(5)), Pg.interval(rs, 6),
                AppointmentStatus.valueOf(rs.getString(8)), rs.getBoolean(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getObject(13, UUID.class), rs.getString(14), rs.getInt(15));
    }
}
