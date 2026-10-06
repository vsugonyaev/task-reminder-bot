package com.example.bot;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quartz job that unpins a previously pinned message.
 */
public class UnpinJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(UnpinJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        try {
            ReminderBot bot = (ReminderBot) context.getScheduler().getContext().get("bot");
            long chatId = context.getMergedJobDataMap().getLong("chatId");
            int messageId = context.getMergedJobDataMap().getInt("messageId");

            bot.unpinMessage(chatId, messageId);
        } catch (Exception e) {
            log.error("Failed to unpin message", e);
        }
    }
}
