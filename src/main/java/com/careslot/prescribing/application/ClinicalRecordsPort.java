package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.PatientContext;

import java.util.UUID;

/**
 * Where the patient's active medicines and allergies come from. CareSlot does not own clinical records: in
 * production this is a FHIR R4 server (MediGraph or any other), in the demo a local stand-in table.
 */
public interface ClinicalRecordsPort {

    /** @throws RecordsUnavailableException when the source cannot be read; the caller then fails closed. */
    PatientContext read(UUID patientId, String healthId);

    final class RecordsUnavailableException extends RuntimeException {
        public RecordsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
