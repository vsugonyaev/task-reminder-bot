package com.example.bot;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * Quartz job that sends the reminder message.
 */
public class ReminderJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        try {
            ReminderBot bot = (ReminderBot) context.getScheduler().getContext().get("bot");
            long chatId = context.getMergedJobDataMap().getLong("chatId");
            org.quartz.Scheduler scheduler = context.getScheduler();

            String text =
                    "⏰ Напоминание! ✅🙂✨\n" +
                            "Проверьте и закройте задачи до конца дня 📌🔥🧩\n" +
                            "Если есть блокеры — пишите в чат 🛑🙋";

            bot.sendPinAndAutoUnpinMinutes(scheduler, chatId, text, 2);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
