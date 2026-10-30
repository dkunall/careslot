package com.careslot.prescribing.adapter.persistence;

import com.careslot.prescribing.application.Prescription;
import com.careslot.prescribing.application.PrescriptionStore;
import com.careslot.prescribing.domain.PrescriptionItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPrescriptionStore implements PrescriptionStore {

    private static final String COLS = """
            id, patient_id, appointment_id, prescriber, status, safety::text, override_reason, sign_key,
            content_sha256, signed_at, created_at""";

    private final JdbcClient jdbc;

    JdbcPrescriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    @Override
    public void insertDraft(Prescription p) {
        jdbc.sql("""
                        insert into prescription (id, patient_id, appointment_id, prescriber, status, safety, created_at)
                        values (?, ?, ?, ?, 'DRAFT', ?::jsonb, ?)""")
                .params(p.id(), p.patientId(), p.appointmentId(), p.prescriber(), p.safetyJson(), ts(p.createdAt()))
                .update();
        int line = 1;
        for (PrescriptionItem i : p.items()) {
            jdbc.sql("""
                            insert into prescription_item (prescription_id, line, drug_code, dose, frequency, days)
                            values (?, ?, ?, ?, ?, ?)""")
                    .params(p.id(), line++, i.drugCode(), i.dose(), i.frequency(), i.days()).update();
        }
    }

    @Override
    public Optional<Prescription> find(UUID id) {
        return query("select " + COLS + " from prescription where id = ?", id);
    }

    @Override
    public Optional<Prescription> lock(UUID id) {
        return query("select " + COLS + " from prescription where id = ? for update", id);
    }

    private Optional<Prescription> query(String sql, UUID id) {
        List<PrescriptionItem> items = jdbc.sql("""
                        select drug_code, dose, frequency, days from prescription_item
                        where prescription_id = ? order by line""").param(id)
                .query((rs, n) -> new PrescriptionItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                .list();
        return jdbc.sql(sql).param(id).query((rs, n) -> new Prescription(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), instant(rs, 10), instant(rs, 11),
                items)).optional();
    }

    @Override
    public void updateSafety(UUID id, String safetyJson) {
        jdbc.sql("update prescription set safety = ?::jsonb where id = ?").params(safetyJson, id).update();
    }

    @Override
    public void markSigned(UUID id, String signKey, String overrideReason, String contentSha256, Instant signedAt,
                           String safetyJson) {
        jdbc.sql("""
                        update prescription set status = 'SIGNED', sign_key = ?, override_reason = ?, content_sha256 = ?,
                            signed_at = ?, safety = ?::jsonb, version = version + 1
                        where id = ? and status = 'DRAFT'""")
                .params(signKey, overrideReason, contentSha256, ts(signedAt), safetyJson, id).update();
    }
}
