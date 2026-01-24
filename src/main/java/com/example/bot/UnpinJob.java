package com.example.bot;

import org.quartz.Job;
import org.quartz.JobExecutionContext;

public class UnpinJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
        try {
            ReminderBot bot = (ReminderBot) context.getScheduler().getContext().get("bot");
            long chatId = context.getMergedJobDataMap().getLong("chatId");
            int messageId = context.getMergedJobDataMap().getInt("messageId");

            bot.unpinMessage(chatId, messageId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
