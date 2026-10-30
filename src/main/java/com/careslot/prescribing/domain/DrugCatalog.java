package com.careslot.prescribing.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Drugs plus the interaction graph as adjacency sets: drug code to (other drug code to interaction).
 * Pair lookup is O(1) and checking a prescription of k items against m current medicines is O(k(k+m)).
 * Built once at startup from the sample tables and replaced whole on reload, never mutated in place.
 */
public final class DrugCatalog {

    private final Map<String, Drug> drugs;
    private final Map<String, Map<String, Interaction>> adjacency;

    public DrugCatalog(Collection<Drug> drugs, Collection<Interaction> interactions) {
        Map<String, Drug> d = new HashMap<>();
        drugs.forEach(x -> d.put(x.code(), x));
        Map<String, Map<String, Interaction>> adj = new HashMap<>();
        for (Interaction i : interactions) {
            if (!d.containsKey(i.drugA()) || !d.containsKey(i.drugB()) || i.drugA().equals(i.drugB())) {
                throw new IllegalArgumentException("interaction refers to unknown drug or itself: " + i);
            }
            adj.computeIfAbsent(i.drugA(), k -> new HashMap<>()).put(i.drugB(), i);
            adj.computeIfAbsent(i.drugB(), k -> new HashMap<>()).put(i.drugA(), i);
        }
        this.drugs = Map.copyOf(d);
        Map<String, Map<String, Interaction>> frozen = new HashMap<>();
        adj.forEach((k, v) -> frozen.put(k, Map.copyOf(v)));
        this.adjacency = Map.copyOf(frozen);
    }

    public Optional<Drug> drug(String code) {
        return Optional.ofNullable(drugs.get(code));
    }

    public Optional<Interaction> between(String a, String b) {
        return Optional.ofNullable(adjacency.getOrDefault(a, Map.of()).get(b));
    }

    public Set<String> codes() {
        return drugs.keySet();
    }

    public int interactionCount() {
        return adjacency.values().stream().mapToInt(Map::size).sum() / 2;
    }
}
