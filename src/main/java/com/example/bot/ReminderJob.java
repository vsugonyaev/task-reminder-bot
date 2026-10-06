package com.example.bot;

import com.example.config.AppConfig;
import com.example.sprint.SprintSchedule;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Quartz job that fires every day; sends the reminder only on sprint reminder days
 * and keeps it pinned until unpin.time.
 */
public class ReminderJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            SchedulerContext ctx = context.getScheduler().getContext();
            ReminderBot bot = (ReminderBot) ctx.get("bot");
            SprintSchedule sprints = (SprintSchedule) ctx.get("sprints");
            AppConfig config = (AppConfig) ctx.get("config");

            LocalDate today = config.today();
            if (!sprints.isReminderDay(today)) {
                log.info("No reminder today ({}): day off or sprint planning day", today);
                return;
            }

            bot.sendPinAndAutoUnpin(config.chatId(), bot.reminderText(today), config.untilUnpinTime());
        } catch (Exception e) {
            log.error("Failed to send reminder", e);
        }
    }
}
