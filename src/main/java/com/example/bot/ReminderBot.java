package com.example.bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Minimal Telegram bot for scheduled reminders.
 * Supports /chatid command to reveal chatId in a group.
 */
public class ReminderBot extends TelegramLongPollingBot {

    private final String token;
    private final String username;

    private volatile org.quartz.Scheduler scheduler;
    public void setScheduler(org.quartz.Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public ReminderBot(String token, String username) {
        this.token = token;
        this.username = username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            switch (text) {
                case "/chatid" -> send(chatId, "chatId: " + chatId);

                case "/test" -> {
                    if (scheduler == null) {
                        send(chatId, "⚠️ Scheduler не готов. Перезапусти приложение.");
                        return;
                    }
                    String msg = "✅ Тестовое уведомление 😄📌\n\n"
                            + "Сейчас закреплю это сообщение и сниму закреп через 5 минут ⏳🔁";
                    sendPinAndAutoUnpinMinutes(scheduler, chatId, msg, 5);
                }

                default -> { /* ничего */ }
            }
        }
    }

    public void send(long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }

    public int sendAndGetMessageId(long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            org.telegram.telegrambots.meta.api.objects.Message sent = execute(msg);
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }

    public void pinMessage(long chatId, int messageId) {
        try {
            execute(org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .disableNotification(true)
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to pin message", e);
        }
    }

    public void unpinMessage(long chatId, int messageId) {
        try {
            execute(org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
            System.out.println("[UNPIN OK] chatId=" + chatId + ", messageId=" + messageId);
        } catch (TelegramApiException e) {
            // ВАЖНО: печатаем причину, иначе кажется “не распинивает”
            System.out.println("[UNPIN FAIL] chatId=" + chatId + ", messageId=" + messageId
                    + ", error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendPinAndAutoUnpinMinutes(org.quartz.Scheduler scheduler, long chatId, String text, int unpinAfterMinutes) {
        int messageId = sendAndGetMessageId(chatId, text);
        pinMessage(chatId, messageId);
        scheduleUnpinMinutes(scheduler, chatId, messageId, unpinAfterMinutes);
    }

    private void scheduleUnpinMinutes(org.quartz.Scheduler scheduler, long chatId, int messageId, int afterMinutes) {
        try {
            String key = "unpin_" + chatId + "_" + messageId + "_" + System.currentTimeMillis();

            org.quartz.JobDetail unpinJob = org.quartz.JobBuilder.newJob(UnpinJob.class)
                    .withIdentity(key)
                    .usingJobData("chatId", chatId)
                    .usingJobData("messageId", messageId)
                    .build();

            java.util.Date runAt = java.util.Date.from(
                    java.time.Instant.now().plus(afterMinutes, java.time.temporal.ChronoUnit.MINUTES)
            );

            org.quartz.Trigger trigger = org.quartz.TriggerBuilder.newTrigger()
                    .withIdentity("trigger_" + key)
                    .startAt(runAt)
                    .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
                            .withMisfireHandlingInstructionFireNow())
                    .build();

            scheduler.scheduleJob(unpinJob, trigger);
            System.out.println("[SCHEDULE] Unpin in " + afterMinutes + " minutes: chatId=" + chatId + ", messageId=" + messageId);
        } catch (org.quartz.SchedulerException e) {
            throw new RuntimeException("Failed to schedule unpin (minutes)", e);
        }
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }
}
