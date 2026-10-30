package com.careslot.scheduling.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Who gets a freed slot: highest priority, then longest waiting, then id so the order is total and two
 * replicas reading the same rows agree. A priority queue because the matcher usually needs only the head
 * and moves to the next one only if booking the head fails.
 */
public final class WaitlistRanking {

    public static final Comparator<WaitlistEntry> ORDER = Comparator.comparingInt(WaitlistEntry::priority).reversed()
            .thenComparing(WaitlistEntry::createdAt)
            .thenComparing(e -> e.id().toString());

    private WaitlistRanking() {
    }

    public static PriorityQueue<WaitlistEntry> candidates(Collection<WaitlistEntry> entries, UUID practitioner,
                                                          Interval freed) {
        PriorityQueue<WaitlistEntry> pq = new PriorityQueue<>(ORDER);
        for (WaitlistEntry e : entries) {
            if (e.accepts(practitioner, freed)) {
                pq.add(e);
            }
        }
        return pq;
    }

    public static List<WaitlistEntry> drain(PriorityQueue<WaitlistEntry> pq) {
        List<WaitlistEntry> out = new ArrayList<>(pq.size());
        while (!pq.isEmpty()) {
            out.add(pq.poll());
        }
        return out;
    }
}
