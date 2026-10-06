package com.example.bot;

import com.example.sprint.SprintSchedule;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Quartz job that fires every day; on the planning day of a sprint warns the team
 * not to close tasks today and keeps the warning pinned until the end of the day.
 */
public class SprintStartJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SprintStartJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            ReminderBot bot = (ReminderBot) context.getScheduler().getContext().get("bot");
            SprintSchedule sprints = (SprintSchedule) context.getScheduler().getContext().get("sprints");
            long chatId = context.getMergedJobDataMap().getLong("chatId");

            LocalDate today = LocalDate.now(ReminderJob.ZONE);
            if (!sprints.isPlanningDay(today)) {
                return;
            }

            bot.sendPinAndAutoUnpin(chatId, ReminderTexts.sprintStart(sprints.sprintOf(today)),
                    ReminderJob.untilEndOfDay());
        } catch (Exception e) {
            log.error("Failed to send sprint start warning", e);
        }
    }
}
