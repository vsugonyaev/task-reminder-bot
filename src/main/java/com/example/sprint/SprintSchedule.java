package com.example.sprint;

import com.example.calendar.ProductionCalendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fixed-length sprints going back to back from an anchor date.
 *
 * The first working day of a sprint is the planning day: tasks must not be closed,
 * so the team gets a warning instead of a reminder.
 * Reminders to close tasks are sent on every other working day of the sprint.
 */
public class SprintSchedule {

    public record Sprint(int number, LocalDate start, LocalDate end) {}

    private final LocalDate anchor;
    private final int lengthDays;
    private final ProductionCalendar calendar;

    public SprintSchedule(LocalDate anchor, int lengthDays, ProductionCalendar calendar) {
        this.anchor = anchor;
        this.lengthDays = lengthDays;
        this.calendar = calendar;
    }

    /** Sprint containing the date; number is relative to the anchor sprint (which is 1). */
    public Sprint sprintOf(LocalDate date) {
        long index = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), lengthDays);
        LocalDate start = anchor.plusDays(index * lengthDays);
        return new Sprint((int) index + 1, start, start.plusDays(lengthDays - 1));
    }

    /** First working day of the sprint (empty if the whole sprint is days off). */
    public Optional<LocalDate> planningDay(Sprint sprint) {
        for (LocalDate d = sprint.start(); !d.isAfter(sprint.end()); d = d.plusDays(1)) {
            if (calendar.isWorkingDay(d)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    public boolean isPlanningDay(LocalDate date) {
        return planningDay(sprintOf(date)).map(date::equals).orElse(false);
    }

    public boolean isReminderDay(LocalDate date) {
        return calendar.isWorkingDay(date) && !isPlanningDay(date);
    }

    /** Last reminder day of the sprint containing the date (empty if there are none). */
    public Optional<LocalDate> lastReminderDay(LocalDate date) {
        List<LocalDate> days = reminderDays(sprintOf(date));
        return days.isEmpty() ? Optional.empty() : Optional.of(days.getLast());
    }

    public List<LocalDate> reminderDays(Sprint sprint) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = sprint.start(); !d.isAfter(sprint.end()); d = d.plusDays(1)) {
            if (isReminderDay(d)) {
                days.add(d);
            }
        }
        return days;
    }
}
