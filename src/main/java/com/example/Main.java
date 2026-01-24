package com.example;

import com.example.bot.ReminderBot;
import com.example.bot.ReminderJob;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.ZoneId;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Starts Telegram bot and Quartz scheduler.
 *
 * Schedule: Mon/Wed/Fri at 16:30 MSK (Europe/Moscow).
 *
 * Required environment variables:
 *  - TG_BOT_TOKEN
 *  - TG_BOT_USERNAME
 *  - TG_CHAT_ID
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("TG_BOT_TOKEN");
        String username = System.getenv("TG_BOT_USERNAME");
        String chatIdStr = System.getenv("TG_CHAT_ID");

        if (token == null || token.isBlank()
                || username == null || username.isBlank()
                || chatIdStr == null || chatIdStr.isBlank()) {
            throw new IllegalStateException(
                    "Set env vars: TG_BOT_TOKEN, TG_BOT_USERNAME, TG_CHAT_ID"
            );
        }

        long chatId = Long.parseLong(chatIdStr);

        // 1) Telegram bot init
        ReminderBot bot = new ReminderBot(token, username);
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bot);

        // 2) Quartz scheduler init
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        bot.setScheduler(scheduler);
        JobDetail job = newJob(ReminderJob.class)
                .withIdentity("reminderJob")
                .usingJobData("chatId", chatId)
                .build();
        job.getJobDataMap().put("bot", bot);
        job.getJobDataMap().put("scheduler", scheduler);

        // Cron: second minute hour day-of-month month day-of-week year(optional)
        // Mon/Wed/Fri at 16:30 MSK
        CronScheduleBuilder cronSchedule = CronScheduleBuilder
                .cronSchedule("0 30 16 ? * MON,TUE,THU,FRI")
                .inTimeZone(java.util.TimeZone.getTimeZone(ZoneId.of("Europe/Moscow")));


        Trigger trigger = newTrigger()
                .withIdentity("reminderTrigger")
                .withSchedule(cronSchedule)
                .startNow()
                .build();

        scheduler.start();
        scheduler.scheduleJob(job, trigger);

        System.out.println("Bot started. Reminders: Mon/Wed/Fri 16:30 MSK (Europe/Moscow).");
        System.out.println("Use /chatid in the target group to get TG_CHAT_ID.");
    }
}
