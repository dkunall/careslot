package com.careslot.notify.adapter.persistence;

import com.careslot.notify.application.ReminderStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcReminderStore implements ReminderStore {

    private final JdbcClient jdbc;

    JdbcReminderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Due> claimDue(Instant now, int limit) {
        return jdbc.sql("""
                        select r.id, r.kind, a.id, a.patient_id, a.practitioner_id, lower(a.period), r.attempts
                        from reminder r join appointment a on a.id = r.appointment_id
                        where r.status = 'PENDING' and r.due_at <= ? and a.status = 'BOOKED' and lower(a.period) > ?
                        order by r.due_at
                        limit ?
                        for update of r skip locked""")
                .params(now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), limit)
                .query((rs, n) -> new Due(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, OffsetDateTime.class).toInstant(), rs.getInt(7)))
                .list();
    }

    @Override
    public int cancelStale() {
        return jdbc.sql("""
                        update reminder r set status = 'CANCELLED'
                        from appointment a
                        where a.id = r.appointment_id and r.status = 'PENDING'
                          and (a.status <> 'BOOKED' or lower(a.period) <= now())""").update();
    }

    @Override
    public void markSent(long id, Instant at) {
        jdbc.sql("update reminder set status = 'SENT', sent_at = ?, attempts = attempts + 1 where id = ?")
                .params(at.atOffset(ZoneOffset.UTC), id).update();
    }

    @Override
    public void markFailed(long id, boolean giveUp) {
        jdbc.sql("update reminder set attempts = attempts + 1, status = case when ? then 'FAILED' else status end where id = ?")
                .params(giveUp, id).update();
    }
}
