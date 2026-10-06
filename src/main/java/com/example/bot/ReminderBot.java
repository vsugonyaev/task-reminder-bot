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

import com.example.access.AccessControl;
import com.example.access.Permission;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Minimal Telegram bot for scheduled reminders.
 *
 * Commands:
 *  - /help   — what the bot does and its commands (tailored to the chat and the user's rights)
 *  - /chatid — reveals chatId of any chat (needed to configure TG_CHAT_ID)
 *  - /sprint — shows current sprint and its reminder days
 *  - /test   — previews the next reminder (pinned for a few minutes); works only in test chats, never in the work chat
 *  - /test start — same for the sprint planning day warning
 *  - /test daily|planning|review — previews a meeting reminder (deleted after a few minutes)
 *  - artifact commands — see {@link ArtifactDialogs}; work in the work chat and test chats
 *    (each chat has its own data)
 *  - /test_cleanup — test chats only: deletes artifact data created in the chat
 */
public class ReminderBot implements LongPollingSingleThreadUpdateConsumer, ChatApi {

    private static final Logger log = LoggerFactory.getLogger(ReminderBot.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final LinkPreviewOptions NO_PREVIEW = LinkPreviewOptions.builder().isDisabled(true).build();

    private final TelegramClient client;
    private final Scheduler scheduler;
    private final AppConfig config;
    private final SprintSchedule sprints;
    private final AccessControl access;
    /** Null when artifacts are disabled (artifacts.enabled=false): only reminders work then. */
    private final ArtifactDialogs artifacts;

    /**
     * @param artifactRepository null — artifact features are disabled
     */
    public ReminderBot(TelegramClient client, Scheduler scheduler, AppConfig config, SprintSchedule sprints,
                       ArtifactRepository artifactRepository) {
        this.client = client;
        this.scheduler = scheduler;
        this.config = config;
        this.sprints = sprints;
        this.access = new AccessControl(client, config.accessRules());
        this.artifacts = artifactRepository == null ? null : new ArtifactDialogs(this, artifactRepository,
                new EpicMatcher(config.epicKeyPrefixes()), config.artifactTypes(),
                config.artifactResultTtl(), config.artifactDialogTimeout(), access, config.testChatIds());
    }

    private boolean isTestChat(long chatId) {
        return config.testChatIds().contains(chatId);
    }

    /** Startup checks of the work chat settings (logged as warnings); tags matter only for artifacts. */
    public void checkChatSettings() {
        if (artifacts != null) {
            access.checkChatSettings(config.chatId());
        }
    }

    /** Shows bot commands in the "/" menu of the work chat and test chats (test chats also get test commands). */
    public void registerCommands() {
        List<BotCommand> work = new ArrayList<>();
        work.add(new BotCommand("help", "Что умеет бот"));
        work.add(new BotCommand("sprint", "Даты спринта, напоминания и встречи"));
        if (artifacts != null) {
            ArtifactDialogs.COMMANDS.forEach((cmd, description) -> work.add(new BotCommand(cmd.substring(1), description)));
        }
        registerCommands(config.chatId(), work);

        List<BotCommand> test = new ArrayList<>(work);
        test.add(new BotCommand("test", "Тест: напоминание (start | daily | planning | review)"));
        if (artifacts != null) {
            test.add(new BotCommand(ArtifactDialogs.CLEANUP_COMMAND.substring(1), "Удалить данные, созданные в этом чате"));
        }
        config.testChatIds().forEach(chatId -> registerCommands(chatId, test));
    }

    private void registerCommands(long chatId, List<BotCommand> commands) {
        try {
            client.execute(SetMyCommands.builder()
                    .commands(commands)
                    .scope(new BotCommandScopeChat(String.valueOf(chatId)))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to register bot commands in chat {}: {}", chatId, e.getMessage());
        }
    }

    public void shutdown() {
        if (artifacts != null) {
            artifacts.close();
        }
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                // Inline buttons with callbacks exist only in artifact dialogs
                if (artifacts != null) {
                    artifacts.onCallback(update.getCallbackQuery());
                }
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

        if (ArtifactDialogs.COMMANDS.containsKey(command) || ArtifactDialogs.CLEANUP_COMMAND.equals(command)) {
            boolean allowedChat = ArtifactDialogs.CLEANUP_COMMAND.equals(command)
                    ? isTestChat(chatId)
                    : chatId == config.chatId() || isTestChat(chatId);
            if (artifacts != null && allowedChat && message.getFrom() != null) {
                // Artifact dialogs clean up their own messages
                artifacts.onCommand(command, chatId, message.getFrom(), message.getMessageId());
            } else {
                deleteLater(chatId, message.getMessageId(), config.commandsDeleteAfter());
            }
            return;
        }
        if (!command.startsWith("/")) {
            if (artifacts != null && message.getFrom() != null) {
                artifacts.onText(chatId, message.getFrom(), message.getMessageId(), message.getText());
            }
            return;
        }
        if (!OWN_COMMANDS.contains(command)) {
            return; // someone else's command
        }

        // Every command and every reply to it is removed after commands.delete-after-minutes
        deleteLater(chatId, message.getMessageId(), config.commandsDeleteAfter());
        try {
            switch (command) {
                case "/chatid" -> reply(chatId, "chatId: " + chatId);

                case "/sprint" -> reply(chatId, sprintInfo());

                case "/help" -> help(message);

                case "/test" -> {
                    if (!isTestChat(chatId)) {
                        log.info("Ignored /test from chat {} (test chats: {})", chatId, config.testChatIds());
                        return;
                    }
                    if (!arg.isEmpty() && !arg.equalsIgnoreCase("start")) {
                        testMeeting(chatId, arg);
                        return;
                    }
                    boolean start = arg.equalsIgnoreCase("start");
                    LocalDate day = start ? nextPlanningDay() : nextReminderDay();
                    String text = start ? sprintStartText(day) : reminderText(day);
                    String msg = "🧪 <i>Тест: так будет выглядеть " + (start ? "предупреждение " : "напоминание ")
                            + DATE.format(day) + ". Сообщение удалится через "
                            + config.commandsDeleteAfter().toMinutes() + " мин.</i>\n\n" + text;
                    // Pinned until deleted: deleting a message also removes it from pins
                    pinMessage(chatId, reply(chatId, msg));
                }

                default -> { /* not reachable: filtered by OWN_COMMANDS */ }
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle update in chat {}", chatId, e);
        }
    }

    private static final Set<String> OWN_COMMANDS = Set.of("/chatid", "/sprint", "/help", "/test");

    /** Sends a reply to a command and schedules its deletion. Returns the message id. */
    private int reply(long chatId, String html) {
        int id = sendAndGetMessageId(chatId, html);
        deleteLater(chatId, id, config.commandsDeleteAfter());
        return id;
    }

    /** Help tailored to the chat; in work/test chats with artifacts on also shows the user's rights. */
    private void help(Message message) {
        long chatId = message.getChatId();
        HelpText.ChatKind kind = chatId == config.chatId() ? HelpText.ChatKind.WORK
                : isTestChat(chatId) ? HelpText.ChatKind.TEST
                : HelpText.ChatKind.OTHER;

        Set<Permission> rights = null;
        if (artifacts != null && kind != HelpText.ChatKind.OTHER && message.getFrom() != null) {
            rights = EnumSet.noneOf(Permission.class);
            for (Permission p : Permission.values()) {
                if (access.allowed(chatId, message.getFrom().getId(), p)) {
                    rights.add(p);
                }
            }
        }

        reply(chatId, HelpText.build(config, kind, artifacts != null, rights));
    }

    private void testMeeting(long chatId, String arg) {
        Meeting.Type type = Meeting.Type.byKey(arg);
        Meeting meeting = type != null ? config.meetings().get(type) : null;
        if (meeting == null) {
            String known = config.meetings().keySet().stream().map(Meeting.Type::key).collect(Collectors.joining(", "));
            reply(chatId, "Использование: /test, /test start, /test &lt;встреча&gt;\nВстречи: " + known);
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
            // Usually the message was already deleted by someone — not worth a stack trace
            log.warn("Failed to delete: chatId={}, messageId={}: {}", chatId, messageId, e.getMessage());
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
