package com.careslot.directory.adapter.persistence;

import com.careslot.directory.application.Clinic;
import com.careslot.directory.application.DirectoryStore;
import com.careslot.directory.application.Facility;
import com.careslot.directory.application.Resource;
import com.careslot.scheduling.domain.ResourceKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JdbcDirectoryStore implements DirectoryStore {

    private final JdbcClient jdbc;

    JdbcDirectoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertFacility(Facility f) {
        return jdbc.sql("insert into facility (id, name, zone) values (?, ?, ?) on conflict do nothing")
                .params(f.id(), f.name(), f.zone().getId()).update() == 1;
    }

    @Override
    public boolean insertClinic(Clinic c) {
        int n = jdbc.sql("insert into clinic (id, facility_id, name) values (?, ?, ?) on conflict do nothing")
                .params(c.id(), c.facilityId(), c.name()).update();
        if (n == 1) {
            jdbc.sql("insert into clinic_attendance (clinic_id) values (?)").param(c.id()).update();
        }
        return n == 1;
    }

    @Override
    public void insertResource(Resource r) {
        jdbc.sql("insert into resource (id, facility_id, clinic_id, kind, name, login_subject) values (?, ?, ?, ?, ?, ?)")
                .params(r.id(), r.facilityId(), r.clinicId(), r.kind().name(), r.name(), r.loginSubject()).update();
    }

    @Override
    public Optional<Facility> facility(String id) {
        return jdbc.sql("select id, name, zone from facility where id = ?").param(id)
                .query((rs, n) -> new Facility(rs.getString(1), rs.getString(2), ZoneId.of(rs.getString(3)))).optional();
    }

    @Override
    public Optional<Clinic> clinic(String id) {
        return jdbc.sql("select id, facility_id, name from clinic where id = ?").param(id)
                .query((rs, n) -> new Clinic(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
    }

    @Override
    public Optional<Resource> resource(UUID id) {
        return jdbc.sql("select id, facility_id, clinic_id, kind, name, login_subject from resource where id = ?")
                .param(id).query(JdbcDirectoryStore::resource).optional();
    }

    @Override
    public List<Resource> practitioners(String clinicId) {
        return jdbc.sql("""
                select id, facility_id, clinic_id, kind, name, login_subject from resource
                where clinic_id = ? and kind = 'PRACTITIONER' order by id""")
                .param(clinicId).query(JdbcDirectoryStore::resource).list();
    }

    @Override
    public boolean addHoliday(String facilityId, LocalDate day, String name) {
        return jdbc.sql("insert into holiday (facility_id, day, name) values (?, ?, ?) on conflict do nothing")
                .params(facilityId, day, name).update() == 1;
    }

    @Override
    public Set<LocalDate> holidays(String facilityId, LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("select day from holiday where facility_id = ? and day between ? and ?")
                .params(facilityId, from, to).query((rs, n) -> rs.getObject(1, LocalDate.class)).list());
    }

    @Override
    public void addAttendanceHistory(String clinicId, long attended, long noShows) {
        jdbc.sql("""
                update clinic_attendance set attended = attended + ?, no_shows = no_shows + ?, updated_at = now()
                where clinic_id = ?""").params(attended, noShows, clinicId).update();
    }

    @Override
    public Optional<UUID> insertPatient(EncryptedPatient p) {
        int n = jdbc.sql("""
                insert into patient (id, health_id_hash, health_id_enc, name_enc, phone_enc, email_enc, birth_year, login_subject)
                values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (health_id_hash) do nothing""")
                .params(p.id(), p.healthIdHash(), p.healthId(), p.name(), p.phone(), p.email(), p.birthYear(),
                        p.loginSubject()).update();
        return n == 1 ? Optional.of(p.id()) : Optional.empty();
    }

    @Override
    public Optional<UUID> patientIdByHealthIdHash(byte[] hash) {
        return jdbc.sql("select id from patient where health_id_hash = ?").param(hash)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    @Override
    public Optional<EncryptedPatient> patient(UUID id) {
        return jdbc.sql("""
                select id, health_id_hash, health_id_enc, name_enc, phone_enc, email_enc, birth_year, login_subject
                from patient where id = ?""").param(id)
                .query((rs, n) -> new EncryptedPatient(rs.getObject(1, UUID.class), rs.getBytes(2), rs.getBytes(3),
                        rs.getBytes(4), rs.getBytes(5), rs.getBytes(6), (Integer) rs.getObject(7), rs.getString(8)))
                .optional();
    }

    @Override
    public Optional<UUID> patientIdForSubject(String subject) {
        return jdbc.sql("select id from patient where login_subject = ?").param(subject)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    @Override
    public Optional<UUID> practitionerIdForSubject(String subject) {
        return jdbc.sql("select id from resource where login_subject = ? and kind = 'PRACTITIONER'").param(subject)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    private static Resource resource(ResultSet rs, int n) throws SQLException {
        return new Resource(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                ResourceKind.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6));
    }
}
