package com.careslot.prescribing.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Specification: each rule inspects the proposed items against the patient's context and returns its findings.
 * Rules compose with {@link #and}; the checker is just {@code interactions().and(allergies()).and(duplicates())}.
 * SAMPLE DATA, NOT CLINICALLY VALIDATED: the rules are only as good as the hand-made tables behind them.
 */
@FunctionalInterface
public interface SafetyRule {

    List<Finding> evaluate(List<PrescriptionItem> items, PatientContext patient, DrugCatalog catalog);

    default SafetyRule and(SafetyRule other) {
        return (items, patient, catalog) -> {
            List<Finding> all = new ArrayList<>(evaluate(items, patient, catalog));
            all.addAll(other.evaluate(items, patient, catalog));
            return all;
        };
    }

    static SafetyRule standard() {
        return interactions().and(allergies()).and(duplicateTherapy());
    }

    /** Every pair among the new items, and every new item against every active medicine. */
    static SafetyRule interactions() {
        return (items, patient, catalog) -> {
            List<String> proposed = items.stream().map(PrescriptionItem::drugCode).distinct().toList();
            List<Finding> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < proposed.size(); i++) {
                String a = proposed.get(i);
                List<String> others = new ArrayList<>(proposed.subList(i + 1, proposed.size()));
                others.addAll(patient.activeMedications());
                for (String b : others) {
                    if (a.equals(b)) {
                        continue;
                    }
                    String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                    if (!seen.add(pair)) {
                        continue;
                    }
                    catalog.between(a, b).ifPresent(x -> out.add(new Finding("interaction", x.severity(),
                            List.of(a, b), x.note())));
                }
            }
            return out;
        };
    }

    /** Direct drug allergy or allergy to the drug's class; always SEVERE. */
    static SafetyRule allergies() {
        return (items, patient, catalog) -> {
            List<Finding> out = new ArrayList<>();
            for (PrescriptionItem item : items) {
                var drug = catalog.drug(item.drugCode()).orElseThrow();
                if (patient.allergies().contains(drug.code())) {
                    out.add(new Finding("allergy", Severity.SEVERE, List.of(drug.code()),
                            "recorded allergy to " + drug.name()));
                } else if (patient.allergies().contains("class:" + drug.therapeuticClass())) {
                    out.add(new Finding("allergy", Severity.SEVERE, List.of(drug.code()),
                            "recorded allergy to class " + drug.therapeuticClass()));
                }
            }
            return out;
        };
    }

    /**
     * Two medicines of the same class (new + new, or new + already taking). Prescribing a medicine the patient
     * already takes is reported too: it is usually a refill typed as a new line, but sometimes a double dose.
     */
    static SafetyRule duplicateTherapy() {
        return (items, patient, catalog) -> {
            Map<String, Set<String>> byClass = new TreeMap<>();
            Set<String> proposedClasses = new LinkedHashSet<>();
            for (PrescriptionItem item : items) {
                var d = catalog.drug(item.drugCode()).orElseThrow();
                byClass.computeIfAbsent(d.therapeuticClass(), k -> new LinkedHashSet<>()).add(d.code());
                proposedClasses.add(d.therapeuticClass());
            }
            for (String active : patient.activeMedications()) {
                catalog.drug(active).ifPresent(d -> {
                    if (proposedClasses.contains(d.therapeuticClass())) {
                        byClass.get(d.therapeuticClass()).add(d.code());
                    }
                });
            }
            List<Finding> out = new ArrayList<>();
            byClass.forEach((cls, codes) -> {
                long sameDrugTwice = items.stream().map(PrescriptionItem::drugCode).filter(codes::contains).count();
                if (codes.size() > 1 || sameDrugTwice > 1) {
                    out.add(new Finding("duplicate-therapy", Severity.MODERATE, List.copyOf(codes),
                            "more than one " + cls + " medicine"));
                } else if (codes.size() == 1 && patient.activeMedications().containsAll(codes)) {
                    out.add(new Finding("duplicate-therapy", Severity.MINOR, List.copyOf(codes),
                            "patient already takes this medicine"));
                }
            });
            return out;
        };
    }
}
