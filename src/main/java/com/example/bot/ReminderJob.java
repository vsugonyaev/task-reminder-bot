package com.example.bot;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * Quartz job that sends the reminder message.
 */
public class ReminderJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        ReminderBot bot = (ReminderBot) context.getMergedJobDataMap().get("bot");
        long chatId = context.getMergedJobDataMap().getLong("chatId");
        org.quartz.Scheduler scheduler = (org.quartz.Scheduler) context.getMergedJobDataMap().get("scheduler");

        String text =
                "⏰ Напоминание! ✅🙂✨\n" +
                        "Проверьте и закройте задачи до конца дня 📌🔥🧩\n" +
                        "Если есть блокеры — пишите в чат 🛑🙋";

        bot.sendPinAndAutoUnpin(scheduler, chatId, text, 2);
    }
}
