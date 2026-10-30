package com.careslot.prescribing.domain;

import java.util.Set;

/**
 * What the record system says about the patient right now: active medicine codes and allergies. An allergy
 * is either a drug code or {@code class:<therapeutic class>} (e.g. {@code class:PENICILLIN}).
 */
public record PatientContext(Set<String> activeMedications, Set<String> allergies, String source) {

    public PatientContext {
        activeMedications = Set.copyOf(activeMedications);
        allergies = Set.copyOf(allergies);
    }
}
