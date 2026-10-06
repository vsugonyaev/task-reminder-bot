package com.example.bot;

import com.example.config.AppConfig;
import com.example.meeting.Meeting;
import com.example.sprint.SprintSchedule;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Quartz job that fires every day a few minutes before a meeting; if the meeting takes place today,
 * sends a reminder with a link to the room and deletes it after a while.
 */
public class MeetingJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(MeetingJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            SchedulerContext ctx = context.getScheduler().getContext();
            ReminderBot bot = (ReminderBot) ctx.get("bot");
            SprintSchedule sprints = (SprintSchedule) ctx.get("sprints");
            AppConfig config = (AppConfig) ctx.get("config");
            Meeting.Type type = Meeting.Type.valueOf(context.getMergedJobDataMap().getString("type"));

            LocalDate today = config.today();
            if (!type.occursOn(sprints, today)) {
                return;
            }

            bot.sendMeetingReminder(config.chatId(), config.meetings().get(type));
        } catch (Exception e) {
            log.error("Failed to send meeting reminder", e);
        }
    }
}
