package com.example;

import com.example.bot.ReminderBot;
import com.example.bot.ReminderJob;
import com.example.bot.SprintStartJob;
import com.example.calendar.ProductionCalendar;
import com.example.sprint.SprintSchedule;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.TimeZone;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Starts Telegram bot and Quartz scheduler.
 *
 * Schedule (MSK, Europe/Moscow):
 *  - planning day (first working day of a sprint) at 10:00 — warning not to close tasks today;
 *  - every other working day of a sprint at 16:30 — reminder to close tasks.
 * Days off are taken from the Russian production calendar.
 *
 * Required environment variables:
 *  - TG_BOT_TOKEN
 *  - TG_CHAT_ID
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final LocalTime REMINDER_TIME = LocalTime.of(16, 30);
    /** Planning day warning goes out in the morning, before anyone touches the board. */
    private static final LocalTime SPRINT_START_TIME = LocalTime.of(10, 0);
    /** First day (Wednesday) of a known sprint; sprints go back to back. */
    private static final LocalDate SPRINT_ANCHOR = LocalDate.of(2026, 10, 7);
    private static final int SPRINT_LENGTH_DAYS = 14;

    public static void main(String[] args) throws Exception {
        String token = System.getenv("TG_BOT_TOKEN");
        String chatIdStr = System.getenv("TG_CHAT_ID");

        if (token == null || token.isBlank() || chatIdStr == null || chatIdStr.isBlank()) {
            throw new IllegalStateException("Set env vars: TG_BOT_TOKEN, TG_CHAT_ID");
        }

        long chatId = Long.parseLong(chatIdStr.trim());

        SprintSchedule sprints = new SprintSchedule(SPRINT_ANCHOR, SPRINT_LENGTH_DAYS, new ProductionCalendar());
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

        ReminderBot bot = new ReminderBot(new OkHttpTelegramClient(token), scheduler, chatId, sprints,
                REMINDER_TIME, SPRINT_START_TIME);
        scheduler.getContext().put("bot", bot);
        scheduler.getContext().put("sprints", sprints);

        TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
        botsApplication.registerBot(token, bot);

        // Both jobs fire every day and decide themselves whether today is their day
        scheduleDaily(scheduler, ReminderJob.class, "reminder", REMINDER_TIME, chatId);
        scheduleDaily(scheduler, SprintStartJob.class, "sprintStart", SPRINT_START_TIME, chatId);
        scheduler.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                scheduler.shutdown();
                botsApplication.close();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));

        SprintSchedule.Sprint sprint = sprints.sprintOf(LocalDate.now(ReminderJob.ZONE));
        log.info("Bot started. Target chat: {}. Planning day warning at {} MSK, reminders at {} MSK",
                chatId, SPRINT_START_TIME, REMINDER_TIME);
        log.info("Current sprint {} — {}, planning day: {}, reminder days: {}", sprint.start(), sprint.end(),
                sprints.planningDay(sprint).orElse(null), sprints.reminderDays(sprint));
        log.info("Use /chatid in the target group to get TG_CHAT_ID, /sprint to see the schedule.");
    }

    private static void scheduleDaily(Scheduler scheduler, Class<? extends Job> jobClass, String name,
                                      LocalTime time, long chatId) throws SchedulerException {
        JobDetail job = newJob(jobClass)
                .withIdentity(name + "Job")
                .usingJobData("chatId", chatId)
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(name + "Trigger")
                .withSchedule(CronScheduleBuilder
                        .dailyAtHourAndMinute(time.getHour(), time.getMinute())
                        .inTimeZone(TimeZone.getTimeZone(ReminderJob.ZONE)))
                .build();

        scheduler.scheduleJob(job, trigger);
    }
}
