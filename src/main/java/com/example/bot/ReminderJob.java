package com.example.bot;

import com.example.sprint.SprintSchedule;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Quartz job that fires every day; sends the reminder only on sprint reminder days
 * and keeps it pinned until the end of the day.
 */
public class ReminderJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final LocalTime UNPIN_AT = LocalTime.of(23, 59);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            ReminderBot bot = (ReminderBot) context.getScheduler().getContext().get("bot");
            SprintSchedule sprints = (SprintSchedule) context.getScheduler().getContext().get("sprints");
            long chatId = context.getMergedJobDataMap().getLong("chatId");

            LocalDate today = LocalDate.now(ZONE);
            if (!sprints.isReminderDay(today)) {
                log.info("No reminder today ({}): day off or sprint planning day", today);
                return;
            }

            SprintSchedule.Sprint sprint = sprints.sprintOf(today);
            boolean lastDay = sprints.lastReminderDay(today).map(today::equals).orElse(false);

            bot.sendPinAndAutoUnpin(chatId, ReminderTexts.forDay(sprint, today, lastDay), untilEndOfDay());
        } catch (Exception e) {
            log.error("Failed to send reminder", e);
        }
    }

    static Duration untilEndOfDay() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        Duration d = Duration.between(now, now.with(UNPIN_AT));
        return d.isNegative() ? Duration.ZERO : d;
    }
}
