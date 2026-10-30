package com.careslot.prescribing.application;

import com.careslot.prescribing.domain.DrugCatalog;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/** The current drug catalog, loaded on first use and swapped whole on reload. SAMPLE data, see V2 migration. */
@Component
public class Catalog {

    private final CatalogStore store;
    private final AtomicReference<DrugCatalog> current = new AtomicReference<>();

    Catalog(CatalogStore store) {
        this.store = store;
    }

    public DrugCatalog get() {
        DrugCatalog c = current.get();
        if (c == null) {
            c = reload();
        }
        return c;
    }

    public DrugCatalog reload() {
        DrugCatalog fresh = new DrugCatalog(store.drugs(), store.interactions());
        current.set(fresh);
        return fresh;
    }
}
