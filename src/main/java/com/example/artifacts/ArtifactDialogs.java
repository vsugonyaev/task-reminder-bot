package com.example.artifacts;

import com.example.access.Permission;
import com.example.access.PermissionChecker;
import com.example.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.net.URI;
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
 *  - /artifact_add      — choose or create an epic (a new one gets an optional "assignees" step),
 *                         choose a type, send a link and give it a title (add or replace);
 *  - /artifact_edit     — choose an epic, then an artifact (replace link, rename, delete) or assignees;
 *  - /artifacts         — choose an epic, get its assignees and links;
 *  - /epic_archive      — archive an epic;
 *  - /artifacts_archive — the same for archived epics.
 *
 * One dialog per user per chat; only its author can press the buttons. When a dialog ends (done, cancelled
 * or timed out) all its messages are deleted; results are posted separately and deleted after a while.
 * Not thread-safe by itself — all entry points are synchronized.
 */
public class ArtifactDialogs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDialogs.class);

    public static final Map<String, String> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("/artifacts", "Артефакты эпика");
        COMMANDS.put("/artifact_add", "Добавить артефакт");
        COMMANDS.put("/artifact_edit", "Изменить ссылку или исполнителей");
        COMMANDS.put("/epic_archive", "Отправить эпик в архив");
        COMMANDS.put("/artifacts_archive", "Артефакты архивных эпиков");
    }

    /** Available only in test chats: permanently deletes artifact data created in the chat. */
    public static final String CLEANUP_COMMAND = "/test_cleanup";

    private static final int PAGE_SIZE = 8;
    private static final int PEOPLE_PAGE_SIZE = 16;
    private static final int TYPES_PER_ROW = 3;
    private static final int MAX_BUTTON_TEXT = 48;
    private static final int MAX_TITLE = 100;
    private static final int MAX_NAME = 64;

    private enum Kind { ADD, EDIT, VIEW, VIEW_ARCHIVED, ARCHIVE, CLEANUP }

    private enum Step {
        EPIC_SELECT, EPIC_CONFIRM, TYPE_SELECT, MODE_SELECT, REPLACE_SELECT, LINK_INPUT, TITLE_INPUT,
        ARTIFACT_SELECT, ACTION_SELECT, RENAME_INPUT, DELETE_CONFIRM, ARCHIVE_CONFIRM, CLEANUP_CONFIRM,
        TEAM_ROLES, TEAM_PEOPLE, TEAM_MANUAL
    }

    /** Steps that wait for a text message from the dialog author. */
    private static final Set<Step> TEXT_STEPS = Set.of(
            Step.EPIC_SELECT, Step.EPIC_CONFIRM, Step.LINK_INPUT, Step.TITLE_INPUT, Step.RENAME_INPUT, Step.TEAM_MANUAL);

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

        /** Assignees step of a just created epic: continue to the type choice afterwards. */
        boolean teamThenTypes;
        String role;
        int peoplePage;
        List<Person> admins;

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
    private final Duration resultTtl;
    private final Duration timeout;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "artifact-dialogs-sweeper");
        t.setDaemon(true);
        return t;
    });

    public ArtifactDialogs(ChatApi chat, ArtifactRepository repo, EpicMatcher matcher, List<String> types,
                           List<String> roles, Duration resultTtl, Duration timeout, PermissionChecker access,
                           Set<Long> testChatIds) {
        this.chat = chat;
        this.testChatIds = Set.copyOf(testChatIds);
        this.access = access;
        this.repo = repo;
        this.matcher = matcher;
        this.types = List.copyOf(types);
        this.roles = List.copyOf(roles);
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
            case "/artifact_edit" -> Kind.EDIT;
            case "/artifacts" -> Kind.VIEW;
            case "/artifacts_archive" -> Kind.VIEW_ARCHIVED;
            case "/epic_archive" -> Kind.ARCHIVE;
            case "/test_cleanup" -> Kind.CLEANUP;
            default -> throw new IllegalArgumentException(command);
        };

        boolean allowed = switch (kind) {
            case ADD -> can(chatId, from, Permission.ARTIFACT_EDIT);
            case EDIT -> can(chatId, from, Permission.ARTIFACT_EDIT) || can(chatId, from, Permission.ARTIFACT_DELETE);
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
        Session s = sessions.get(key(chatId, from.getId()));
        if (s == null || !TEXT_STEPS.contains(s.step)) {
            return false;
        }
        s.userMessages.add(messageId);
        s.lastActivity = Instant.now();

        try {
            String t = text.trim();
            switch (s.step) {
                case LINK_INPUT -> onLink(s, t);
                case TITLE_INPUT -> saveArtifact(s, t);
                case RENAME_INPUT -> onRename(s, t);
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
            case "t" -> {
                s.type = types.get(Integer.parseInt(arg));
                onTypeChosen(s);
            }
            case "add" -> {
                s.artifactId = null;
                askLink(s);
            }
            case "rep" -> {
                if (!can(s, Permission.ARTIFACT_EDIT)) {
                    return;
                }
                if (s.step == Step.ACTION_SELECT) {
                    askLink(s);
                    return;
                }
                List<Artifact> existing = artifactsOfType(s);
                if (existing.size() == 1) {
                    s.artifactId = existing.getFirst().id();
                    askLink(s);
                } else {
                    s.step = Step.REPLACE_SELECT;
                    render(s, header(s) + "Какую ссылку заменить?", artifactKeyboard(existing, false));
                }
            }
            case "a" -> {
                s.artifactId = Long.parseLong(arg);
                if (s.step == Step.REPLACE_SELECT) {
                    askLink(s);
                } else {
                    showArtifactActions(s);
                }
            }
            case "ren" -> {
                if (!can(s, Permission.ARTIFACT_EDIT)) {
                    return;
                }
                s.step = Step.RENAME_INPUT;
                Artifact a = repo.artifact(s.artifactId).orElseThrow();
                render(s, header(s) + "Сейчас: " + titledLink(a) + "\n\nПришлите новое название ссылки.",
                        keyboard(row(cancelButton())));
            }
            case "keep" -> {
                if (s.step == Step.TITLE_INPUT) {
                    saveArtifact(s, defaultTitle(s));
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
                        keyboard(row(button("🗑 Да, удалить", "yes"), cancelButton())));
            }
            case "yes" -> {
                if (s.step == Step.DELETE_CONFIRM && can(s, Permission.ARTIFACT_DELETE)) {
                    Artifact a = repo.artifact(s.artifactId).orElseThrow();
                    repo.deleteArtifact(s.artifactId, s.user);
                    finish(s, "🗑 <b>" + Html.escape(s.epic.title()) + "</b>\n"
                            + Html.escape(a.displayTitle()) + " — ссылка удалена");
                } else if (s.step == Step.CLEANUP_CONFIRM && testChatIds.contains(s.chatId)
                        && can(s, Permission.ARTIFACT_DELETE)) {
                    ArtifactRepository.ChatDataStats deleted = repo.deleteChatData(s.chatId);
                    finish(s, "🧹 Тестовые данные удалены: эпиков — " + deleted.epics()
                            + ", артефактов — " + deleted.artifacts() + ".");
                } else if (s.step == Step.ARCHIVE_CONFIRM && can(s, Permission.EPIC_ARCHIVE)) {
                    repo.archiveEpic(s.epic.id(), s.user);
                    finish(s, "📦 Эпик <b>" + Html.escape(s.epic.title()) + "</b> отправлен в архив.\n"
                            + "Документы по нему — в /artifacts_archive");
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
                    s.step = Step.TYPE_SELECT;
                    renderTypeSelect(s);
                } else {
                    finish(s, "👥 <b>" + Html.escape(s.epic.title()) + "</b>\n" + teamLines(s.epic.id(), true)
                            + "\nИсполнители сохранены.");
                }
            }
            default -> log.warn("Unknown dialog action: {}", action);
        }
    }

    private void onEpicChosen(Session s, Epic epic) {
        s.epic = epic;
        s.notice = null;
        switch (s.kind) {
            case ADD -> {
                s.step = Step.TYPE_SELECT;
                renderTypeSelect(s);
            }
            case EDIT -> {
                List<Artifact> artifacts = sorted(repo.artifacts(epic.id()));
                boolean canTeam = can(s, Permission.ARTIFACT_EDIT);
                if (artifacts.isEmpty() && !canTeam) {
                    finish(s, "📂 <b>" + Html.escape(epic.title()) + "</b>\nАртефактов пока нет.");
                    return;
                }
                s.step = Step.ARTIFACT_SELECT;
                List<InlineKeyboardRow> rows = new ArrayList<>();
                artifacts.forEach(a -> rows.add(row(button(artifactLabel(a), "a:" + a.id()))));
                if (canTeam) {
                    rows.add(row(button("👥 Исполнители", "team")));
                }
                rows.add(row(cancelButton()));
                render(s, header(s) + (artifacts.isEmpty() ? "Артефактов пока нет." : "Что изменить?"), keyboard(rows));
            }
            case VIEW, VIEW_ARCHIVED -> finish(s, report(epic));
            case ARCHIVE -> {
                s.step = Step.ARCHIVE_CONFIRM;
                render(s, header(s) + "Отправить эпик в архив?\n"
                                + "<i>Он пропадёт из списков, документы останутся доступны через /artifacts_archive.</i>",
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

    private void showArtifactActions(Session s) {
        Optional<Artifact> a = repo.artifact(s.artifactId);
        if (a.isEmpty()) {
            return;
        }
        s.type = a.get().type();
        s.step = Step.ACTION_SELECT;
        InlineKeyboardRow actions = new InlineKeyboardRow();
        if (can(s, Permission.ARTIFACT_EDIT)) {
            actions.add(button("🔁 Ссылку", "rep"));
            actions.add(button("✏️ Название", "ren"));
        }
        if (can(s, Permission.ARTIFACT_DELETE)) {
            actions.add(button("🗑 Удалить", "del"));
        }
        render(s, header(s) + titledLink(a.get()) + "\n\nЧто сделать?", keyboard(actions, row(cancelButton())));
    }

    private void onTypeChosen(Session s) {
        List<Artifact> existing = artifactsOfType(s);
        if (existing.isEmpty()) {
            s.artifactId = null;
            askLink(s);
            return;
        }
        s.step = Step.MODE_SELECT;
        StringBuilder text = new StringBuilder(header(s)).append("Уже есть:\n");
        existing.forEach(a -> text.append("➡️").append(titledLink(a)).append('\n'));
        text.append("\nДобавить ещё одну ссылку или заменить существующую?");
        render(s, text.toString(), keyboard(
                row(button("➕ Добавить", "add"), button("🔁 Заменить", "rep")),
                row(cancelButton())));
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

    private void onLink(Session s, String url) {
        if (!isUrl(url)) {
            s.notice = "❗ Это не похоже на ссылку. Нужна ссылка вида https://…";
            askLink(s);
            return;
        }
        s.pendingUrl = url;
        askTitle(s);
    }

    private void askTitle(Session s) {
        s.step = Step.TITLE_INPUT;
        String def = defaultTitle(s);
        render(s, header(s) + "Ссылка: " + Html.link(s.pendingUrl, shortUrl(s.pendingUrl)) + "\n\n"
                        + "Как назвать её в списке? Пришлите название, например «" + Html.escape(s.type)
                        + " ВТБО» или «Макеты ИБ Figma».",
                keyboard(row(button("Оставить «" + def + "»", "keep")), row(cancelButton())));
    }

    /** Current title when replacing a link, otherwise the type. */
    private String defaultTitle(Session s) {
        if (s.artifactId != null) {
            Optional<Artifact> a = repo.artifact(s.artifactId);
            if (a.isPresent()) {
                return a.get().displayTitle();
            }
        }
        return s.type;
    }

    private void saveArtifact(Session s, String title) {
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            s.notice = "❗ Название должно быть от 1 до " + MAX_TITLE + " символов.";
            askTitle(s);
            return;
        }
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
        finish(s, "✅ <b>" + Html.escape(s.epic.title()) + "</b>\n"
                + "➡️" + Html.link(s.pendingUrl, title) + (replace ? " — ссылка заменена" : " — добавлено"));
    }

    private void onRename(Session s, String title) {
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            s.notice = "❗ Название должно быть от 1 до " + MAX_TITLE + " символов.";
            onAction(s, "ren", "");
            return;
        }
        Artifact a = repo.artifact(s.artifactId).orElseThrow();
        repo.renameArtifact(a.id(), title, s.user);
        finish(s, "✏️ <b>" + Html.escape(s.epic.title()) + "</b>\n➡️" + Html.link(a.url(), title) + " — название изменено");
    }

    private void onEpicText(Session s, String text) {
        EpicMatcher.ParsedEpic parsed = matcher.parse(text);

        if (s.kind == Kind.ADD && parsed != null) {
            Optional<Epic> existing = repo.epicByKey(s.chatId, parsed.key());
            if (existing.isPresent()) {
                if (existing.get().archived()) {
                    s.notice = "🗄 Эпик " + parsed.key() + " в архиве — его документы смотрите в /artifacts_archive";
                    renderEpicSelect(s);
                } else {
                    onEpicChosen(s, existing.get());
                }
                return;
            }
            if (parsed.name() == null) {
                s.notice = "Эпика " + parsed.key() + " ещё нет. Чтобы создать, пришлите ключ вместе с названием: "
                        + parsed.key() + " Название";
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
            s.notice = "Чтобы создать новый эпик, пришлите ключ и название: STRLPL-123 Название";
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
            buttons.add(button((selected ? "✅ " : "") + p.name(), "u:" + p.userId()));
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
        rows.add(row(button("✍️ Ввести вручную", "man"), button("◀️ К ролям", "roles")));

        render(s, teamHeader(s) + "Роль: <b>" + Html.escape(s.role) + "</b>\n"
                        + "Нажмите на человека, чтобы добавить или убрать его.\n"
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
        List<Person> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER));
        return result;
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
        } else {
            candidates(s).stream().filter(p -> p.userId() == userId).findFirst()
                    .ifPresent(p -> repo.addAssignee(s.epic.id(), s.role, p.userId(), p.name(), s.user));
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
        if (text.startsWith("@")) {
            String username = text.substring(1);
            Optional<Person> known = candidates(s).stream()
                    .filter(p -> p.username() != null && p.username().equalsIgnoreCase(username))
                    .findFirst();
            if (known.isPresent()) {
                repo.addAssignee(s.epic.id(), s.role, known.get().userId(), known.get().name(), s.user);
                showTeamPeople(s);
                return;
            }
        }
        repo.addAssignee(s.epic.id(), s.role, null, text, s.user);
        showTeamPeople(s);
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
            case ADD -> "📎 <b>Добавление артефакта</b>\nВыберите эпик или пришлите ключ и название нового:\n"
                    + "<code>STRLPL-123 Название эпика</code>\nМожно прислать номер или часть названия — поищу.";
            case EDIT -> "✏️ <b>Изменение артефактов и исполнителей</b>\n" + hint;
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
        render(s, header(s) + "Выберите тип артефакта (✅ — уже есть ссылки):", keyboard(rows));
    }

    private InlineKeyboardMarkup artifactKeyboard(List<Artifact> artifacts, boolean withType) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            rows.add(row(button(withType ? artifactLabel(a) : (i + 1) + ". " + a.displayTitle(), "a:" + a.id())));
        }
        rows.add(row(cancelButton()));
        return keyboard(rows);
    }

    /** "СТ ВТБО" stays as is; "ИБ Figma" of type Макеты becomes "Макеты · ИБ Figma". */
    private static String artifactLabel(Artifact a) {
        String title = a.displayTitle();
        return title.toLowerCase().startsWith(a.type().toLowerCase()) ? title : a.type() + " · " + title;
    }

    /** "📎 STRLPL-123 · Name · СТ", ending with an empty line. */
    private String header(Session s) {
        StringBuilder sb = new StringBuilder(switch (s.kind) {
            case EDIT -> "✏️ ";
            case ARCHIVE -> "📦 ";
            default -> "📎 ";
        }).append("<b>").append(Html.escape(s.epic.title()));
        if (s.type != null) {
            sb.append(" · ").append(Html.escape(s.type));
        }
        return sb.append("</b>\n\n").toString();
    }

    /**
     * 📂 STRLPL-101 · Name
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
        chat.edit(s.chatId, s.panelId, text, keyboard);
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

    private List<Artifact> artifactsOfType(Session s) {
        return repo.artifacts(s.epic.id()).stream().filter(a -> a.type().equals(s.type)).toList();
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

    static boolean isUrl(String text) {
        if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create(text);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
