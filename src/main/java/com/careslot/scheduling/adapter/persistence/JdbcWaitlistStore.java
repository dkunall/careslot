package com.careslot.scheduling.adapter.persistence;

import com.careslot.scheduling.application.WaitlistStore;
import com.careslot.scheduling.domain.Interval;
import com.careslot.scheduling.domain.WaitlistEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcWaitlistStore implements WaitlistStore {

    private static final String SELECT = """
            select id, patient_id, practitioner_id, lower("window"), upper("window"), priority, created_at, status,
                   appointment_id from waitlist_entry""";

    private final JdbcClient jdbc;

    JdbcWaitlistStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(WaitlistEntry e, String createdBy) {
        return jdbc.sql("""
                insert into waitlist_entry (id, patient_id, practitioner_id, "window", priority, status, created_by, created_at)
                values (?, ?, ?, tstzrange(?, ?, '[)'), ?, 'WAITING', ?, ?)
                on conflict (patient_id, practitioner_id) where status = 'WAITING' do nothing""")
                .params(e.id(), e.patientId(), e.practitionerId(), Pg.ts(e.window().start()), Pg.ts(e.window().end()),
                        e.priority(), createdBy, Pg.ts(e.createdAt())).update() == 1;
    }

    @Override
    public Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " where id = ?").param(id).query(JdbcWaitlistStore::row).optional();
    }

    @Override
    public boolean withdraw(UUID id) {
        return jdbc.sql("update waitlist_entry set status = 'WITHDRAWN', updated_at = now() where id = ? and status = 'WAITING'")
                .param(id).update() == 1;
    }

    @Override
    public List<WaitlistEntry> lockCandidates(UUID practitionerId, Interval freed) {
        return jdbc.sql(SELECT + """
                 where practitioner_id = ? and status = 'WAITING' and "window" @> tstzrange(?, ?, '[)')
                order by priority desc, created_at, id limit 50 for update skip locked""")
                .params(practitionerId, Pg.ts(freed.start()), Pg.ts(freed.end()))
                .query(JdbcWaitlistStore::row).list().stream().map(Row::entry).toList();
    }

    @Override
    public void markBooked(UUID id, UUID appointmentId) {
        jdbc.sql("update waitlist_entry set status = 'BOOKED', appointment_id = ?, updated_at = now() where id = ?")
                .params(appointmentId, id).update();
    }

    @Override
    public List<Row> forPractitioner(UUID practitionerId) {
        return jdbc.sql(SELECT + " where practitioner_id = ? order by status, priority desc, created_at limit 500")
                .param(practitionerId).query(JdbcWaitlistStore::row).list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(new WaitlistEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), Pg.interval(rs, 4), rs.getInt(6), Pg.instant(rs, 7)),
                rs.getString(8), rs.getObject(9, UUID.class));
    }
}
