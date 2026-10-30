package com.careslot.prescribing.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builder that refuses to produce a prescription it would be unsafe to even check. */
public final class PrescriptionBuilder {

    public static final int MAX_ITEMS = 20;

    public record Draft(UUID patientId, UUID appointmentId, String prescriber, List<PrescriptionItem> items) {
    }

    private final DrugCatalog catalog;
    private UUID patientId;
    private UUID appointmentId;
    private String prescriber;
    private final List<PrescriptionItem> items = new ArrayList<>();

    public PrescriptionBuilder(DrugCatalog catalog) {
        this.catalog = catalog;
    }

    public PrescriptionBuilder patient(UUID id) {
        this.patientId = id;
        return this;
    }

    public PrescriptionBuilder appointment(UUID id) {
        this.appointmentId = id;
        return this;
    }

    public PrescriptionBuilder prescriber(String subject) {
        this.prescriber = subject;
        return this;
    }

    public PrescriptionBuilder item(PrescriptionItem item) {
        if (catalog.drug(item.drugCode()).isEmpty()) {
            throw new IllegalArgumentException("unknown drug code " + item.drugCode());
        }
        items.add(item);
        return this;
    }

    public Draft build() {
        if (patientId == null || prescriber == null) {
            throw new IllegalArgumentException("patient and prescriber are required");
        }
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a prescription needs 1.." + MAX_ITEMS + " items");
        }
        return new Draft(patientId, appointmentId, prescriber, List.copyOf(items));
    }
}
