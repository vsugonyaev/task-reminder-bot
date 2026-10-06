package com.example.bot;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import com.example.config.AppConfig;
import com.example.sprint.SprintSchedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Minimal Telegram bot for scheduled reminders.
 *
 * Commands:
 *  - /chatid — reveals chatId of any chat (needed to configure TG_CHAT_ID)
 *  - /sprint — shows current sprint and its reminder days
 *  - /test   — previews the next reminder (pinned for a few minutes); works only in the target chat
 *  - /test start — same for the sprint planning day warning
 */
public class ReminderBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReminderBot.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    private final TelegramClient client;
    private final Scheduler scheduler;
    private final AppConfig config;
    private final SprintSchedule sprints;

    public ReminderBot(TelegramClient client, Scheduler scheduler, AppConfig config, SprintSchedule sprints) {
        this.client = client;
        this.scheduler = scheduler;
        this.config = config;
        this.sprints = sprints;
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }
        long chatId = update.getMessage().getChatId();
        String[] parts = update.getMessage().getText().trim().split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        try {
            switch (parseCommand(parts[0])) {
                case "/chatid" -> send(chatId, "chatId: " + chatId);

                case "/sprint" -> send(chatId, sprintInfo());

                case "/test" -> {
                    if (chatId != config.chatId()) {
                        log.info("Ignored /test from chat {} (target chat is {})", chatId, config.chatId());
                        return;
                    }
                    boolean start = arg.equalsIgnoreCase("start");
                    LocalDate day = start ? nextPlanningDay() : nextReminderDay();
                    String text = start ? sprintStartText(day) : reminderText(day);
                    Duration pin = config.testPinDuration();
                    String msg = "🧪 <i>Тест: так будет выглядеть " + (start ? "предупреждение " : "напоминание ")
                            + DATE.format(day) + ". Закреп снимется через " + pin.toMinutes() + " мин.</i>\n\n"
                            + text;
                    sendPinAndAutoUnpin(chatId, msg, pin);
                }

                default -> { /* ничего */ }
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle update in chat {}", chatId, e);
        }
    }

    /** Text of the reminder to close tasks for the given day. */
    public String reminderText(LocalDate day) {
        boolean lastDay = sprints.lastReminderDay(day).map(day::equals).orElse(false);
        return ReminderTexts.forDay(sprints.sprintOf(day), day, lastDay);
    }

    /** Text of the planning day warning for the given day. */
    public String sprintStartText(LocalDate day) {
        return ReminderTexts.sprintStart(sprints.sprintOf(day), config.scrumMaster());
    }

    /** Today if today's reminder is still ahead, otherwise the next reminder day. */
    private LocalDate nextReminderDay() {
        return nextDay(config.reminderTime(), sprints::isReminderDay);
    }

    /** Same for the sprint planning day warning. */
    private LocalDate nextPlanningDay() {
        return nextDay(config.sprintStartTime(), sprints::isPlanningDay);
    }

    private LocalDate nextDay(LocalTime sendTime, Predicate<LocalDate> matches) {
        LocalDate day = LocalDate.now(config.zone());
        if (!LocalTime.now(config.zone()).isBefore(sendTime)) {
            day = day.plusDays(1);
        }
        while (!matches.test(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    private String sprintInfo() {
        SprintSchedule.Sprint sprint = sprints.sprintOf(LocalDate.now(config.zone()));
        String planning = sprints.planningDay(sprint).map(DATE::format).orElse("—");
        String days = sprints.reminderDays(sprint).stream()
                .map(DATE::format)
                .collect(Collectors.joining(", "));
        String zone = " " + config.zoneLabel();
        return "🏃 <b>Спринт " + DATE.format(sprint.start()) + " — " + DATE.format(sprint.end()) + "</b>\n"
                + "⛔ Планирование, задачи не закрываем: " + planning + " (" + config.sprintStartTime() + zone + ")\n"
                + "⏰ Напоминания (" + config.reminderTime() + zone + "): " + days + "\n"
                + "Следующее напоминание: " + DATE.format(nextReminderDay()) + "\n"
                + "Следующее планирование: " + DATE.format(nextPlanningDay());
    }

    /** "/test@my_bot" -> "/test" */
    private static String parseCommand(String command) {
        int at = command.indexOf('@');
        return at >= 0 ? command.substring(0, at) : command;
    }

    public void send(long chatId, String text) {
        sendAndGetMessageId(chatId, text);
    }

    public int sendAndGetMessageId(long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("HTML")
                .build();
        try {
            Message sent = client.execute(msg);
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }

    public void pinMessage(long chatId, int messageId) {
        try {
            client.execute(PinChatMessage.builder()
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
            client.execute(UnpinChatMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
            log.info("Unpinned: chatId={}, messageId={}", chatId, messageId);
        } catch (TelegramApiException e) {
            log.error("Failed to unpin: chatId={}, messageId={}", chatId, messageId, e);
        }
    }

    public void sendPinAndAutoUnpin(long chatId, String text, Duration pinDuration) {
        int messageId = sendAndGetMessageId(chatId, text);
        pinMessage(chatId, messageId);
        scheduleUnpin(chatId, messageId, pinDuration);
    }

    private void scheduleUnpin(long chatId, int messageId, Duration after) {
        String key = "unpin_" + chatId + "_" + messageId;

        JobDetail unpinJob = JobBuilder.newJob(UnpinJob.class)
                .withIdentity(key)
                .usingJobData("chatId", chatId)
                .usingJobData("messageId", messageId)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger_" + key)
                .startAt(Date.from(Instant.now().plus(after)))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withMisfireHandlingInstructionFireNow())
                .build();

        try {
            scheduler.scheduleJob(unpinJob, trigger);
            log.info("Unpin scheduled in {}: chatId={}, messageId={}", after, chatId, messageId);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule unpin", e);
        }
    }
}
