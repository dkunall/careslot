package com.careslot.scheduling.domain;

import java.util.UUID;

/** A slot as the allocator sees it: who it belongs to, how full it is and how full it may get. */
public record SlotRef(long id, UUID resourceId, ResourceKind kind, Interval period, int booked, int limit) {

    public boolean hasRoom() {
        return booked < limit;
    }
}
