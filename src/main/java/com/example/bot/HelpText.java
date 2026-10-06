package com.example.bot;

import com.example.access.Permission;
import com.example.config.AppConfig;
import com.example.util.Html;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * /help text: what the bot does and which commands it has. Built from the config, so times,
 * meetings and access rules are always current.
 */
final class HelpText {

    enum ChatKind { WORK, TEST, OTHER }

    private HelpText() {}

    /**
     * @param artifactsEnabled whether artifact commands are on (artifacts.enabled)
     * @param rights permissions of the user who asked (null outside work/test chats or when artifacts are off)
     */
    static String build(AppConfig config, ChatKind kind, boolean artifactsEnabled, Set<Permission> rights) {
        String zone = config.zoneLabel();
        StringBuilder sb = new StringBuilder()
                .append("🤖 <b>Бот команды ").append(Html.escape(config.meetingTeam())).append("</b>\n")
                .append("Напоминаю о задачах и встречах спринта")
                .append(artifactsEnabled ? " и храню ссылки на документы по эпикам" : "").append(".\n\n");

        sb.append("<b>⏰ Сам, без команд</b> (время ").append(zone).append(")\n");
        meetingsLine(config).ifPresent(sb::append);
        sb.append("• ").append(config.sprintStartTime())
                .append(" в первый день спринта — предупреждение «задачи сегодня не закрываем»\n")
                .append("• ").append(config.reminderTime())
                .append(" в остальные рабочие дни — напоминание закрыть задачи (в закрепе до ")
                .append(config.unpinTime()).append(")\n")
                .append("Выходные и праздники по производственному календарю РФ пропускаются.\n\n");

        if (artifactsEnabled && kind != ChatKind.OTHER) {
            sb.append("<b>📂 Артефакты эпиков</b>\n")
                    .append("/artifacts — ссылки на документы эпика\n")
                    .append("/artifacts_archive — ссылки по архивным эпикам\n")
                    .append("/artifact_add — добавить ссылку на документ\n")
                    .append("/artifact_edit — заменить или удалить ссылку\n")
                    .append("/epic_archive — отправить эпик в архив\n")
                    .append("Типы документов: ").append(Html.escape(String.join(", ", config.artifactTypes()))).append("\n\n")
                    .append("<b>🔐 Кто может</b>\n")
                    .append("• смотреть — все\n")
                    .append("• добавлять и заменять — ").append(who(config, Permission.ARTIFACT_EDIT)).append('\n');
            String delete = who(config, Permission.ARTIFACT_DELETE);
            String archive = who(config, Permission.EPIC_ARCHIVE);
            if (delete.equals(archive)) {
                sb.append("• удалять ссылки и архивировать эпики — ").append(delete).append('\n');
            } else {
                sb.append("• удалять ссылки — ").append(delete).append('\n')
                        .append("• архивировать эпики — ").append(archive).append('\n');
            }
            sb.append("• админы группы — всё; без тега — только просмотр\n\n");
        }

        sb.append("<b>ℹ️ Прочее</b>\n")
                .append("/sprint — даты спринта, дни напоминаний и встреч\n")
                .append("/chatid — id текущего чата (нужен для настройки)\n")
                .append("/help — эта справка\n");

        if (kind == ChatKind.TEST) {
            sb.append("\n<b>🧪 Тестовый чат</b>\n")
                    .append("/test — пример напоминания закрыть задачи\n")
                    .append("/test start — пример предупреждения первого дня спринта\n")
                    .append("/test daily | planning | review — пример напоминания о встрече\n");
            if (artifactsEnabled) {
                sb.append("/test_cleanup — удалить артефакты, созданные в этом чате\n");
            }
        }

        if (rights != null) {
            sb.append("\n👤 <b>Вам доступно:</b> ").append(rightsText(rights)).append('\n');
        }
        sb.append("\n<i>Сообщение удалится через ").append(config.commandsDeleteAfter().toMinutes()).append(" мин.</i>");
        return sb.toString();
    }

    private static Optional<String> meetingsLine(AppConfig config) {
        if (config.meetings().isEmpty()) {
            return Optional.empty();
        }
        String meetings = config.meetings().values().stream()
                .map(m -> m.type().title().toLowerCase() + " " + m.time())
                .collect(Collectors.joining(", "));
        return Optional.of("• за " + config.meetingRemindBefore().toMinutes()
                + " мин до встреч (" + meetings + ") — напоминание со ссылкой на комнату, удаляется через "
                + config.meetingDeleteAfter().toMinutes() + " мин\n");
    }

    /** "SA, BA, …" — tags allowed besides admins; empty rule — any tagged member. */
    private static String who(AppConfig config, Permission permission) {
        Set<String> tags = config.accessRules().getOrDefault(permission, Set.of());
        return tags.isEmpty() ? "участники с любым тегом" : Html.escape(String.join(", ", sortedTags(tags)));
    }

    private static List<String> sortedTags(Set<String> tags) {
        List<String> list = new ArrayList<>(tags);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private static String rightsText(Set<Permission> rights) {
        List<String> parts = new ArrayList<>();
        parts.add("просмотр");
        if (rights.contains(Permission.ARTIFACT_EDIT)) {
            parts.add("добавление и замена ссылок");
        }
        if (rights.contains(Permission.ARTIFACT_DELETE)) {
            parts.add("удаление ссылок");
        }
        if (rights.contains(Permission.EPIC_ARCHIVE)) {
            parts.add("архивация эпиков");
        }
        return String.join(", ", parts);
    }
}
