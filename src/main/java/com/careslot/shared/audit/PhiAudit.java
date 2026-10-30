package com.careslot.shared.audit;

import com.careslot.shared.security.Actor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Access log for patient data: every read or change of a patient's identity, notes or prescriptions gets a
 * row with who, in which roles, and what. Written in the same transaction as the read path's query, so a
 * read that is served is a read that is logged.
 */
@Component
public class PhiAudit {

    private final JdbcClient jdbc;

    PhiAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Actor actor, UUID patientId, String action) {
        jdbc.sql("insert into phi_access (actor, roles, patient_id, action) values (?, ?, ?, ?)")
                .params(actor.subject(), actor.rolesText(), patientId, action).update();
    }
}
