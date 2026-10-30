package com.careslot.scheduling.domain;

/**
 * A clinic's measured no-show rate over its recent finished appointments. Below {@code minObservations}
 * the estimate is not trusted and reported as unknown, so a new clinic is never overbooked on a guess.
 */
public record NoShowEstimate(long attended, long noShows, long minObservations) {

    public long observations() {
        return attended + noShows;
    }

    public boolean known() {
        return observations() >= minObservations && observations() > 0;
    }

    public double rate() {
        return known() ? (double) noShows / observations() : 0.0;
    }
}
