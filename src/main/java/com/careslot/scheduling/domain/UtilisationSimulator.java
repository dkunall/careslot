package com.careslot.scheduling.domain;

import java.util.SplittableRandom;

/**
 * Monte Carlo model of one consult clinic, used to compare policies, not to predict a real clinic.
 * Assumptions, all stated so nobody reads the output as a measurement of a hospital:
 * demand always exceeds supply (every slot is booked up to its limit), patients cancel or no-show independently
 * with the given rates, a cancelled booking is refilled from the waitlist with probability
 * {@code waitlistFill} (the refill patient has the same no-show rate), and arrivals beyond capacity are seen
 * late ("overflow") rather than turned away.
 */
public final class UtilisationSimulator {

    public record Params(int days, int slotsPerDay, int capacity, double noShowRate, double cancelRate,
                         double waitlistFill, long seed) {
    }

    public record Result(long capacitySeats, long seen, long overflowPatients, long overflowSlots, long slots) {
        public double utilisation() {
            return (double) seen / capacitySeats;
        }

        public double overflowSlotShare() {
            return (double) overflowSlots / slots;
        }
    }

    private UtilisationSimulator() {
    }

    public static Result run(Params p, OverbookingPolicy policy, boolean waitlist, NoShowEstimate estimate) {
        SplittableRandom rnd = new SplittableRandom(p.seed());
        int limit = policy.bookingLimit(p.capacity(), estimate);
        long seats = 0, seen = 0, overflowPatients = 0, overflowSlots = 0, slots = 0;
        for (int d = 0; d < p.days(); d++) {
            for (int s = 0; s < p.slotsPerDay(); s++) {
                int arrivals = 0;
                for (int b = 0; b < limit; b++) {
                    boolean cancelled = rnd.nextDouble() < p.cancelRate();
                    if (cancelled) {
                        if (!waitlist || rnd.nextDouble() >= p.waitlistFill()) {
                            continue; // the seat stays empty
                        }
                    }
                    if (rnd.nextDouble() >= p.noShowRate()) {
                        arrivals++;
                    }
                }
                seats += p.capacity();
                seen += Math.min(arrivals, p.capacity());
                if (arrivals > p.capacity()) {
                    overflowSlots++;
                    overflowPatients += arrivals - p.capacity();
                }
                slots++;
            }
        }
        return new Result(seats, seen, overflowPatients, overflowSlots, slots);
    }
}
