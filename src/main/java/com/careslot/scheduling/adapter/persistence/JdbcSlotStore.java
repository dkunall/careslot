package com.careslot.scheduling.adapter.persistence;

import com.careslot.scheduling.application.SlotRow;
import com.careslot.scheduling.application.SlotStore;
import com.careslot.scheduling.application.TemplateRow;
import com.careslot.scheduling.domain.Interval;
import com.careslot.shared.domain.ResourceKind;
import com.careslot.scheduling.domain.SlotPlanner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcSlotStore implements SlotStore {

    private static final String SLOT_COLS = """
            s.id, s.resource_id, r.kind, lower(s.period), upper(s.period), s.capacity, s.booked, s.blocked""";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    JdbcSlotStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    @Override
    public void insertTemplate(TemplateRow t) {
        jdbc.sql("""
                insert into schedule_template (id, resource_id, rrule, day_start, day_end, slot_minutes, capacity,
                                               valid_from, valid_to) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(t.id(), t.resourceId(), t.rrule(), t.dayStart(), t.dayEnd(), t.slotMinutes(), t.capacity(),
                        t.validFrom(), t.validTo()).update();
    }

    @Override
    public Optional<TemplateRow> template(UUID id) {
        return jdbc.sql(TEMPLATE_SELECT + " where id = ?").param(id).query(JdbcSlotStore::template).optional();
    }

    @Override
    public List<TemplateRow> activeTemplates() {
        return jdbc.sql(TEMPLATE_SELECT + " where superseded_at is null and (valid_to is null or valid_to >= current_date)")
                .query(JdbcSlotStore::template).list();
    }

    private static final String TEMPLATE_SELECT = """
            select id, resource_id, rrule, day_start, day_end, slot_minutes, capacity, valid_from, valid_to,
                   superseded_at is not null from schedule_template""";

    private static TemplateRow template(ResultSet rs, int n) throws SQLException {
        return new TemplateRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, LocalTime.class), rs.getObject(5, LocalTime.class), rs.getInt(6), rs.getInt(7),
                rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class), rs.getBoolean(10));
    }

    @Override
    public void supersede(UUID id, LocalDate validTo) {
        jdbc.sql("update schedule_template set superseded_at = now(), valid_to = ? where id = ?")
                .params(validTo, id).update();
    }

    @Override
    public int insertSlots(UUID resourceId, UUID templateId, List<SlotPlanner.PlannedSlot> slots) {
        if (slots.isEmpty()) {
            return 0;
        }
        int[][] counts = template.batchUpdate("""
                insert into slot (resource_id, template_id, period, capacity)
                values (?, ?, tstzrange(?, ?, '[)'), ?) on conflict do nothing""", slots, 500, (ps, s) -> {
            ps.setObject(1, resourceId);
            ps.setObject(2, templateId);
            ps.setObject(3, Pg.ts(s.period().start()));
            ps.setObject(4, Pg.ts(s.period().end()));
            ps.setInt(5, s.capacity());
        });
        // pgjdbc reports the real per-row count (0 when ON CONFLICT skipped it)
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).map(c -> Math.max(c, 0)).sum();
    }

    @Override
    public int deleteFreeSlotsFrom(UUID templateId, Instant from) {
        return jdbc.sql("""
                delete from slot s where s.template_id = ? and lower(s.period) >= ? and s.booked = 0
                and not exists (select 1 from appointment_slot a where a.slot_id = s.id)""")
                .params(templateId, Pg.ts(from)).update();
    }

    @Override
    public int countBookedSlotsFrom(UUID templateId, Instant from) {
        return jdbc.sql("select count(*) from slot where template_id = ? and lower(period) >= ? and booked > 0")
                .params(templateId, Pg.ts(from)).query(Integer.class).single();
    }

    @Override
    public List<Interval> leaves(UUID resourceId, Instant from, Instant to) {
        return jdbc.sql("""
                select lower(period), upper(period) from resource_leave
                where resource_id = ? and period && tstzrange(?, ?, '[)')""")
                .params(resourceId, Pg.ts(from), Pg.ts(to)).query((rs, n) -> Pg.interval(rs, 1)).list();
    }

    @Override
    public void insertLeave(UUID id, UUID resourceId, Interval period, String reason, String createdBy) {
        jdbc.sql("""
                insert into resource_leave (id, resource_id, period, reason, created_by)
                values (?, ?, tstzrange(?, ?, '[)'), ?, ?)""")
                .params(id, resourceId, Pg.ts(period.start()), Pg.ts(period.end()), reason, createdBy).update();
    }

    @Override
    public List<SlotRow> lockSlotsOverlapping(UUID resourceId, Interval period) {
        return jdbc.sql("select " + SLOT_COLS + """
                 from slot s join resource r on r.id = s.resource_id
                where s.resource_id = ? and s.period && tstzrange(?, ?, '[)') order by s.id for update of s""")
                .params(resourceId, Pg.ts(period.start()), Pg.ts(period.end())).query(JdbcSlotStore::slot).list();
    }

    @Override
    public void block(Collection<Long> slotIds) {
        if (!slotIds.isEmpty()) {
            jdbc.sql("update slot set blocked = true, version = version + 1 where id = any(?)")
                    .param(slotIds.toArray(Long[]::new)).update();
        }
    }

    @Override
    public List<SlotRow> slots(Collection<UUID> resourceIds, Interval window) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + SLOT_COLS + """
                 from slot s join resource r on r.id = s.resource_id
                where s.resource_id = any(?) and s.period <@ tstzrange(?, ?, '[)') order by lower(s.period), s.id""")
                .params(resourceIds.toArray(UUID[]::new), Pg.ts(window.start()), Pg.ts(window.end()))
                .query(JdbcSlotStore::slot).list();
    }

    @Override
    public int tryReserve(long slotId, int limit) {
        return jdbc.sql("""
                update slot set booked = booked + 1, version = version + 1
                where id = ? and not blocked and booked < ? returning booked""")
                .params(slotId, limit).query(Integer.class).optional().orElse(-1);
    }

    @Override
    public int release(UUID appointmentId) {
        List<Long> held = heldSlots(appointmentId);
        if (held.isEmpty()) {
            return 0;
        }
        // same lock order as booking (ascending slot id), so a cancel and a booking cannot deadlock
        jdbc.sql("select id from slot where id = any(?) order by id for update")
                .param(held.toArray(Long[]::new)).query(Long.class).list();
        jdbc.sql("update appointment_slot set released_at = now() where appointment_id = ? and released_at is null")
                .param(appointmentId).update();
        return jdbc.sql("update slot set booked = booked - 1, version = version + 1 where id = any(?) and booked > 0")
                .param(held.toArray(Long[]::new)).update();
    }

    @Override
    public List<Long> heldSlots(UUID appointmentId) {
        return jdbc.sql("select slot_id from appointment_slot where appointment_id = ? and released_at is null order by slot_id")
                .param(appointmentId).query(Long.class).list();
    }

    private static SlotRow slot(ResultSet rs, int n) throws SQLException {
        return new SlotRow(rs.getLong(1), rs.getObject(2, UUID.class), ResourceKind.valueOf(rs.getString(3)),
                Pg.interval(rs, 4), rs.getInt(6), rs.getInt(7), rs.getBoolean(8));
    }
}
