package com.parth.ledger.recurring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Dedicated calendar arithmetic and convergence helper for recurring transfer schedules.
 *
 * Rules:
 * - DAILY: date + 1 day
 * - WEEKLY: date + 1 week
 * - MONTHLY: date + 1 calendar month with LAST VALID DAY OF MONTH semantics preserving original anchor day.
 *   Example: Jan 31 -> Feb 28 -> Mar 31 -> Apr 30. (No drift).
 * - Missed Executions / Convergence: exactly one catch-up execution is performed for the missed slot,
 *   followed by advancement to the next future scheduled occurrence without cascading replay.
 * - End Date: inclusive semantics. If scheduled on or before end_date, slot is eligible.
 *   When next occurrence exceeds end_date, schedule transitions to COMPLETED.
 */
@Component
public class RecurringScheduleCalculator {

    private final Clock clock;

    @Autowired
    public RecurringScheduleCalculator(@Autowired(required = false) Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * Calculates the initial execution Instant from startDate in UTC.
     *
     * @param startDate The user-specified start date.
     * @return UTC Instant at 00:00:00 on the start date.
     */
    public Instant calculateInitialExecution(LocalDate startDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        return startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Computes the immediate single-step next occurrence date from a given slot date,
     * preserving the original anchor day of month for MONTHLY schedules.
     *
     * @param startDate       The original start date of the schedule (provides anchor day).
     * @param currentSlotDate The current slot date being advanced.
     * @param frequency       DAILY, WEEKLY, or MONTHLY.
     * @return The immediate next LocalDate.
     */
    public LocalDate computeImmediateNextDate(LocalDate startDate, LocalDate currentSlotDate, RecurringFrequency frequency) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(currentSlotDate, "currentSlotDate must not be null");
        Objects.requireNonNull(frequency, "frequency must not be null");

        return switch (frequency) {
            case DAILY -> currentSlotDate.plusDays(1);
            case WEEKLY -> currentSlotDate.plusWeeks(1);
            case MONTHLY -> {
                int anchorDay = startDate.getDayOfMonth();
                YearMonth nextYm = YearMonth.from(currentSlotDate).plusMonths(1);
                int day = Math.min(anchorDay, nextYm.lengthOfMonth());
                yield nextYm.atDay(day);
            }
        };
    }

    /**
     * Calculates the next future execution instant following the execution of currentSlotInstant.
     * If the next immediate occurrence is in the future relative to asOf, returns it.
     * If multiple historical periods were missed (e.g. system downtime), advances iteratively
     * until converging to the next future occurrence.
     *
     * @param startDate          Original schedule start date.
     * @param endDate            Optional inclusive end date.
     * @param frequency          DAILY, WEEKLY, or MONTHLY.
     * @param currentSlotInstant The instant of the slot that was just executed.
     * @param asOf               Reference instant for current time (null uses configured clock).
     * @return Optional containing the next execution Instant, or Optional.empty() if expired past endDate.
     */
    public Optional<Instant> calculateNextOccurrence(LocalDate startDate,
                                                     LocalDate endDate,
                                                     RecurringFrequency frequency,
                                                     Instant currentSlotInstant,
                                                     Instant asOf) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(frequency, "frequency must not be null");
        Objects.requireNonNull(currentSlotInstant, "currentSlotInstant must not be null");

        Instant referenceTime = asOf != null ? asOf : clock.instant();
        LocalDate slotDate = currentSlotInstant.atZone(ZoneOffset.UTC).toLocalDate();

        LocalDate candidate = computeImmediateNextDate(startDate, slotDate, frequency);
        Instant candidateInstant = candidate.atStartOfDay(ZoneOffset.UTC).toInstant();

        // Convergence: if multiple past periods were missed, advance until strictly in the future
        while (!candidateInstant.isAfter(referenceTime)) {
            candidate = computeImmediateNextDate(startDate, candidate, frequency);
            candidateInstant = candidate.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        // Inclusive end-date validation: if candidate date is strictly after endDate, schedule is complete
        if (endDate != null && candidate.isAfter(endDate)) {
            return Optional.empty();
        }

        return Optional.of(candidateInstant);
    }
}
