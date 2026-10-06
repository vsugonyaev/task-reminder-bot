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
 * Quartz job that fires every day; on the planning day of a sprint warns the team
 * not to close tasks today and keeps the warning pinned until unpin.time.
 */
public class SprintStartJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SprintStartJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            SchedulerContext ctx = context.getScheduler().getContext();
            ReminderBot bot = (ReminderBot) ctx.get("bot");
            SprintSchedule sprints = (SprintSchedule) ctx.get("sprints");
            AppConfig config = (AppConfig) ctx.get("config");

            LocalDate today = config.today();
            if (!sprints.isPlanningDay(today)) {
                return;
            }

            bot.sendPinAndAutoUnpin(config.chatId(), bot.sprintStartText(today), config.untilUnpinTime());
        } catch (Exception e) {
            log.error("Failed to send sprint start warning", e);
        }
    }
}
