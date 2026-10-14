package com.example.artifacts;

import com.example.access.Permission;
import com.example.access.PermissionChecker;
import com.example.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Step-by-step dialogs (inline buttons + one "panel" message edited in place) for artifact commands:
 *
 *  - /artifact_add      — choose or create an epic (a new one gets an optional "assignees" step); then the epic
 *                         panel: add links (type, link, title), replace / rename / delete links, assignees;
 *  - /artifacts         — choose an epic, get its assignees and links;
 *  - /archivate         — archive an epic;
 *  - /archiv_list       — the same as /artifacts for archived epics.
 *
 * One dialog per user per chat; only its author can press the buttons. When a dialog ends (done, cancelled
 * or timed out) all its messages are deleted; results are posted separately and deleted after a while.
 * Not thread-safe by itself — all entry points are synchronized.
 */
public class ArtifactDialogs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDialogs.class);

    public static final Map<String, String> COMMANDS = new LinkedHashMap<>();

    public static final String ARCHIVE_LIST_COMMAND = "/archiv_list";

    static {
        COMMANDS.put("/artifacts", "Посмотреть артефакты эпика");
        COMMANDS.put("/artifact_add", "Добавить или изменить артефакты и исполнителей");
        COMMANDS.put("/archivate", "Отправить эпик в архив");
        COMMANDS.put(ARCHIVE_LIST_COMMAND, "Артефакты архивных эпиков");
    }

    /** Renamed commands: old name -> new one (the bot answers with a hint). */
    public static final Map<String, String> RENAMED_COMMANDS = Map.of(
            "/artifact_edit", "/artifact_add",
            "/epic_archive", "/archivate",
            "/artifacts_archive", ARCHIVE_LIST_COMMAND);

    /** Available only in test chats: permanently deletes artifact data created in the chat. */
    public static final String CLEANUP_COMMAND = "/test_cleanup";

    private static final int PAGE_SIZE = 8;
    private static final int PEOPLE_PAGE_SIZE = 16;
    private static final int TYPES_PER_ROW = 3;
    private static final int MAX_BUTTON_TEXT = 48;
    private static final int MAX_NAME = 64;

    /** ADD is /artifact_add — adding and editing artifacts of an epic. */
    private enum Kind { ADD, VIEW, VIEW_ARCHIVED, ARCHIVE, CLEANUP }

    private enum Step {
        EPIC_SELECT, EPIC_CONFIRM, HUB, TYPE_SELECT, SYSTEM_SELECT, LINK_INPUT, ADD_MORE,
        ACTION_SELECT, DELETE_CONFIRM, ARCHIVE_CONFIRM, CLEANUP_CONFIRM,
        TEAM_ROLES, TEAM_PEOPLE, TEAM_MANUAL
    }

    /** Steps that wait for a text message from the dialog author. */
    private static final Set<Step> TEXT_STEPS = Set.of(
            Step.EPIC_SELECT, Step.EPIC_CONFIRM, Step.LINK_INPUT, Step.TEAM_MANUAL);

    private static final class Session {
        final Kind kind;
        final long chatId;
        final long userId;
        final String user;
        final List<Integer> userMessages = new ArrayList<>();
        int panelId;
        Instant lastActivity = Instant.now();

        Step step = Step.EPIC_SELECT;
        List<Epic> shownEpics = List.of();
        int page;
        String search;
        String notice;

        Epic epic;
        EpicMatcher.ParsedEpic pendingEpic;
        String type;
        Long artifactId;
        String pendingUrl;
        /** Fixed title of the slot being filled: "БТ" or "СТ ВТБО". */
        String pendingTitle;
        /** The system choice changes the system of an existing СТ / Макеты link instead of adding one. */
        boolean changingSystem;
        /** Changes made in this /artifact_add dialog, for the final summary. */
        final List<String> savedLines = new ArrayList<>();
        boolean teamEdited;

        /** Assignees step of a just created epic: continue to the type choice afterwards. */
        boolean teamThenTypes;
        String role;
        int peoplePage;
        List<Person> admins;
        /** Member tags looked up in this dialog (userId -> tag, "" for none). */
        final Map<Long, String> tags = new HashMap<>();

        /** The author just wrote a message: the next render re-posts the panel below it. */
        boolean repost;

        Session(Kind kind, long chatId, User from) {
            this.kind = kind;
            this.chatId = chatId;
            this.userId = from.getId();
            this.user = userLabel(from);
        }
    }

    private final ChatApi chat;
    private final PermissionChecker access;
    private final Set<Long> testChatIds;
    private final ArtifactRepository repo;
    private final EpicMatcher matcher;
    private final List<String> types;
    private final List<String> roles;
    private final Map<String, String> roleTags;
    /** Types whose links are per system (СТ, Макеты) and the systems (ВТБО, ВТБ Про). */
    private final List<String> systemTypes;
    private final List<String> systems;
    private final Duration resultTtl;
    private final Duration timeout;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "artifact-dialogs-sweeper");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param roleTags role -> member tag whose owners are offered first as assignees (БА -> BA)
     */
    public ArtifactDialogs(ChatApi chat, ArtifactRepository repo, EpicMatcher matcher, List<String> types,
                           List<String> roles, Map<String, String> roleTags, List<String> systemTypes, List<String> systems,
                           Duration resultTtl, Duration timeout,
                           PermissionChecker access, Set<Long> testChatIds) {
        this.chat = chat;
        this.testChatIds = Set.copyOf(testChatIds);
        this.access = access;
        this.repo = repo;
        this.matcher = matcher;
        this.types = List.copyOf(types);
        this.roles = List.copyOf(roles);
        this.roleTags = Map.copyOf(roleTags);
        this.systemTypes = List.copyOf(systemTypes);
        this.systems = List.copyOf(systems);
        this.resultTtl = resultTtl;
        this.timeout = timeout;
        sweeper.scheduleWithFixedDelay(this::expireSessions, 30, 30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ entry points

    /** Remembers a chat member, so they can be picked as an assignee (Telegram gives bots no member list). */
    public synchronized void rememberMember(long chatId, User from) {
        if (Boolean.TRUE.equals(from.getIsBot())) {
            return;
        }
        try {
            repo.rememberMember(chatId, from.getId(), displayName(from), from.getUserName());
        } catch (RuntimeException e) {
            log.warn("Failed to remember member {} of chat {}: {}", from.getId(), chatId, e.getMessage());
        }
    }

    public synchronized void onCommand(String command, long chatId, User from, int messageId) {
        Kind kind = switch (command) {
            case "/artifact_add" -> Kind.ADD;
            case "/artifacts" -> Kind.VIEW;
            case ARCHIVE_LIST_COMMAND -> Kind.VIEW_ARCHIVED;
            case "/archivate" -> Kind.ARCHIVE;
            case CLEANUP_COMMAND -> Kind.CLEANUP;
            default -> throw new IllegalArgumentException(command);
        };

        boolean allowed = switch (kind) {
            case ADD -> can(chatId, from, Permission.ARTIFACT_EDIT) || can(chatId, from, Permission.ARTIFACT_DELETE);
            case ARCHIVE -> can(chatId, from, Permission.EPIC_ARCHIVE);
            case CLEANUP -> testChatIds.contains(chatId) && can(chatId, from, Permission.ARTIFACT_DELETE);
            case VIEW, VIEW_ARCHIVED -> true;
        };
        if (!allowed) {
            // Silently: a reply would let anyone spam the chat
            chat.delete(chatId, messageId);
            return;
        }

        Session old = sessions.get(key(chatId, from.getId()));
        if (old != null) {
            cleanup(old);
        }

        Session s = new Session(kind, chatId, from);
        s.userMessages.add(messageId);

        if (kind == Kind.CLEANUP) {
            ArtifactRepository.ChatDataStats stats = repo.chatDataStats(chatId);
            if (stats.epics() == 0) {
                finish(s, "🧹 В этом чате нет данных артефактов.");
                return;
            }
            s.step = Step.CLEANUP_CONFIRM;
            s.panelId = chat.send(chatId, "🧹 <b>Очистка тестовых данных</b>\n"
                            + "Данные, созданные в этом чате: эпиков — " + stats.epics()
                            + ", артефактов — " + stats.artifacts() + " (включая архивные и удалённые).\n\n"
                            + "Удалить их безвозвратно? Данные рабочего чата не затрагиваются.",
                    keyboard(row(button("🗑 Удалить тестовые данные", "yes"), cancelButton())));
            sessions.put(key(chatId, s.userId), s);
            return;
        }

        s.shownEpics = repo.epics(chatId, kind == Kind.VIEW_ARCHIVED);

        if (s.shownEpics.isEmpty() && kind != Kind.ADD) {
            finish(s, kind == Kind.VIEW_ARCHIVED
                    ? "🗄 В архиве пока нет эпиков."
                    : "📂 Эпиков пока нет. Добавьте первый через /artifact_add");
            return;
        }

        s.panelId = chat.send(chatId, epicSelectText(s), epicKeyboard(s));
        sessions.put(key(chatId, s.userId), s);
        log.info("Dialog {} started by {}", kind, s.user);
    }

    /** Returns true if the message was consumed by a dialog waiting for text input. */
    public synchronized boolean onText(long chatId, User from, int messageId, String text) {
        return onText(chatId, from, messageId, text, List.of());
    }

    /** Entities let a link be taken from Telegram markup, e.g. text with a hidden link copied from a browser. */
    public synchronized boolean onText(long chatId, User from, int messageId, String text, List<MessageEntity> entities) {
        Session s = sessions.get(key(chatId, from.getId()));
        if (s == null || !TEXT_STEPS.contains(s.step)) {
            return false;
        }
        s.userMessages.add(messageId);
        s.lastActivity = Instant.now();
        // The reply pushed the panel up (on phones — behind the keyboard): show the next step below it
        s.repost = true;

        try {
            String t = text.trim();
            switch (s.step) {
                case LINK_INPUT -> onLink(s, t, entities);
                case TEAM_MANUAL -> onManualAssignee(s, t);
                default -> onEpicText(s, t);
            }
        } catch (RuntimeException e) {
            log.error("Dialog {} failed on text input", s.kind, e);
            finish(s, "⚠️ Что-то пошло не так, попробуйте ещё раз.");
        }
        return true;
    }

    public synchronized void onCallback(CallbackQuery cb) {
        long chatId = cb.getMessage().getChatId();
        int messageId = cb.getMessage().getMessageId();
        Session s = sessions.get(key(chatId, cb.getFrom().getId()));

        if (s == null || s.panelId != messageId) {
            boolean foreign = sessions.values().stream()
                    .anyMatch(other -> other.chatId == chatId && other.panelId == messageId);
            if (foreign) {
                chat.answerCallback(cb.getId(), "Это меню вызвал другой участник 🙂", true);
            } else {
                chat.answerCallback(cb.getId(), "Диалог устарел — вызовите команду заново", false);
                chat.delete(chatId, messageId);
            }
            return;
        }

        chat.answerCallback(cb.getId(), null, false);
        s.lastActivity = Instant.now();

        String data = cb.getData() != null ? cb.getData() : "";
        int colon = data.indexOf(':');
        String action = colon >= 0 ? data.substring(0, colon) : data;
        String arg = colon >= 0 ? data.substring(colon + 1) : "";

        try {
            onAction(s, action, arg);
        } catch (RuntimeException e) {
            log.error("Dialog {} failed on action {}", s.kind, data, e);
            finish(s, "⚠️ Что-то пошло не так, попробуйте ещё раз.");
        }
    }

    @Override
    public synchronized void close() {
        sweeper.shutdownNow();
        new ArrayList<>(sessions.values()).forEach(this::cleanup);
    }

    // ------------------------------------------------------------------ actions

    private void onAction(Session s, String action, String arg) {
        switch (action) {
            case "x" -> {
                if (s.kind == Kind.ADD && s.epic != null && s.step != Step.HUB) {
                    // Inside an epic "Отмена" only drops the current step
                    showHub(s);
                    return;
                }
                if (s.kind == Kind.ADD && s.epic != null && hasChanges(s)) {
                    finishAdding(s);
                    return;
                }
                cleanup(s);
                log.info("Dialog {} cancelled by {}", s.kind, s.user);
            }
            case "n" -> { /* page counter, no-op */ }
            case "p" -> {
                s.page = Integer.parseInt(arg);
                renderEpicSelect(s);
            }
            case "all" -> {
                s.search = null;
                s.shownEpics = repo.epics(s.chatId, s.kind == Kind.VIEW_ARCHIVED);
                s.page = 0;
                s.step = Step.EPIC_SELECT;
                renderEpicSelect(s);
            }
            case "e" -> repo.epic(s.chatId, Long.parseLong(arg)).ifPresent(epic -> onEpicChosen(s, epic));
            case "new" -> {
                EpicMatcher.ParsedEpic p = s.pendingEpic;
                if (p != null) {
                    Optional<Epic> existing = repo.epicByKey(s.chatId, p.key());
                    if (existing.isPresent()) {
                        onEpicChosen(s, existing.get());
                    } else {
                        onEpicCreated(s, repo.createEpic(s.chatId, p.key(), p.name(), s.user));
                    }
                }
            }
            case "hub" -> showHub(s);
            case "add" -> {
                if (can(s, Permission.ARTIFACT_EDIT)) {
                    s.step = Step.TYPE_SELECT;
                    renderTypeSelect(s);
                }
            }
            case "t" -> onTypeChosen(s, types.get(Integer.parseInt(arg)));
            case "sys" -> onSystemChosen(s, systems.get(Integer.parseInt(arg)));
            case "a" -> {
                s.artifactId = Long.parseLong(arg);
                showArtifactActions(s);
            }
            case "rep" -> {
                if (s.step == Step.ACTION_SELECT && can(s, Permission.ARTIFACT_EDIT)) {
                    askLink(s);
                }
            }
            case "ren" -> {
                // Only СТ / Макеты have a changeable part — the system (ВТБО / ВТБ Про)
                if (s.step == Step.ACTION_SELECT && can(s, Permission.ARTIFACT_EDIT) && systemTypes.contains(s.type)) {
                    s.changingSystem = true;
                    showSystemSelect(s);
                }
            }
            case "del" -> {
                if (!can(s, Permission.ARTIFACT_DELETE)) {
                    return;
                }
                s.step = Step.DELETE_CONFIRM;
                Artifact a = repo.artifact(s.artifactId).orElseThrow();
                render(s, header(s) + "Удалить ссылку " + titledLink(a) + "?\n"
                                + "<i>Она пропадёт из списков, но останется в истории.</i>",
                        keyboard(row(button("🗑 Да, удалить", "yes"), backButton())));
            }
            case "yes" -> {
                if (s.step == Step.DELETE_CONFIRM && can(s, Permission.ARTIFACT_DELETE)) {
                    Artifact a = repo.artifact(s.artifactId).orElseThrow();
                    repo.deleteArtifact(s.artifactId, s.user);
                    String line = Html.escape(a.displayTitle()) + " — удалено";
                    s.savedLines.add("🗑 " + line);
                    s.notice = "🗑 " + a.displayTitle() + " — удалено";
                    showHub(s);
                } else if (s.step == Step.CLEANUP_CONFIRM && testChatIds.contains(s.chatId)
                        && can(s, Permission.ARTIFACT_DELETE)) {
                    ArtifactRepository.ChatDataStats deleted = repo.deleteChatData(s.chatId);
                    finish(s, "🧹 Тестовые данные удалены: эпиков — " + deleted.epics()
                            + ", артефактов — " + deleted.artifacts() + ".");
                } else if (s.step == Step.ARCHIVE_CONFIRM && can(s, Permission.EPIC_ARCHIVE)) {
                    repo.archiveEpic(s.epic.id(), s.user);
                    finish(s, "📦 Эпик <b>" + Html.escape(s.epic.title()) + "</b> отправлен в архив.\n"
                            + "Документы по нему — в " + ARCHIVE_LIST_COMMAND);
                }
            }
            case "done" -> {
                if (hasChanges(s)) {
                    finishAdding(s);
                } else {
                    cleanup(s);
                }
            }
            // --- assignees ---
            case "team" -> {
                if (can(s, Permission.ARTIFACT_EDIT)) {
                    s.teamThenTypes = false;
                    showTeamRoles(s);
                }
            }
            case "r" -> {
                s.role = roles.get(Integer.parseInt(arg));
                s.peoplePage = 0;
                showTeamPeople(s);
            }
            case "pp" -> {
                s.peoplePage = Integer.parseInt(arg);
                showTeamPeople(s);
            }
            case "u" -> toggleAssignee(s, Long.parseLong(arg));
            case "m" -> {
                if (can(s, Permission.ARTIFACT_EDIT)) {
                    repo.removeAssignee(Long.parseLong(arg), s.user);
                    s.teamEdited = true;
                }
                showTeamPeople(s);
            }
            case "man" -> {
                s.step = Step.TEAM_MANUAL;
                render(s, teamHeader(s) + "Роль: <b>" + Html.escape(s.role) + "</b>\n\n"
                                + "Пришлите имя исполнителя (например, «Алина Мамонтова») или @username.",
                        keyboard(row(button("◀️ Назад", "back"))));
            }
            case "back" -> showTeamPeople(s);
            case "roles" -> showTeamRoles(s);
            case "tdone" -> {
                if (s.teamThenTypes) {
                    // New epic: assignees are done, go on to its first link
                    s.teamThenTypes = false;
                    s.step = Step.TYPE_SELECT;
                    renderTypeSelect(s);
                } else {
                    s.notice = "👥 Исполнители сохранены";
                    showHub(s);
                }
            }
            default -> log.warn("Unknown dialog action: {}", action);
        }
    }

    private void onEpicChosen(Session s, Epic epic) {
        s.epic = epic;
        s.notice = null;
        switch (s.kind) {
            case ADD -> showHub(s);
            case VIEW, VIEW_ARCHIVED -> finish(s, report(epic));
            case ARCHIVE -> {
                s.step = Step.ARCHIVE_CONFIRM;
                render(s, header(s) + "Отправить эпик в архив?\n"
                                + "<i>Он пропадёт из списков, документы останутся доступны через "
                                + ARCHIVE_LIST_COMMAND + ".</i>",
                        keyboard(row(button("📦 В архив", "yes"), cancelButton())));
            }
            default -> { /* CLEANUP has no epic choice */ }
        }
    }

    /** A new epic: offer the optional assignees step, then continue with the type choice. */
    private void onEpicCreated(Session s, Epic epic) {
        s.epic = epic;
        s.notice = null;
        s.teamThenTypes = true;
        showTeamRoles(s);
    }

    /**
     * Epic panel of /artifact_add: its assignees and links. A link opens replace / rename / delete;
     * "➕ Добавить ссылку", "👥 Исполнители", "✅ Готово" (summary of everything changed in the dialog).
     */
    private void showHub(Session s) {
        s.step = Step.HUB;
        s.type = null;
        s.artifactId = null;
        s.pendingUrl = null;
        s.pendingTitle = null;
        s.changingSystem = false;
        boolean canEdit = can(s, Permission.ARTIFACT_EDIT);
        List<Artifact> artifacts = sorted(repo.artifacts(s.epic.id()));

        StringBuilder text = new StringBuilder("📎 <b>").append(Html.escape(s.epic.title())).append("</b>\n\n");
        String team = teamLines(s.epic.id(), true);
        if (!team.isEmpty()) {
            text.append(team).append('\n');
        }
        if (artifacts.isEmpty()) {
            text.append("Артефактов пока нет.");
        } else {
            artifacts.forEach(a -> text.append("➡️").append(titledLink(a)).append('\n'));
            text.append("\nНажмите на ссылку, чтобы заменить или удалить её (у СТ и Макетов — сменить систему).");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        artifacts.forEach(a -> rows.add(row(button(artifactLabel(a), "a:" + a.id()))));
        if (canEdit) {
            rows.add(row(button("➕ Добавить ссылку", "add"), button("👥 Исполнители", "team")));
        }
        rows.add(row(button("✅ Готово", "done")));
        render(s, text.toString(), keyboard(rows));
    }

    private void showArtifactActions(Session s) {
        Optional<Artifact> a = repo.artifact(s.artifactId);
        if (a.isEmpty()) {
            showHub(s);
            return;
        }
        s.type = a.get().type();
        s.step = Step.ACTION_SELECT;
        s.pendingTitle = a.get().displayTitle();
        InlineKeyboardRow actions = new InlineKeyboardRow();
        if (can(s, Permission.ARTIFACT_EDIT)) {
            actions.add(button("🔁 Ссылку", "rep"));
            if (systemTypes.contains(s.type)) {
                actions.add(button("✏️ Система", "ren"));
            }
        }
        if (can(s, Permission.ARTIFACT_DELETE)) {
            actions.add(button("🗑 Удалить", "del"));
        }
        render(s, header(s) + titledLink(a.get()) + "\n\nЧто сделать?", keyboard(actions, row(backButton())));
    }

    private void askLink(Session s) {
        s.step = Step.LINK_INPUT;
        StringBuilder text = new StringBuilder(header(s));
        if (s.artifactId != null) {
            repo.artifact(s.artifactId).ifPresent(a -> text.append("Заменяем: ").append(titledLink(a)).append("\n\n"));
        }
        text.append("Пришлите ссылку на документ сообщением в чат.");
        render(s, text.toString(), keyboard(row(cancelButton())));
    }

    private void onLink(Session s, String text, List<MessageEntity> entities) {
        LinkInput link = LinkInput.extract(text, entities);
        if (link == null) {
            log.info("Dialog {}: no link found in a message from {}: {}", s.kind, s.user,
                    text.length() > 120 ? text.substring(0, 120) + "…" : text);
            s.notice = "❗ Не нашёл в сообщении ссылку. Пришлите адрес документа, например https://wiki…/pages/…";
            askLink(s);
            return;
        }
        s.pendingUrl = link.url();
        saveArtifact(s);
    }

    /**
     * Titles are fixed: the type itself ("БТ"), or the type and the system for СТ / Макеты ("СТ ВТБО").
     * One link per such slot — choosing a filled slot replaces its link.
     */
    private void onTypeChosen(Session s, String type) {
        s.type = type;
        s.changingSystem = false;
        if (systemTypes.contains(type)) {
            showSystemSelect(s);
            return;
        }
        s.pendingTitle = type;
        s.artifactId = slot(s, type).map(Artifact::id).orElse(null);
        askLink(s);
    }

    private void showSystemSelect(Session s) {
        s.step = Step.SYSTEM_SELECT;
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (int i = 0; i < systems.size(); i++) {
            boolean filled = slot(s, systemTitle(s.type, systems.get(i))).isPresent();
            row.add(button(systems.get(i) + (filled ? " ✅" : ""), "sys:" + i));
        }
        String question = s.changingSystem
                ? "Сейчас: " + Html.escape(s.pendingTitle) + ". На какую систему поменять?"
                : "Для какой системы? (✅ — ссылка уже есть, её можно заменить)";
        render(s, header(s) + question, keyboard(row, row(s.changingSystem ? backButton() : cancelButton())));
    }

    private void onSystemChosen(Session s, String system) {
        String title = systemTitle(s.type, system);
        Optional<Artifact> filled = slot(s, title);
        if (s.changingSystem) {
            s.changingSystem = false;
            Artifact a = repo.artifact(s.artifactId).orElseThrow();
            if (filled.isPresent() && filled.get().id() != a.id()) {
                s.notice = "❗ " + title + " уже есть — сначала замените или удалите её ссылку.";
                showHub(s);
                return;
            }
            if (!a.displayTitle().equals(title)) {
                repo.renameArtifact(a.id(), title, s.user);
                s.savedLines.add("➡️" + Html.link(a.url(), title) + " — система изменена");
                s.notice = "✏️ " + a.displayTitle() + " → " + title;
            }
            showHub(s);
            return;
        }
        s.pendingTitle = title;
        s.artifactId = filled.map(Artifact::id).orElse(null);
        askLink(s);
    }

    /** "СТ" + "ВТБО" -> "СТ ВТБО". */
    private static String systemTitle(String type, String system) {
        return type + " " + system;
    }

    /** The link already filling this slot (same title), if any. */
    private Optional<Artifact> slot(Session s, String title) {
        return repo.artifacts(s.epic.id()).stream()
                .filter(a -> a.displayTitle().equalsIgnoreCase(title))
                .findFirst();
    }

    private void saveArtifact(Session s) {
        String title = s.pendingTitle != null ? s.pendingTitle : s.type;
        boolean replace = s.artifactId != null;
        if (replace) {
            Artifact old = repo.artifact(s.artifactId).orElseThrow();
            if (!old.url().equals(s.pendingUrl)) {
                repo.replaceUrl(s.artifactId, s.pendingUrl, s.user);
            }
            if (!old.displayTitle().equals(title)) {
                repo.renameArtifact(s.artifactId, title, s.user);
            }
        } else {
            repo.addArtifact(s.epic.id(), s.type, title, s.pendingUrl, s.user);
        }
        String line = "➡️" + Html.link(s.pendingUrl, title) + (replace ? " — ссылка заменена" : " — добавлено");
        s.savedLines.add(line);

        if (replace) {
            s.notice = "✅ " + title + " — ссылка заменена";
            showHub(s);
            return;
        }
        // Documents are usually added several at once: offer the next one right away
        s.type = null;
        s.artifactId = null;
        s.pendingUrl = null;
        s.pendingTitle = null;
        s.step = Step.ADD_MORE;
        render(s, "📎 <b>" + Html.escape(s.epic.title()) + "</b>\n\n✅ " + line + "\n\n"
                        + "Добавить ещё артефакт к этому эпику?",
                keyboard(row(button("➕ Ещё артефакт", "add"), button("✏️ Все ссылки эпика", "hub")),
                        row(button("✅ Готово", "done"))));
    }

    private boolean hasChanges(Session s) {
        return !s.savedLines.isEmpty() || s.teamEdited;
    }

    /** End of /artifact_add: one summary of everything changed in this dialog. */
    private void finishAdding(Session s) {
        StringBuilder sb = new StringBuilder("✅ <b>").append(Html.escape(s.epic.title())).append("</b>\n");
        if (s.teamEdited) {
            String team = teamLines(s.epic.id(), true);
            sb.append(team.isEmpty() ? "Исполнители не назначены\n" : team);
            if (!s.savedLines.isEmpty()) {
                sb.append('\n');
            }
        }
        sb.append(String.join("\n", s.savedLines));
        finish(s, sb.toString());
    }

    private void onEpicText(Session s, String text) {
        EpicMatcher.ParsedEpic parsed = matcher.parse(text);

        if (s.kind == Kind.ADD && matcher.foreignPrefix(text) != null) {
            s.notice = "❗ Эпики только из " + matcher.prefix() + ". Пришлите номер эпика и название, например: 123 Название — "
                    + matcher.prefix() + "- бот подставит сам.";
            renderEpicSelect(s);
            return;
        }

        if (s.kind == Kind.ADD && parsed != null) {
            Optional<Epic> existing = repo.epicByKey(s.chatId, parsed.key());
            if (existing.isPresent()) {
                if (existing.get().archived()) {
                    s.notice = "🗄 Эпик " + parsed.key() + " в архиве — его документы смотрите в " + ARCHIVE_LIST_COMMAND;
                    renderEpicSelect(s);
                } else {
                    onEpicChosen(s, existing.get());
                }
                return;
            }
            if (parsed.name() == null) {
                s.notice = "Эпика " + parsed.key() + " ещё нет. Чтобы создать, пришлите номер вместе с названием: "
                        + parsed.key().substring(parsed.key().indexOf('-') + 1) + " Название";
                renderEpicSelect(s);
                return;
            }
            List<Epic> similar = matcher.similar(text, repo.epics(s.chatId, false));
            if (similar.isEmpty()) {
                onEpicCreated(s, repo.createEpic(s.chatId, parsed.key(), parsed.name(), s.user));
                return;
            }
            s.pendingEpic = parsed;
            s.step = Step.EPIC_CONFIRM;
            List<InlineKeyboardRow> rows = new ArrayList<>();
            similar.forEach(e -> rows.add(row(button(e.title(), "e:" + e.id()))));
            rows.add(row(button("➕ Создать " + parsed.key() + " · " + parsed.name(), "new")));
            rows.add(row(cancelButton()));
            render(s, "📎 <b>Похожие эпики уже есть</b>\nМожет, нужный среди них? Если нет — создайте новый:\n<b>"
                    + Html.escape(parsed.key() + " · " + parsed.name()) + "</b>", keyboard(rows));
            return;
        }

        // Search within the list the dialog works with
        List<Epic> base = repo.epics(s.chatId, s.kind == Kind.VIEW_ARCHIVED);
        List<Epic> found = parsed != null
                ? base.stream().filter(e -> e.key().equals(parsed.key())).toList()
                : List.of();
        if (found.isEmpty()) {
            found = matcher.similar(text, base);
        }
        if (found.size() == 1 && parsed != null) {
            onEpicChosen(s, found.getFirst());
            return;
        }
        s.search = text;
        s.shownEpics = found;
        s.page = 0;
        s.step = Step.EPIC_SELECT;
        if (found.isEmpty() && s.kind == Kind.ADD) {
            s.notice = "Чтобы создать новый эпик, пришлите номер и название: 123 Название — "
                    + matcher.prefix() + "- бот подставит сам.";
        }
        renderEpicSelect(s);
    }

    // ------------------------------------------------------------------ assignees

    private void showTeamRoles(Session s) {
        s.step = Step.TEAM_ROLES;
        s.role = null;
        boolean empty = repo.assignees(s.epic.id()).isEmpty();
        InlineKeyboardRow roleRow = new InlineKeyboardRow();
        for (int i = 0; i < roles.size(); i++) {
            roleRow.add(button(roles.get(i), "r:" + i));
        }
        String doneLabel = s.teamThenTypes ? (empty ? "Пропустить ➡️" : "Далее ➡️") : "✅ Готово";
        render(s, teamHeader(s) + teamLines(s.epic.id(), false) + "\n"
                        + (s.teamThenTypes ? "Назначьте исполнителей или пропустите этот шаг.\n" : "")
                        + "Выберите роль, чтобы изменить состав.",
                keyboard(roleRow, row(button(doneLabel, "tdone"), cancelButton())));
    }

    private void showTeamPeople(Session s) {
        s.step = Step.TEAM_PEOPLE;
        List<Assignee> inRole = repo.assignees(s.epic.id()).stream().filter(a -> a.role().equals(s.role)).toList();
        List<Person> candidates = candidates(s);

        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Person p : candidates) {
            boolean selected = inRole.stream().anyMatch(a -> Long.valueOf(p.userId()).equals(a.userId()));
            String tag = hasRoleTag(s, p) ? " · " + tagOf(s, p.userId()) : "";
            buttons.add(button((selected ? "✅ " : "") + p.name() + tag, "u:" + p.userId()));
        }
        // Names typed by hand: shown as selected, a press removes them
        inRole.stream().filter(a -> a.userId() == null)
                .forEach(a -> buttons.add(button("✅ " + a.name(), "m:" + a.id())));

        int pages = Math.max(1, (buttons.size() + PEOPLE_PAGE_SIZE - 1) / PEOPLE_PAGE_SIZE);
        s.peoplePage = Math.clamp(s.peoplePage, 0, pages - 1);
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow current = new InlineKeyboardRow();
        for (InlineKeyboardButton b : buttons.subList(s.peoplePage * PEOPLE_PAGE_SIZE,
                Math.min(buttons.size(), (s.peoplePage + 1) * PEOPLE_PAGE_SIZE))) {
            current.add(b);
            if (current.size() == 2) {
                rows.add(current);
                current = new InlineKeyboardRow();
            }
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        if (pages > 1) {
            rows.add(row(
                    button(s.peoplePage > 0 ? "◀️" : "·", s.peoplePage > 0 ? "pp:" + (s.peoplePage - 1) : "n"),
                    button((s.peoplePage + 1) + " / " + pages, "n"),
                    button(s.peoplePage < pages - 1 ? "▶️" : "·", s.peoplePage < pages - 1 ? "pp:" + (s.peoplePage + 1) : "n")));
        }
        rows.add(row(button("✍️ Ввести вручную", "man"), button("✅ Готово", "roles")));

        String tagHint = roleTag(s) != null ? "Сначала — участники с тегом " + Html.escape(roleTag(s)) + ".\n" : "";
        render(s, teamHeader(s) + "Роль: <b>" + Html.escape(s.role) + "</b>\n" + tagHint
                        + "Нажмите на человека, чтобы добавить его; ✅ — уже добавлен, повторное нажатие убирает.\n"
                        + "<i>Бот знает только админов и тех, кто писал в чат; остальных — «Ввести вручную».</i>",
                keyboard(rows));
    }

    /** People the bot knows in this chat (seen members + administrators), by name. */
    private List<Person> candidates(Session s) {
        if (s.admins == null) {
            s.admins = chat.administrators(s.chatId);
        }
        Map<Long, Person> byId = new LinkedHashMap<>();
        repo.chatMembers(s.chatId).forEach(p -> byId.put(p.userId(), p));
        s.admins.forEach(p -> byId.putIfAbsent(p.userId(), p));
        // Assigned earlier but no longer seen — keep them visible so they can be removed
        repo.assignees(s.epic.id()).stream()
                .filter(a -> a.role().equals(s.role) && a.userId() != null && !byId.containsKey(a.userId()))
                .forEach(a -> byId.put(a.userId(), new Person(a.userId(), a.name(), null)));
        // Members whose tag matches the role (BA for БА, …) go first, then everyone else by name
        List<Person> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing((Person p) -> !hasRoleTag(s, p))
                .thenComparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Tag that corresponds to the current role (БА -> BA), or null if the role has none. */
    private String roleTag(Session s) {
        return s.role == null ? null : roleTags.get(s.role);
    }

    private boolean hasRoleTag(Session s, Person p) {
        String wanted = roleTag(s);
        return wanted != null && wanted.equalsIgnoreCase(tagOf(s, p.userId()));
    }

    /** Member tag, looked up once per dialog (Telegram asks one member at a time). */
    private String tagOf(Session s, long userId) {
        String tag = s.tags.computeIfAbsent(userId, id -> {
            String t = chat.memberTag(s.chatId, id);
            return t == null ? "" : t;
        });
        return tag.isEmpty() ? null : tag;
    }

    private void toggleAssignee(Session s, long userId) {
        if (!can(s, Permission.ARTIFACT_EDIT)) {
            return;
        }
        Optional<Assignee> existing = repo.assignees(s.epic.id()).stream()
                .filter(a -> a.role().equals(s.role) && Long.valueOf(userId).equals(a.userId()))
                .findFirst();
        if (existing.isPresent()) {
            repo.removeAssignee(existing.get().id(), s.user);
            s.teamEdited = true;
        } else {
            candidates(s).stream().filter(p -> p.userId() == userId).findFirst()
                    .ifPresent(p -> repo.addAssignee(s.epic.id(), s.role, p.userId(), p.name(), s.user));
            s.teamEdited = true;
        }
        showTeamPeople(s);
    }

    private void onManualAssignee(Session s, String text) {
        if (!can(s, Permission.ARTIFACT_EDIT)) {
            showTeamPeople(s);
            return;
        }
        if (text.isEmpty() || text.length() > MAX_NAME) {
            s.notice = "❗ Имя должно быть от 1 до " + MAX_NAME + " символов.";
            onAction(s, "man", "");
            return;
        }
        // A known member by @username or by exactly the same name — link the person, don't create a text duplicate
        String username = text.startsWith("@") ? text.substring(1) : null;
        Optional<Person> known = candidates(s).stream()
                .filter(p -> username != null
                        ? p.username() != null && p.username().equalsIgnoreCase(username)
                        : p.name().equalsIgnoreCase(text))
                .findFirst();
        String name = known.map(Person::name).orElse(text);
        repo.addAssignee(s.epic.id(), s.role, known.map(Person::userId).orElse(null), name, s.user);
        s.teamEdited = true;

        // Back to the roles: the step is clearly done (staying on the list invited a press that removed the person)
        s.notice = "✅ " + name + " — " + s.role;
        showTeamRoles(s);
    }

    private String teamHeader(Session s) {
        return "👥 <b>Исполнители " + Html.escape(s.epic.title()) + "</b>\n\n";
    }

    /** "БА - Алина Мамонтова\n…"; roles without people are shown as "—" or skipped. */
    private String teamLines(long epicId, boolean skipEmpty) {
        Map<String, List<String>> byRole = new LinkedHashMap<>();
        roles.forEach(r -> byRole.put(r, new ArrayList<>()));
        repo.assignees(epicId).forEach(a -> byRole.computeIfAbsent(a.role(), r -> new ArrayList<>()).add(a.name()));
        StringBuilder sb = new StringBuilder();
        byRole.forEach((role, names) -> {
            if (!names.isEmpty() || !skipEmpty) {
                sb.append(Html.escape(role)).append(" - ")
                        .append(names.isEmpty() ? "—" : Html.escape(String.join(", ", names))).append('\n');
            }
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------ rendering

    private void renderEpicSelect(Session s) {
        render(s, epicSelectText(s), epicKeyboard(s));
    }

    private String epicSelectText(Session s) {
        String hint = "Выберите эпик или пришлите номер / часть названия для поиска.";
        StringBuilder text = new StringBuilder(switch (s.kind) {
            case ADD -> "📎 <b>Артефакты эпика: добавить или изменить</b>\nВыберите эпик или пришлите номер и название нового:\n"
                    + "<code>123 Название эпика</code> — " + matcher.prefix() + "- бот подставит сам.\n"
                    + "Можно прислать номер или часть названия — поищу среди существующих.";
            case VIEW -> "📂 <b>Артефакты эпика</b>\n" + hint;
            case VIEW_ARCHIVED -> "🗄 <b>Архив эпиков</b>\n" + hint;
            case ARCHIVE -> "📦 <b>Архивация эпика</b>\n" + hint;
            case CLEANUP -> "🧹 <b>Очистка тестовых данных</b>";
        });
        if (s.search != null) {
            text.append("\n\n🔍 «").append(Html.escape(s.search)).append("»: ")
                    .append(s.shownEpics.isEmpty() ? "ничего не найдено" : "найдено " + s.shownEpics.size());
        }
        return text.toString();
    }

    private InlineKeyboardMarkup epicKeyboard(Session s) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<Epic> epics = s.shownEpics;
        int pages = Math.max(1, (epics.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        s.page = Math.clamp(s.page, 0, pages - 1);

        epics.stream().skip((long) s.page * PAGE_SIZE).limit(PAGE_SIZE)
                .forEach(e -> rows.add(row(button(e.title(), "e:" + e.id()))));

        if (pages > 1) {
            InlineKeyboardRow nav = new InlineKeyboardRow();
            nav.add(button(s.page > 0 ? "◀️" : "·", s.page > 0 ? "p:" + (s.page - 1) : "n"));
            nav.add(button((s.page + 1) + " / " + pages, "n"));
            nav.add(button(s.page < pages - 1 ? "▶️" : "·", s.page < pages - 1 ? "p:" + (s.page + 1) : "n"));
            rows.add(nav);
        }
        if (s.search != null) {
            rows.add(row(button("↩️ Все эпики", "all"), cancelButton()));
        } else {
            rows.add(row(cancelButton()));
        }
        return keyboard(rows);
    }

    private void renderTypeSelect(Session s) {
        s.type = null;
        Map<String, Long> counts = new HashMap<>();
        repo.artifacts(s.epic.id()).forEach(a -> counts.merge(a.type(), 1L, Long::sum));

        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow current = new InlineKeyboardRow();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            long count = counts.getOrDefault(type, 0L);
            String label = type + (count == 1 ? " ✅" : count > 1 ? " ✅×" + count : "");
            current.add(button(label, "t:" + i));
            if (current.size() == TYPES_PER_ROW) {
                rows.add(current);
                current = new InlineKeyboardRow();
            }
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        rows.add(row(cancelButton()));
        render(s, header(s) + "Выберите тип артефакта (✅ — ссылка уже есть, выбор заменит её):", keyboard(rows));
    }

    /** "СТ ВТБО" stays as is; "ИБ Figma" of type Макеты becomes "Макеты · ИБ Figma". */
    private static String artifactLabel(Artifact a) {
        String title = a.displayTitle();
        return title.toLowerCase().startsWith(a.type().toLowerCase()) ? title : a.type() + " · " + title;
    }

    /** "📎 STRLP-123 · Name · СТ", ending with an empty line. */
    private String header(Session s) {
        StringBuilder sb = new StringBuilder(switch (s.kind) {
            case ARCHIVE -> "📦 ";
            default -> "📎 ";
        }).append("<b>").append(Html.escape(s.epic.title()));
        // "СТ ВТБО" once the slot is known, otherwise just the type
        String what = s.pendingTitle != null && !s.changingSystem ? s.pendingTitle : s.type;
        if (what != null) {
            sb.append(" · ").append(Html.escape(what));
        }
        return sb.append("</b>\n\n").toString();
    }

    /**
     * 📂 STRLP-101 · Name
     *
     * БА - Алина Мамонтова
     * Дизайн - Олег Кудряшов, Алина Султанова
     *
     * ➡️БТ по кешбэку в лесенке
     * ➡️СТ ВТБО
     */
    private String report(Epic epic) {
        StringBuilder sb = new StringBuilder("📂 <b>").append(Html.escape(epic.title())).append("</b>")
                .append(epic.archived() ? " <i>(в архиве)</i>" : "").append("\n\n");
        String team = teamLines(epic.id(), true);
        if (!team.isEmpty()) {
            sb.append(team).append('\n');
        }
        List<Artifact> artifacts = sorted(repo.artifacts(epic.id()));
        if (artifacts.isEmpty()) {
            sb.append("Артефактов пока нет.\n");
        } else {
            artifacts.forEach(a -> sb.append("➡️").append(titledLink(a)).append('\n'));
        }
        sb.append("\n<i>Сообщение удалится через ").append(resultTtl.toMinutes()).append(" мин.</i>");
        return sb.toString();
    }

    private void render(Session s, String text, InlineKeyboardMarkup keyboard) {
        if (s.notice != null) {
            text = text + "\n\n" + Html.escape(s.notice);
            s.notice = null;
        }
        if (s.repost) {
            // After a text reply the panel is above it: send it anew at the bottom instead of editing in place
            s.repost = false;
            int old = s.panelId;
            s.panelId = chat.send(s.chatId, text, keyboard);
            chat.delete(s.chatId, old);
        } else {
            chat.edit(s.chatId, s.panelId, text, keyboard);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Deletes the dialog messages and posts the result (deleted after resultTtl). */
    private void finish(Session s, String resultHtml) {
        cleanup(s);
        int id = chat.send(s.chatId, resultHtml, null);
        chat.deleteLater(s.chatId, id, resultTtl);
        log.info("Dialog {} finished by {}", s.kind, s.user);
    }

    private void cleanup(Session s) {
        sessions.remove(key(s.chatId, s.userId), s);
        if (s.panelId > 0) {
            chat.delete(s.chatId, s.panelId);
        }
        s.userMessages.forEach(id -> chat.delete(s.chatId, id));
    }

    private synchronized void expireSessions() {
        try {
            Instant deadline = Instant.now().minus(timeout);
            new ArrayList<>(sessions.values()).stream()
                    .filter(s -> s.lastActivity.isBefore(deadline))
                    .forEach(s -> {
                        log.info("Dialog {} of {} timed out", s.kind, s.user);
                        cleanup(s);
                    });
        } catch (RuntimeException e) {
            log.error("Failed to expire dialogs", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean can(long chatId, User user, Permission permission) {
        return access.allowed(chatId, user.getId(), permission);
    }

    private boolean can(Session s, Permission permission) {
        return access.allowed(s.chatId, s.userId, permission);
    }

    /** By configured type order, then in the order they were added. */
    private List<Artifact> sorted(List<Artifact> artifacts) {
        List<Artifact> result = new ArrayList<>(artifacts);
        result.sort(Comparator.comparingInt((Artifact a) -> typeOrder(a.type())).thenComparingLong(Artifact::id));
        return result;
    }

    private int typeOrder(String type) {
        int i = types.indexOf(type);
        return i >= 0 ? i : types.size();
    }

    private static String key(long chatId, long userId) {
        return chatId + ":" + userId;
    }

    /** "Алина Мамонтова" — as in the Telegram profile. */
    static String displayName(User u) {
        String name = u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : "");
        return name.isBlank() && u.getUserName() != null ? "@" + u.getUserName() : name.trim();
    }

    private static String userLabel(User u) {
        String name = u.getUserName() != null ? "@" + u.getUserName() : displayName(u);
        return name + " (" + u.getId() + ")";
    }

    private static String titledLink(Artifact a) {
        return Html.link(a.url(), a.displayTitle());
    }

    /** "https://www.figma.com/file/abc..." -> "figma.com/file/abc…" */
    static String shortUrl(String url) {
        String s = url.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "");
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    private static InlineKeyboardButton button(String text, String data) {
        String label = text.length() > MAX_BUTTON_TEXT ? text.substring(0, MAX_BUTTON_TEXT - 1) + "…" : text;
        return InlineKeyboardButton.builder().text(label).callbackData(data).build();
    }

    /** Back to the epic panel of /artifact_add. */
    private static InlineKeyboardButton backButton() {
        return button("◀️ К эпику", "hub");
    }

    private static InlineKeyboardButton cancelButton() {
        return button("✖️ Отмена", "x");
    }

    private static InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private static InlineKeyboardMarkup keyboard(InlineKeyboardRow... rows) {
        return keyboard(List.of(rows));
    }

    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardRow> rows) {
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
