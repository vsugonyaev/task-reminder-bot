package com.example.artifacts;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Step-by-step dialogs (inline buttons + one "panel" message edited in place) for artifact commands:
 *
 *  - /artifact_add      — choose or create an epic, choose a type, send a link (add or replace);
 *  - /artifact_edit     — choose an epic and an artifact, replace its link or delete it;
 *  - /artifacts         — choose an epic, get its links;
 *  - /epic_archive      — archive an epic;
 *  - /artifacts_archive — links of archived epics.
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
        COMMANDS.put("/artifact_edit", "Заменить ссылку или удалить артефакт");
        COMMANDS.put("/epic_archive", "Отправить эпик в архив");
        COMMANDS.put("/artifacts_archive", "Артефакты архивных эпиков");
    }

    private static final int PAGE_SIZE = 8;
    private static final int TYPES_PER_ROW = 3;
    private static final int MAX_BUTTON_TEXT = 48;

    private enum Kind { ADD, EDIT, VIEW, VIEW_ARCHIVED, ARCHIVE }

    private enum Step {
        EPIC_SELECT, EPIC_CONFIRM, TYPE_SELECT, MODE_SELECT, REPLACE_SELECT, LINK_INPUT,
        ARTIFACT_SELECT, ACTION_SELECT, DELETE_CONFIRM, ARCHIVE_CONFIRM
    }

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

        Session(Kind kind, long chatId, User from) {
            this.kind = kind;
            this.chatId = chatId;
            this.userId = from.getId();
            this.user = userLabel(from);
        }
    }

    private final ChatApi chat;
    private final ArtifactRepository repo;
    private final EpicMatcher matcher;
    private final List<String> types;
    private final Duration resultTtl;
    private final Duration timeout;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "artifact-dialogs-sweeper");
        t.setDaemon(true);
        return t;
    });

    public ArtifactDialogs(ChatApi chat, ArtifactRepository repo, EpicMatcher matcher, List<String> types,
                           Duration resultTtl, Duration timeout) {
        this.chat = chat;
        this.repo = repo;
        this.matcher = matcher;
        this.types = List.copyOf(types);
        this.resultTtl = resultTtl;
        this.timeout = timeout;
        sweeper.scheduleWithFixedDelay(this::expireSessions, 30, 30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ entry points

    public synchronized void onCommand(String command, long chatId, User from, int messageId) {
        Kind kind = switch (command) {
            case "/artifact_add" -> Kind.ADD;
            case "/artifact_edit" -> Kind.EDIT;
            case "/artifacts" -> Kind.VIEW;
            case "/artifacts_archive" -> Kind.VIEW_ARCHIVED;
            case "/epic_archive" -> Kind.ARCHIVE;
            default -> throw new IllegalArgumentException(command);
        };

        Session old = sessions.get(key(chatId, from.getId()));
        if (old != null) {
            cleanup(old);
        }

        Session s = new Session(kind, chatId, from);
        s.userMessages.add(messageId);
        s.shownEpics = repo.epics(kind == Kind.VIEW_ARCHIVED);

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
        if (s == null || !(s.step == Step.EPIC_SELECT || s.step == Step.EPIC_CONFIRM || s.step == Step.LINK_INPUT)) {
            return false;
        }
        s.userMessages.add(messageId);
        s.lastActivity = Instant.now();

        try {
            if (s.step == Step.LINK_INPUT) {
                onLink(s, text.trim());
            } else {
                onEpicText(s, text.trim());
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
                s.shownEpics = repo.epics(s.kind == Kind.VIEW_ARCHIVED);
                s.page = 0;
                s.step = Step.EPIC_SELECT;
                renderEpicSelect(s);
            }
            case "e" -> repo.epic(Long.parseLong(arg)).ifPresent(epic -> onEpicChosen(s, epic));
            case "new" -> {
                EpicMatcher.ParsedEpic p = s.pendingEpic;
                if (p != null) {
                    Epic epic = repo.epicByKey(p.key()).orElseGet(() -> repo.createEpic(p.key(), p.name(), s.user));
                    onEpicChosen(s, epic);
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
                    Optional<Artifact> a = repo.artifact(s.artifactId);
                    if (a.isEmpty()) {
                        return;
                    }
                    s.type = a.get().type();
                    s.step = Step.ACTION_SELECT;
                    render(s, header(s) + "Ссылка: " + linkTo(a.get().url()) + "\n\nЧто сделать?", keyboard(
                            row(button("🔁 Заменить ссылку", "rep"), button("🗑 Удалить", "del")),
                            row(cancelButton())));
                }
            }
            case "del" -> {
                s.step = Step.DELETE_CONFIRM;
                Artifact a = repo.artifact(s.artifactId).orElseThrow();
                render(s, header(s) + "Удалить ссылку " + linkTo(a.url()) + "?\n"
                                + "<i>Она пропадёт из списков, но останется в истории.</i>",
                        keyboard(row(button("🗑 Да, удалить", "yes"), cancelButton())));
            }
            case "yes" -> {
                if (s.step == Step.DELETE_CONFIRM) {
                    repo.deleteArtifact(s.artifactId, s.user);
                    finish(s, "🗑 <b>" + Html.escape(s.epic.title()) + "</b>\n" + Html.escape(s.type) + ": ссылка удалена");
                } else if (s.step == Step.ARCHIVE_CONFIRM) {
                    repo.archiveEpic(s.epic.id(), s.user);
                    finish(s, "📦 Эпик <b>" + Html.escape(s.epic.title()) + "</b> отправлен в архив.\n"
                            + "Документы по нему — в /artifacts_archive");
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
                if (artifacts.isEmpty()) {
                    finish(s, "📂 <b>" + Html.escape(epic.title()) + "</b>\nАртефактов пока нет. Добавьте через /artifact_add");
                    return;
                }
                s.step = Step.ARTIFACT_SELECT;
                render(s, header(s) + "Какой артефакт изменить?", artifactKeyboard(artifacts, true));
            }
            case VIEW, VIEW_ARCHIVED -> finish(s, report(epic));
            case ARCHIVE -> {
                s.step = Step.ARCHIVE_CONFIRM;
                render(s, header(s) + "Отправить эпик в архив?\n"
                                + "<i>Он пропадёт из списков, документы останутся доступны через /artifacts_archive.</i>",
                        keyboard(row(button("📦 В архив", "yes"), cancelButton())));
            }
        }
    }

    private void onTypeChosen(Session s) {
        List<Artifact> existing = artifactsOfType(s);
        if (existing.isEmpty()) {
            s.artifactId = null;
            askLink(s);
            return;
        }
        s.step = Step.MODE_SELECT;
        StringBuilder text = new StringBuilder(header(s)).append("Уже есть ")
                .append(existing.size() == 1 ? "ссылка" : "ссылки").append(":\n");
        for (int i = 0; i < existing.size(); i++) {
            text.append(i + 1).append(". ").append(linkTo(existing.get(i).url())).append('\n');
        }
        text.append("\nДобавить ещё одну или заменить?");
        render(s, text.toString(), keyboard(
                row(button("➕ Добавить", "add"), button("🔁 Заменить", "rep")),
                row(cancelButton())));
    }

    private void askLink(Session s) {
        s.step = Step.LINK_INPUT;
        StringBuilder text = new StringBuilder(header(s));
        if (s.artifactId != null) {
            repo.artifact(s.artifactId).ifPresent(a -> text.append("Заменяем: ").append(linkTo(a.url())).append("\n\n"));
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
        boolean replace = s.artifactId != null;
        if (replace) {
            repo.replaceUrl(s.artifactId, url, s.user);
        } else {
            repo.addArtifact(s.epic.id(), s.type, url, s.user);
        }
        finish(s, "✅ <b>" + Html.escape(s.epic.title()) + "</b>\n"
                + Html.escape(s.type) + ": " + linkTo(url) + (replace ? " — ссылка заменена" : " — добавлено"));
    }

    private void onEpicText(Session s, String text) {
        EpicMatcher.ParsedEpic parsed = matcher.parse(text);

        if (s.kind == Kind.ADD && parsed != null) {
            Optional<Epic> existing = repo.epicByKey(parsed.key());
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
            List<Epic> similar = matcher.similar(text, repo.epics(false));
            if (similar.isEmpty()) {
                onEpicChosen(s, repo.createEpic(parsed.key(), parsed.name(), s.user));
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
        List<Epic> base = repo.epics(s.kind == Kind.VIEW_ARCHIVED);
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

    // ------------------------------------------------------------------ rendering

    private void renderEpicSelect(Session s) {
        render(s, epicSelectText(s), epicKeyboard(s));
    }

    private String epicSelectText(Session s) {
        String hint = "Выберите эпик или пришлите номер / часть названия для поиска.";
        StringBuilder text = new StringBuilder(switch (s.kind) {
            case ADD -> "📎 <b>Добавление артефакта</b>\nВыберите эпик или пришлите ключ и название нового:\n"
                    + "<code>STRLPL-123 Название эпика</code>\nМожно прислать номер или часть названия — поищу.";
            case EDIT -> "✏️ <b>Изменение артефакта</b>\n" + hint;
            case VIEW -> "📂 <b>Артефакты эпика</b>\n" + hint;
            case VIEW_ARCHIVED -> "🗄 <b>Архив эпиков</b>\n" + hint;
            case ARCHIVE -> "📦 <b>Архивация эпика</b>\n" + hint;
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
        render(s, header(s) + "Выберите тип артефакта (✅ — уже есть ссылка):", keyboard(rows));
    }

    private InlineKeyboardMarkup artifactKeyboard(List<Artifact> artifacts, boolean withType) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            String label = (withType ? a.type() + ": " : (i + 1) + ". ") + shortUrl(a.url());
            rows.add(row(button(label, "a:" + a.id())));
        }
        rows.add(row(cancelButton()));
        return keyboard(rows);
    }

    /** "📎 STRLPL-123 · Name · SA" + notice, ending with an empty line. */
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

    private String report(Epic epic) {
        Map<String, List<Artifact>> byType = new LinkedHashMap<>();
        types.forEach(t -> byType.put(t, new ArrayList<>()));
        repo.artifacts(epic.id()).forEach(a -> byType.computeIfAbsent(a.type(), t -> new ArrayList<>()).add(a));

        StringBuilder sb = new StringBuilder("📂 <b>").append(Html.escape(epic.title())).append("</b>")
                .append(epic.archived() ? " <i>(в архиве)</i>" : "").append("\n\n");
        byType.forEach((type, list) -> {
            sb.append("<b>").append(Html.escape(type)).append("</b>: ");
            if (list.isEmpty()) {
                sb.append("—");
            } else if (list.size() == 1) {
                sb.append(linkTo(list.getFirst().url()));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    sb.append("\n   ").append(i + 1).append(". ").append(linkTo(list.get(i).url()));
                }
            }
            sb.append('\n');
        });
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

    private List<Artifact> artifactsOfType(Session s) {
        return repo.artifacts(s.epic.id()).stream().filter(a -> a.type().equals(s.type)).toList();
    }

    /** Artifacts in the order of configured types. */
    private List<Artifact> sorted(List<Artifact> artifacts) {
        List<Artifact> result = new ArrayList<>(artifacts);
        result.sort((a, b) -> Integer.compare(typeOrder(a.type()), typeOrder(b.type())));
        return result;
    }

    private int typeOrder(String type) {
        int i = types.indexOf(type);
        return i >= 0 ? i : types.size();
    }

    private static String key(long chatId, long userId) {
        return chatId + ":" + userId;
    }

    private static String userLabel(User u) {
        String name = u.getUserName() != null
                ? "@" + u.getUserName()
                : (u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : ""));
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

    private static String linkTo(String url) {
        return Html.link(url, shortUrl(url));
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
