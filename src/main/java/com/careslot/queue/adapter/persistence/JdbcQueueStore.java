package com.careslot.queue.adapter.persistence;

import com.careslot.queue.application.QueueStore;
import com.careslot.queue.application.QueueToken;
import com.careslot.queue.domain.ConsultDuration;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcQueueStore implements QueueStore {

    private static final String COLS = """
            id, appointment_id, practitioner_id, day, number, status, checked_in_at, called_at, done_at""";

    private static final RowMapper<QueueToken> TOKEN = (rs, n) -> new QueueToken(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getInt(5),
            rs.getString(6), instant(rs, 7), instant(rs, 8), instant(rs, 9));

    private final JdbcClient jdbc;

    JdbcQueueStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    @Override
    public Optional<QueueToken> forAppointment(UUID appointmentId) {
        return jdbc.sql("select " + COLS + " from queue_token where appointment_id = ?").param(appointmentId)
                .query(TOKEN).optional();
    }

    @Override
    public int nextNumber(UUID practitionerId, LocalDate day) {
        return jdbc.sql("""
                        insert into queue_counter (practitioner_id, day, last_number) values (?, ?, 1)
                        on conflict (practitioner_id, day) do update set last_number = queue_counter.last_number + 1
                        returning last_number""")
                .params(practitionerId, day).query(Integer.class).single();
    }

    @Override
    public boolean lockDay(UUID practitionerId, LocalDate day) {
        return jdbc.sql("select 1 from queue_counter where practitioner_id = ? and day = ? for update")
                .params(practitionerId, day).query(Integer.class).optional().isPresent();
    }

    @Override
    public QueueToken insert(QueueToken t) {
        jdbc.sql("""
                        insert into queue_token (id, appointment_id, practitioner_id, day, number, status, checked_in_at)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(t.id(), t.appointmentId(), t.practitionerId(), t.day(), t.number(), t.status(),
                        ts(t.checkedInAt()))
                .update();
        return t;
    }

    @Override
    public List<QueueToken> day(UUID practitionerId, LocalDate day) {
        return jdbc.sql("select " + COLS + " from queue_token where practitioner_id = ? and day = ? order by number")
                .params(practitionerId, day).query(TOKEN).list();
    }

    @Override
    public Optional<QueueToken> called(UUID practitionerId, LocalDate day) {
        return jdbc.sql("select " + COLS + " from queue_token where practitioner_id = ? and day = ? and status = 'CALLED'")
                .params(practitionerId, day).query(TOKEN).optional();
    }

    @Override
    public Optional<QueueToken> firstWaiting(UUID practitionerId, LocalDate day) {
        return jdbc.sql("select " + COLS + """
                         from queue_token where practitioner_id = ? and day = ? and status = 'WAITING'
                        order by number limit 1""")
                .params(practitionerId, day).query(TOKEN).optional();
    }

    @Override
    public void markCalled(UUID tokenId, Instant at) {
        jdbc.sql("update queue_token set status = 'CALLED', called_at = ? where id = ? and status = 'WAITING'")
                .params(ts(at), tokenId).update();
    }

    @Override
    public void markDone(UUID tokenId, Instant at) {
        jdbc.sql("update queue_token set status = 'DONE', done_at = ? where id = ? and status = 'CALLED'")
                .params(ts(at), tokenId).update();
    }

    @Override
    public Optional<ConsultDuration> lockStats(UUID practitionerId) {
        return jdbc.sql("select mean_seconds, samples from consult_stats where practitioner_id = ? for update")
                .param(practitionerId).query((rs, n) -> new ConsultDuration(rs.getDouble(1), rs.getLong(2))).optional();
    }

    @Override
    public Optional<ConsultDuration> stats(UUID practitionerId) {
        return jdbc.sql("select mean_seconds, samples from consult_stats where practitioner_id = ?")
                .param(practitionerId).query((rs, n) -> new ConsultDuration(rs.getDouble(1), rs.getLong(2))).optional();
    }

    @Override
    public void saveStats(UUID practitionerId, ConsultDuration d) {
        jdbc.sql("""
                        insert into consult_stats (practitioner_id, mean_seconds, samples) values (?, ?, ?)
                        on conflict (practitioner_id) do update set mean_seconds = excluded.mean_seconds,
                            samples = excluded.samples""")
                .params(practitionerId, d.seconds(), d.samples()).update();
    }

    @Override
    public void saveNote(UUID appointmentId, byte[] encrypted, String author) {
        jdbc.sql("insert into consult_note (appointment_id, note_enc, author) values (?, ?, ?)")
                .params(appointmentId, encrypted, author).update();
    }
}
