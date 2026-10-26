package dev.wochenchallenge;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** When does the current challenge week end? */
final class WeekCalc {
    private WeekCalc() {}

    /**
     * Next week boundary (day + hour) strictly after {@code from}. If that is less than
     * {@code minDays} away (e.g. the plugin was installed on a Saturday), the week after is used.
     */
    static long nextEnd(long from, DayOfWeek day, int hour, ZoneId zone, int minDays) {
        ZonedDateTime now = Instant.ofEpochMilli(from).atZone(zone);
        ZonedDateTime end = now.with(TemporalAdjusters.nextOrSame(day))
                .withHour(Math.max(0, Math.min(23, hour))).withMinute(0).withSecond(0).withNano(0);
        if (!end.isAfter(now)) end = end.plusWeeks(1);
        if (Duration.between(now, end).toDays() < minDays) end = end.plusWeeks(1);
        return end.toInstant().toEpochMilli();
    }
}
