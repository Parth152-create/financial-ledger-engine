package com.parth.ledger.recurring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V2.6 Recurring Schedule Calculator Calendar & Convergence Unit Tests")
class RecurringScheduleCalculatorTest {

    private RecurringScheduleCalculator calculator;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        // Base reference time: 2026-06-15T12:00:00Z
        fixedClock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
        calculator = new RecurringScheduleCalculator(fixedClock);
    }

    @Test
    @DisplayName("DAILY: advances by exactly 1 calendar day")
    void testDailyAdvancement() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate slot = LocalDate.of(2026, 1, 1);

        LocalDate next = calculator.computeImmediateNextDate(start, slot, RecurringFrequency.DAILY);
        assertThat(next).isEqualTo(LocalDate.of(2026, 1, 2));

        next = calculator.computeImmediateNextDate(start, next, RecurringFrequency.DAILY);
        assertThat(next).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    @DisplayName("WEEKLY: advances by exactly 7 calendar days")
    void testWeeklyAdvancement() {
        LocalDate start = LocalDate.of(2026, 1, 1); // Thursday
        LocalDate slot = LocalDate.of(2026, 1, 1);

        LocalDate next = calculator.computeImmediateNextDate(start, slot, RecurringFrequency.WEEKLY);
        assertThat(next).isEqualTo(LocalDate.of(2026, 1, 8));

        next = calculator.computeImmediateNextDate(start, next, RecurringFrequency.WEEKLY);
        assertThat(next).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    @DisplayName("MONTHLY: preserves original anchor day (31st) across Jan -> Feb -> Mar -> Apr -> May (No Drift)")
    void testMonthlyAnchorDayPreservation31st() {
        LocalDate start = LocalDate.of(2026, 1, 31); // Anchor day = 31

        // Jan 31 -> Feb 28 (non-leap year 2026)
        LocalDate feb = calculator.computeImmediateNextDate(start, start, RecurringFrequency.MONTHLY);
        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));

        // Feb 28 -> Mar 31 (anchor day 31 is restored!)
        LocalDate mar = calculator.computeImmediateNextDate(start, feb, RecurringFrequency.MONTHLY);
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 31));

        // Mar 31 -> Apr 30
        LocalDate apr = calculator.computeImmediateNextDate(start, mar, RecurringFrequency.MONTHLY);
        assertThat(apr).isEqualTo(LocalDate.of(2026, 4, 30));

        // Apr 30 -> May 31 (anchor day 31 is restored again!)
        LocalDate may = calculator.computeImmediateNextDate(start, apr, RecurringFrequency.MONTHLY);
        assertThat(may).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("MONTHLY: leap year leap day (Feb 29) handling on 31st anchor day")
    void testMonthlyLeapYearHandling() {
        LocalDate start = LocalDate.of(2024, 1, 31); // 2024 is leap year

        LocalDate feb = calculator.computeImmediateNextDate(start, start, RecurringFrequency.MONTHLY);
        assertThat(feb).isEqualTo(LocalDate.of(2024, 2, 29));

        LocalDate mar = calculator.computeImmediateNextDate(start, feb, RecurringFrequency.MONTHLY);
        assertThat(mar).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    @DisplayName("MONTHLY: anchor day 30 across August -> Sept -> Oct -> Nov -> Dec -> Jan -> Feb -> Mar")
    void testMonthlyAnchorDayPreservation30th() {
        LocalDate start = LocalDate.of(2025, 8, 30); // Anchor day = 30

        LocalDate sep = calculator.computeImmediateNextDate(start, start, RecurringFrequency.MONTHLY);
        assertThat(sep).isEqualTo(LocalDate.of(2025, 9, 30));

        LocalDate oct = calculator.computeImmediateNextDate(start, sep, RecurringFrequency.MONTHLY);
        assertThat(oct).isEqualTo(LocalDate.of(2025, 10, 30));

        LocalDate nov = calculator.computeImmediateNextDate(start, oct, RecurringFrequency.MONTHLY);
        assertThat(nov).isEqualTo(LocalDate.of(2025, 11, 30));

        LocalDate dec = calculator.computeImmediateNextDate(start, nov, RecurringFrequency.MONTHLY);
        assertThat(dec).isEqualTo(LocalDate.of(2025, 12, 30));

        LocalDate jan = calculator.computeImmediateNextDate(start, dec, RecurringFrequency.MONTHLY);
        assertThat(jan).isEqualTo(LocalDate.of(2026, 1, 30));

        LocalDate feb = calculator.computeImmediateNextDate(start, jan, RecurringFrequency.MONTHLY);
        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));

        LocalDate mar = calculator.computeImmediateNextDate(start, feb, RecurringFrequency.MONTHLY);
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 30));
    }

    @Test
    @DisplayName("Catch-up convergence: multiple missed periods jump directly to next future occurrence without replay")
    void testCatchUpConvergenceAfterDowntime() {
        // Schedule due 2026-01-01, current time is 2026-06-15T12:00:00Z (5+ missed monthly periods)
        LocalDate start = LocalDate.of(2026, 1, 1);
        Instant slotExecuted = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-06-15T12:00:00Z");

        Optional<Instant> nextOccurrence = calculator.calculateNextOccurrence(
                start,
                null,
                RecurringFrequency.MONTHLY,
                slotExecuted,
                now
        );

        assertThat(nextOccurrence).isPresent();
        // Converges to 2026-07-01T00:00:00Z (first future occurrence)
        assertThat(nextOccurrence.get()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    @DisplayName("Inclusive end date: execution on exact end date is eligible, then transitions to complete")
    void testInclusiveEndDateExecution() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);

        // Slot executed was 2026-02-01
        Instant febSlot = Instant.parse("2026-02-01T00:00:00Z");
        Instant now = Instant.parse("2026-02-01T00:05:00Z");

        Optional<Instant> nextOccurrence = calculator.calculateNextOccurrence(
                start,
                end,
                RecurringFrequency.MONTHLY,
                febSlot,
                now
        );

        assertThat(nextOccurrence).isPresent();
        // Next slot is 2026-03-01 (equal to end date, so still eligible)
        assertThat(nextOccurrence.get()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));

        // Now slot 2026-03-01 is executed
        Instant marSlot = Instant.parse("2026-03-01T00:00:00Z");
        now = Instant.parse("2026-03-01T00:05:00Z");

        Optional<Instant> afterEnd = calculator.calculateNextOccurrence(
                start,
                end,
                RecurringFrequency.MONTHLY,
                marSlot,
                now
        );

        // Next candidate would be 2026-04-01 which exceeds 2026-03-01, so empty (COMPLETED)
        assertThat(afterEnd).isEmpty();
    }
}
