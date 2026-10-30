package com.careslot.scheduling.domain;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Strategy: which of the feasible options to try first. The booking service tries them in this order. */
public sealed interface SlotAllocator {

    List<BookingOption> rank(List<BookingOption> options);

    static SlotAllocator named(String name, UUID preferred) {
        return switch (name == null ? "EARLIEST" : name.toUpperCase(java.util.Locale.ROOT)) {
            case "EARLIEST" -> new EarliestFit();
            case "PREFERRED" -> {
                if (preferred == null) {
                    throw new IllegalArgumentException("PREFERRED needs a practitioner");
                }
                yield new PreferredPractitioner(preferred);
            }
            case "LOAD_BALANCED" -> new LoadBalanced();
            default -> throw new IllegalArgumentException("unknown strategy " + name);
        };
    }

    Comparator<BookingOption> BY_START = Comparator.comparing((BookingOption o) -> o.period().start())
            .thenComparing(o -> o.practitionerId().toString());

    /** First free time with any eligible doctor. */
    record EarliestFit() implements SlotAllocator {
        @Override
        public List<BookingOption> rank(List<BookingOption> options) {
            return options.stream().filter(SlotAllocator::feasible).sorted(BY_START).toList();
        }
    }

    /** The patient's own doctor if she has anything, then everyone else by time. */
    record PreferredPractitioner(UUID practitionerId) implements SlotAllocator {
        @Override
        public List<BookingOption> rank(List<BookingOption> options) {
            return options.stream().filter(SlotAllocator::feasible)
                    .sorted(Comparator.comparing((BookingOption o) -> !o.practitionerId().equals(practitionerId))
                            .thenComparing(BY_START))
                    .toList();
        }
    }

    /** Spread walk-ins across the clinic: least-booked doctor that day first, then time. */
    record LoadBalanced() implements SlotAllocator {
        @Override
        public List<BookingOption> rank(List<BookingOption> options) {
            return options.stream().filter(SlotAllocator::feasible)
                    .sorted(Comparator.comparingInt(BookingOption::practitionerLoad).thenComparing(BY_START))
                    .toList();
        }
    }

    private static boolean feasible(BookingOption o) {
        return o.slots().stream().allMatch(SlotRef::hasRoom);
    }
}
