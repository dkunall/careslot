package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.Drug;
import com.careslot.prescribing.domain.Interaction;

import java.util.List;

public interface CatalogStore {

    List<Drug> drugs();

    List<Interaction> interactions();
}
