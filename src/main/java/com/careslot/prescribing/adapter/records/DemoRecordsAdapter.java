package com.careslot.prescribing.adapter.records;

import com.careslot.prescribing.application.ClinicalRecordsPort;
import com.careslot.prescribing.application.DemoFacts;
import com.careslot.prescribing.domain.PatientContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Demo stand-in for the record system: a local table of synthetic medicines and allergies. */
@Component
@ConditionalOnProperty(name = "careslot.records.mode", havingValue = "demo", matchIfMissing = true)
class DemoRecordsAdapter implements ClinicalRecordsPort, DemoFacts {

    private final JdbcClient jdbc;

    DemoRecordsAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PatientContext read(UUID patientId, String healthId) {
        Set<String> meds = new HashSet<>();
        Set<String> allergies = new HashSet<>();
        jdbc.sql("select kind, code from demo_clinical_fact where patient_id = ?").param(patientId)
                .query(rs -> {
                    (rs.getString(1).equals("MEDICATION") ? meds : allergies).add(rs.getString(2));
                });
        return new PatientContext(meds, allergies, "demo");
    }

    @Override
    public void replace(UUID patientId, Set<String> medications, Set<String> allergies) {
        jdbc.sql("delete from demo_clinical_fact where patient_id = ?").param(patientId).update();
        for (String m : medications) {
            jdbc.sql("insert into demo_clinical_fact (patient_id, kind, code) values (?, 'MEDICATION', ?)")
                    .params(patientId, m).update();
        }
        for (String a : allergies) {
            jdbc.sql("insert into demo_clinical_fact (patient_id, kind, code) values (?, 'ALLERGY', ?)")
                    .params(patientId, a).update();
        }
    }
}
