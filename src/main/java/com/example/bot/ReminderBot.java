package com.example.bot;

import org.quartz.Job;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import com.example.artifacts.ArtifactDialogs;
import com.example.artifacts.ArtifactRepository;
import com.example.artifacts.ChatApi;
import com.example.artifacts.EpicMatcher;
import com.example.config.AppConfig;
import com.example.meeting.Meeting;
import com.example.sprint.SprintSchedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
 *  - /test daily|planning|review — previews a meeting reminder (deleted after a few minutes)
 *  - artifact commands — see {@link ArtifactDialogs}; work only in the target chat
 */
public class ReminderBot implements LongPollingSingleThreadUpdateConsumer, ChatApi {

    private static final Logger log = LoggerFactory.getLogger(ReminderBot.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final LinkPreviewOptions NO_PREVIEW = LinkPreviewOptions.builder().isDisabled(true).build();

    private final TelegramClient client;
    private final Scheduler scheduler;
    private final AppConfig config;
    private final SprintSchedule sprints;
    private final ArtifactDialogs artifacts;

    public ReminderBot(TelegramClient client, Scheduler scheduler, AppConfig config, SprintSchedule sprints,
                       ArtifactRepository artifactRepository) {
        this.client = client;
        this.scheduler = scheduler;
        this.config = config;
        this.sprints = sprints;
        this.artifacts = new ArtifactDialogs(this, artifactRepository,
                new EpicMatcher(config.epicKeyPrefixes()), config.artifactTypes(),
                config.artifactResultTtl(), config.artifactDialogTimeout());
    }

    /** Shows bot commands in the "/" menu of the target chat. */
    public void registerCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("sprint", "Даты спринта, напоминания и встречи"));
        ArtifactDialogs.COMMANDS.forEach((cmd, description) -> commands.add(new BotCommand(cmd.substring(1), description)));
        try {
            client.execute(SetMyCommands.builder()
                    .commands(commands)
                    .scope(new BotCommandScopeChat(String.valueOf(config.chatId())))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to register bot commands: {}", e.getMessage());
        }
    }

    public void shutdown() {
        artifacts.close();
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                artifacts.onCallback(update.getCallbackQuery());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                onMessage(update.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle update", e);
        }
    }

    private void onMessage(Message message) {
        long chatId = message.getChatId();
        String[] parts = message.getText().trim().split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        String command = parseCommand(parts[0]);

        if (ArtifactDialogs.COMMANDS.containsKey(command)) {
            if (chatId == config.chatId() && message.getFrom() != null) {
                artifacts.onCommand(command, chatId, message.getFrom(), message.getMessageId());
            }
            return;
        }
        if (!command.startsWith("/") && message.getFrom() != null) {
            artifacts.onText(chatId, message.getFrom(), message.getMessageId(), message.getText());
            return;
        }

        try {
            switch (command) {
                case "/chatid" -> send(chatId, "chatId: " + chatId);

                case "/sprint" -> send(chatId, sprintInfo());

                case "/test" -> {
                    if (chatId != config.chatId()) {
                        log.info("Ignored /test from chat {} (target chat is {})", chatId, config.chatId());
                        return;
                    }
                    if (!arg.isEmpty() && !arg.equalsIgnoreCase("start")) {
                        testMeeting(chatId, arg);
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

    private void testMeeting(long chatId, String arg) {
        Meeting.Type type = Meeting.Type.byKey(arg);
        Meeting meeting = type != null ? config.meetings().get(type) : null;
        if (meeting == null) {
            String known = config.meetings().keySet().stream().map(Meeting.Type::key).collect(Collectors.joining(", "));
            send(chatId, "Использование: /test, /test start, /test &lt;встреча&gt;\nВстречи: " + known);
            return;
        }
        sendMeetingReminder(chatId, meeting);
    }

    /** Sends a meeting reminder with a button to join; deletes it after meeting.delete-after-minutes. */
    public void sendMeetingReminder(long chatId, Meeting meeting) {
        String text = ReminderTexts.meeting(meeting, config.meetingTeam(), config.meetingRemindBefore());
        InlineKeyboardMarkup joinButton = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text("🎥 Подключиться")
                        .url(meeting.room())
                        .build()))
                .build();

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("HTML")
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .replyMarkup(joinButton)
                .build();
        try {
            int messageId = client.execute(msg).getMessageId();
            scheduleMessageJob(DeleteJob.class, "delete", chatId, messageId, config.meetingDeleteAfter());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send meeting reminder", e);
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
                + meetingsInfo(sprint)
                + "Следующее напоминание: " + DATE.format(nextReminderDay()) + "\n"
                + "Следующее планирование: " + DATE.format(nextPlanningDay());
    }

    private String meetingsInfo(SprintSchedule.Sprint sprint) {
        StringBuilder sb = new StringBuilder();
        for (Meeting meeting : config.meetings().values()) {
            List<String> days = new ArrayList<>();
            for (LocalDate d = sprint.start(); !d.isAfter(sprint.end()); d = d.plusDays(1)) {
                if (meeting.type().occursOn(sprints, d)) {
                    days.add(DATE.format(d));
                }
            }
            sb.append(meeting.type().emoji()).append(' ').append(meeting.type().title())
                    .append(" (").append(meeting.time()).append(' ').append(config.zoneLabel()).append("): ")
                    .append(days.isEmpty() ? "—" : String.join(", ", days)).append('\n');
        }
        return sb.toString();
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

    // --- ChatApi (used by artifact dialogs) ---

    @Override
    public int send(long chatId, String html, InlineKeyboardMarkup keyboard) {
        try {
            return client.execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(html)
                    .parseMode("HTML")
                    .linkPreviewOptions(NO_PREVIEW)
                    .replyMarkup(keyboard)
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }

    @Override
    public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard) {
        try {
            client.execute(EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .text(html)
                    .parseMode("HTML")
                    .linkPreviewOptions(NO_PREVIEW)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            // Telegram rejects edits that change nothing — harmless
            if (e.getMessage() == null || !e.getMessage().contains("message is not modified")) {
                throw new RuntimeException("Failed to edit message", e);
            }
        }
    }

    @Override
    public void delete(long chatId, int messageId) {
        deleteMessage(chatId, messageId);
    }

    @Override
    public void answerCallback(String callbackId, String text, boolean alert) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback: {}", e.getMessage());
        }
    }

    @Override
    public void deleteLater(long chatId, int messageId, Duration after) {
        scheduleMessageJob(DeleteJob.class, "delete", chatId, messageId, after);
    }

    public void deleteMessage(long chatId, int messageId) {
        try {
            client.execute(DeleteMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .build());
            log.info("Deleted: chatId={}, messageId={}", chatId, messageId);
        } catch (TelegramApiException e) {
            log.error("Failed to delete: chatId={}, messageId={}", chatId, messageId, e);
        }
    }

    public void sendPinAndAutoUnpin(long chatId, String text, Duration pinDuration) {
        int messageId = sendAndGetMessageId(chatId, text);
        pinMessage(chatId, messageId);
        scheduleMessageJob(UnpinJob.class, "unpin", chatId, messageId, pinDuration);
    }

    /** Runs a one-off job (unpin, delete) for the message after the given delay. */
    private void scheduleMessageJob(Class<? extends Job> jobClass, String action,
                                    long chatId, int messageId, Duration after) {
        String key = action + "_" + chatId + "_" + messageId;

        JobDetail job = JobBuilder.newJob(jobClass)
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
            scheduler.scheduleJob(job, trigger);
            log.info("{} scheduled in {}: chatId={}, messageId={}", action, after, chatId, messageId);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to schedule " + action, e);
        }
    }
}
