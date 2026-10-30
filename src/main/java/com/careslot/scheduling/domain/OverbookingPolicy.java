package com.careslot.scheduling.domain;

/** Strategy: how many bookings a slot of a given capacity may take. Only consult slots are overbooked. */
public sealed interface OverbookingPolicy {

    int bookingLimit(int capacity, NoShowEstimate estimate);

    /** Exactly {@code extra} more than capacity, regardless of history; {@code extra = 0} means never overbook. */
    record Fixed(int extra) implements OverbookingPolicy {
        public Fixed {
            if (extra < 0) {
                throw new IllegalArgumentException("extra must be >= 0");
            }
        }

        @Override
        public int bookingLimit(int capacity, NoShowEstimate estimate) {
            return capacity + extra;
        }
    }

    /**
     * Treats each booked patient as an independent Bernoulli arrival with the clinic's show rate, and books the
     * largest {@code n} for which {@code P(arrivals > capacity) <= maxOverflowProbability}. The extra is also
     * capped at {@code maxExtraFraction} of capacity (at least one seat once overbooking applies), because the
     * independence assumption is optimistic: no-shows cluster on rainy days and festival weeks.
     * Cost is O(capacity) per candidate n, using the binomial pmf recurrence.
     */
    record BinomialNoShow(double maxOverflowProbability, double maxExtraFraction) implements OverbookingPolicy {
        public BinomialNoShow {
            if (maxOverflowProbability <= 0 || maxOverflowProbability >= 1) {
                throw new IllegalArgumentException("maxOverflowProbability must be in (0,1)");
            }
            if (maxExtraFraction < 0) {
                throw new IllegalArgumentException("maxExtraFraction must be >= 0");
            }
        }

        @Override
        public int bookingLimit(int capacity, NoShowEstimate estimate) {
            if (!estimate.known() || estimate.rate() <= 0) {
                return capacity;
            }
            int cap = Math.max(1, (int) Math.floor(capacity * maxExtraFraction));
            double show = 1.0 - estimate.rate();
            int best = capacity;
            for (int n = capacity + 1; n <= capacity + cap; n++) {
                if (Binomial.upperTail(n, show, capacity + 1) <= maxOverflowProbability) {
                    best = n;
                } else {
                    break; // the tail only grows with n
                }
            }
            return best;
        }
    }
}
