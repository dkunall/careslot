package com.careslot.tools;

import com.careslot.scheduling.domain.NoShowEstimate;
import com.careslot.scheduling.domain.OverbookingPolicy;
import com.careslot.scheduling.domain.UtilisationSimulator;
import com.careslot.scheduling.domain.UtilisationSimulator.Params;
import com.careslot.scheduling.domain.UtilisationSimulator.Result;

import java.util.Locale;

/**
 * Prints the policy comparison quoted in the README. Run with
 * {@code java -cp target/classes com.careslot.tools.SimulateUtilisation [noShowRate] [cancelRate] [waitlistFill]}.
 * The rates are inputs, not facts about any clinic; the defaults are stated in the README next to the output.
 */
public final class SimulateUtilisation {

    public static void main(String[] args) {
        double noShow = args.length > 0 ? Double.parseDouble(args[0]) : 0.20;
        double cancel = args.length > 1 ? Double.parseDouble(args[1]) : 0.10;
        double fill = args.length > 2 ? Double.parseDouble(args[2]) : 0.60;
        Params p = new Params(250, 8, 6, noShow, cancel, fill, 42L);
        // the estimate a clinic with this rate would have after 1,000 finished visits
        NoShowEstimate est = new NoShowEstimate(Math.round(1000 * (1 - noShow)), Math.round(1000 * noShow), 200);
        OverbookingPolicy none = new OverbookingPolicy.Fixed(0);
        OverbookingPolicy binomial = new OverbookingPolicy.BinomialNoShow(0.25, 0.5);
        System.out.printf(Locale.ROOT, "inputs: noShow=%.2f cancel=%.2f waitlistFill=%.2f, %d days x %d slots x capacity %d, seed %d%n",
                noShow, cancel, fill, p.days(), p.slotsPerDay(), p.capacity(), p.seed());
        System.out.printf(Locale.ROOT, "binomial limit for capacity %d: %d%n", p.capacity(), binomial.bookingLimit(p.capacity(), est));
        row("no overbooking, no waitlist", UtilisationSimulator.run(p, none, false, est));
        row("no overbooking, waitlist", UtilisationSimulator.run(p, none, true, est));
        row("binomial overbooking, no waitlist", UtilisationSimulator.run(p, binomial, false, est));
        row("binomial overbooking + waitlist", UtilisationSimulator.run(p, binomial, true, est));
    }

    private static void row(String name, Result r) {
        System.out.printf(Locale.ROOT, "%-36s utilisation %5.1f%%  slots with overflow %5.1f%%  overflow patients %d%n",
                name, 100 * r.utilisation(), 100 * r.overflowSlotShare(), r.overflowPatients());
    }
}
