package com.careslot.prescribing.domain;

import java.util.List;

/** Applies the composed rules. Unknown drug codes are rejected up front by {@link PrescriptionBuilder}. */
public final class InteractionChecker {

    private final SafetyRule rules;

    public InteractionChecker(SafetyRule rules) {
        this.rules = rules;
    }

    public SafetyResult check(List<PrescriptionItem> items, PatientContext patient, DrugCatalog catalog) {
        return new SafetyResult(true, rules.evaluate(items, patient, catalog), patient.source());
    }
}
