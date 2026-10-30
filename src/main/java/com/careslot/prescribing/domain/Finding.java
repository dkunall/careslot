package com.careslot.prescribing.domain;

import java.util.List;

public record Finding(String rule, Severity severity, List<String> drugs, String message) {

    public Finding {
        drugs = List.copyOf(drugs);
    }
}
