package com.careslot.prescribing.adapter.persistence;

import com.careslot.prescribing.application.CatalogStore;
import com.careslot.prescribing.domain.Drug;
import com.careslot.prescribing.domain.Interaction;
import com.careslot.prescribing.domain.Severity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class JdbcCatalogStore implements CatalogStore {

    private final JdbcClient jdbc;

    JdbcCatalogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Drug> drugs() {
        return jdbc.sql("select code, name, therapeutic_class from drug")
                .query((rs, n) -> new Drug(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    @Override
    public List<Interaction> interactions() {
        return jdbc.sql("select drug_a, drug_b, severity, note from drug_interaction")
                .query((rs, n) -> new Interaction(rs.getString(1), rs.getString(2), Severity.valueOf(rs.getString(3)),
                        rs.getString(4))).list();
    }
}
