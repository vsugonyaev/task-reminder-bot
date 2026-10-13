package com.example.artifacts;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link found in a user message, plus the text around it as a suggested title.
 *
 * Users send links in many ways: a bare URL, "БТ: https://…", a URL without https://, or text copied from
 * Confluence/a browser where the URL is hidden behind the text (a Telegram "text_link"). Telegram marks
 * links with entities, so they are checked first; the text is searched as a fallback.
 */
public record LinkInput(String url, String title) {

    private static final Pattern URL_IN_TEXT = Pattern.compile(
            "(?i)(https?://[^\\s<>\"]+|www\\.[^\\s<>\"]+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}/[^\\s<>\"]*)");
    /** Scheme + host with a dot, no spaces. */
    private static final Pattern VALID = Pattern.compile("(?i)^https?://[^\\s/?#]+\\.[^\\s/?#]+(?:[/?#]\\S*)?$");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)»\"'";

    /** Returns null if the message has no link. */
    public static LinkInput extract(String text, List<MessageEntity> entities) {
        String t = text == null ? "" : text;
        if (entities != null) {
            // Text with a hidden link: the visible text is a ready-made title
            for (MessageEntity e : entities) {
                if ("text_link".equals(e.getType()) && e.getUrl() != null) {
                    LinkInput link = of(e.getUrl(), slice(t, e));
                    if (link != null) {
                        return link;
                    }
                }
            }
            for (MessageEntity e : entities) {
                if ("url".equals(e.getType())) {
                    String visible = slice(t, e);
                    LinkInput link = of(visible, without(t, visible));
                    if (link != null) {
                        return link;
                    }
                }
            }
        }
        Matcher m = URL_IN_TEXT.matcher(t);
        while (m.find()) {
            LinkInput link = of(m.group(1), without(t, m.group(1)));
            if (link != null) {
                return link;
            }
        }
        return null;
    }

    private static LinkInput of(String rawUrl, String title) {
        String url = rawUrl.trim();
        while (!url.isEmpty() && TRAILING_PUNCTUATION.indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.matches("(?i)^https?://.*")) {
            url = "https://" + url;
        }
        if (!VALID.matcher(url).matches()) {
            return null;
        }
        return new LinkInput(url, cleanTitle(title));
    }

    /** "БТ: " / " — СТ ВТБО" / "Макеты ИБ (" → "БТ" / "СТ ВТБО" / "Макеты ИБ"; null if nothing is left. */
    private static String cleanTitle(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim()
                .replaceAll("^[\\s:—–\\-|➡️→)\\].,;»]+", "")
                .replaceAll("[\\s:—–\\-|➡️→(\\[.,;«]+$", "")
                .trim();
        return t.isEmpty() ? null : t;
    }

    private static String slice(String text, MessageEntity e) {
        int from = e.getOffset() == null ? 0 : e.getOffset();
        int to = from + (e.getLength() == null ? 0 : e.getLength());
        return from >= 0 && to <= text.length() && from <= to ? text.substring(from, to) : "";
    }

    private static String without(String text, String part) {
        int i = text.indexOf(part);
        return i < 0 ? text : text.substring(0, i) + " " + text.substring(i + part.length());
    }
}
