package com.careslot.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The part of RFC 5545 RRULE that clinic rosters actually use: {@code FREQ=DAILY} or {@code FREQ=WEEKLY},
 * optional {@code INTERVAL} and, for weekly rules, {@code BYDAY}. Anything else (BYMONTHDAY, COUNT, UNTIL,
 * BYSETPOS...) is rejected instead of silently ignored, because a half-understood rule produces wrong slots.
 * The template's validity range plays the role of DTSTART/UNTIL.
 */
public record RecurrenceRule(Frequency frequency, int interval, Set<DayOfWeek> byDay) {

    public enum Frequency { DAILY, WEEKLY }

    private static final Map<String, DayOfWeek> DAYS = Map.of("MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY,
            "WE", DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY);

    public RecurrenceRule {
        if (interval < 1 || interval > 52) {
            throw new IllegalArgumentException("INTERVAL must be 1..52");
        }
        byDay = byDay.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(byDay));
        if (frequency == Frequency.DAILY && !byDay.isEmpty()) {
            throw new IllegalArgumentException("BYDAY is only supported with FREQ=WEEKLY");
        }
    }

    public static RecurrenceRule parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty RRULE");
        }
        String body = text.strip();
        if (body.regionMatches(true, 0, "RRULE:", 0, 6)) {
            body = body.substring(6);
        }
        Frequency freq = null;
        int interval = 1;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : body.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2 || kv[1].isEmpty()) {
                throw new IllegalArgumentException("malformed RRULE part: " + part);
            }
            String key = kv[0].strip().toUpperCase(Locale.ROOT);
            String value = kv[1].strip().toUpperCase(Locale.ROOT);
            switch (key) {
                case "FREQ" -> freq = switch (value) {
                    case "DAILY" -> Frequency.DAILY;
                    case "WEEKLY" -> Frequency.WEEKLY;
                    default -> throw new IllegalArgumentException("unsupported FREQ " + value);
                };
                case "INTERVAL" -> {
                    try {
                        interval = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("INTERVAL is not a number");
                    }
                }
                case "BYDAY" -> {
                    for (String d : value.split(",")) {
                        DayOfWeek day = DAYS.get(d.strip());
                        if (day == null) {
                            throw new IllegalArgumentException("unsupported BYDAY value " + d);
                        }
                        days.add(day);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported RRULE part " + key);
            }
        }
        if (freq == null) {
            throw new IllegalArgumentException("RRULE needs FREQ");
        }
        return new RecurrenceRule(freq, interval, days);
    }

    /**
     * Dates in {@code [from, to]} the rule fires on, counting intervals from {@code anchor} (the first day of
     * the template). Weekly intervals count ISO weeks starting Monday, as RFC 5545 does with WKST=MO.
     */
    public List<LocalDate> expand(LocalDate anchor, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate start = from.isBefore(anchor) ? anchor : from;
        LocalDate anchorWeek = anchor.minusDays(anchor.getDayOfWeek().getValue() - 1L);
        Set<DayOfWeek> days = byDay.isEmpty() ? Set.of(anchor.getDayOfWeek()) : byDay;
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            boolean fires = switch (frequency) {
                case DAILY -> ChronoUnit.DAYS.between(anchor, d) % interval == 0;
                case WEEKLY -> days.contains(d.getDayOfWeek())
                        && ChronoUnit.WEEKS.between(anchorWeek, d.minusDays(d.getDayOfWeek().getValue() - 1L)) % interval == 0;
            };
            if (fires) {
                out.add(d);
            }
        }
        return out;
    }
}
