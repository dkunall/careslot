package com.careslot.scheduling.domain;

/** Small, exact-enough binomial helpers for clinic-sized n (tens, not millions). */
public final class Binomial {

    private Binomial() {
    }

    /** P(X >= k) for X ~ Binomial(n, p). */
    public static double upperTail(int n, double p, int k) {
        if (k <= 0) {
            return 1.0;
        }
        if (k > n) {
            return 0.0;
        }
        if (p <= 0) {
            return 0.0;
        }
        if (p >= 1) {
            return 1.0;
        }
        // pmf(0) = (1-p)^n, pmf(i+1) = pmf(i) * (n-i)/(i+1) * p/(1-p); sum the lower part and subtract.
        double q = 1 - p;
        double pmf = Math.pow(q, n);
        double lower = 0;
        for (int i = 0; i < k; i++) {
            lower += pmf;
            pmf = pmf * (n - i) / (i + 1) * p / q;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - lower));
    }
}
