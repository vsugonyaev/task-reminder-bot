package com.example;

import com.example.artifacts.ArtifactRepository;
import com.example.bot.MeetingJob;
import com.example.bot.ReminderBot;
import com.example.bot.ReminderJob;
import com.example.bot.SprintStartJob;
import com.example.calendar.ProductionCalendar;
import com.example.config.AppConfig;
import com.example.meeting.Meeting;
import com.example.sprint.SprintSchedule;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.time.LocalTime;
import java.util.TimeZone;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Starts Telegram bot and Quartz scheduler.
 *
 * Schedule (times and sprints are configured in config.properties, see {@link AppConfig}):
 *  - planning day (first working day of a sprint) — warning not to close tasks today;
 *  - every other working day of a sprint — reminder to close tasks;
 *  - a few minutes before team meetings (planning / daily / review) — reminder with a link to the room.
 * Days off are taken from the Russian production calendar.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        log.info("Loaded {}", config);

        SprintSchedule sprints = new SprintSchedule(
                config.sprintAnchor(), config.sprintLengthDays(), new ProductionCalendar());
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

        ArtifactRepository artifactRepository = new ArtifactRepository(config.artifactsDbPath());
        ReminderBot bot = new ReminderBot(new OkHttpTelegramClient(config.botToken()), scheduler, config, sprints,
                artifactRepository);
        scheduler.getContext().put("bot", bot);
        scheduler.getContext().put("sprints", sprints);
        scheduler.getContext().put("config", config);

        TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
        botsApplication.registerBot(config.botToken(), bot);
        bot.registerCommands();

        // Both jobs fire every day and decide themselves whether today is their day
        scheduleDaily(scheduler, ReminderJob.class, "reminder", config.reminderTime(), config);
        scheduleDaily(scheduler, SprintStartJob.class, "sprintStart", config.sprintStartTime(), config);
        for (Meeting meeting : config.meetings().values()) {
            LocalTime remindAt = meeting.time().minus(config.meetingRemindBefore());
            JobDetail job = newJob(MeetingJob.class)
                    .withIdentity("meeting_" + meeting.type().key() + "Job")
                    .usingJobData("type", meeting.type().name())
                    .build();
            scheduler.scheduleJob(job, dailyTrigger("meeting_" + meeting.type().key(), remindAt, config));
        }
        scheduler.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                scheduler.shutdown();
                botsApplication.close();
                bot.shutdown();
                artifactRepository.close();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));

        SprintSchedule.Sprint sprint = sprints.sprintOf(config.today());
        log.info("Bot started. Current sprint {} — {}, planning day: {}, reminder days: {}",
                sprint.start(), sprint.end(), sprints.planningDay(sprint).orElse(null), sprints.reminderDays(sprint));
        log.info("Use /chatid in the target group to get the chat id, /sprint to see the schedule.");
    }

    private static void scheduleDaily(Scheduler scheduler, Class<? extends Job> jobClass, String name,
                                      LocalTime time, AppConfig config) throws SchedulerException {
        JobDetail job = newJob(jobClass)
                .withIdentity(name + "Job")
                .build();

        scheduler.scheduleJob(job, dailyTrigger(name, time, config));
    }

    private static Trigger dailyTrigger(String name, LocalTime time, AppConfig config) {
        return newTrigger()
                .withIdentity(name + "Trigger")
                .withSchedule(CronScheduleBuilder
                        .dailyAtHourAndMinute(time.getHour(), time.getMinute())
                        .inTimeZone(TimeZone.getTimeZone(config.zone())))
                .build();
    }
}
