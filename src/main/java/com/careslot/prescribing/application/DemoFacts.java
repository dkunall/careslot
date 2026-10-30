package com.careslot.prescribing.application;

import java.util.Set;
import java.util.UUID;

/** Write side of the demo records stand-in. Only present when careslot.records.mode=demo. */
public interface DemoFacts {

    void replace(UUID patientId, Set<String> medications, Set<String> allergies);
}
