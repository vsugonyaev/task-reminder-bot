package com.example.artifacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses epic input and finds epics similar by name or by number.
 *
 * Users send just the number and the name ("123 Кешбэк в лесенке"); the key prefix (STRLP) is added by the bot.
 * "STRLP-123 Название" is accepted too; any other prefix ("ABC-12", the old "STRLPL-12") is reported as foreign.
 */
public class EpicMatcher {

    /** Parsed user input; name is null when only the number was given. */
    public record ParsedEpic(String key, String name) {}

    private static final double MIN_SCORE = 0.5;
    private static final int MAX_RESULTS = 8;
    private static final Pattern NUMBER = Pattern.compile("\\d{2,}");
    /** A Jira-like key at the start: "ABC-12…", "STRLPL-12…" (Latin letters and a dash, so "Кешбэк 2026" is not one). */
    private static final Pattern ANY_PREFIX = Pattern.compile("^\\s*([A-Za-z]+)\\s*-\\s*\\d+");

    private final String prefix;
    private final Pattern keyPattern;

    public EpicMatcher(String keyPrefix) {
        this.prefix = keyPrefix.toUpperCase(Locale.ROOT);
        // "123 Name", "123 — Name", "123: Name", "123", and the same with "STRLP-" / "strlp " in front
        keyPattern = Pattern.compile("^\\s*(?:" + Pattern.quote(prefix) + "\\s*[-–—]?\\s*)?(\\d+)(?:[\\s\\-–—:.]+(.+?))?\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public String prefix() {
        return prefix;
    }

    public ParsedEpic parse(String text) {
        if (foreignPrefix(text) != null) {
            return null;
        }
        Matcher m = keyPattern.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String name = m.group(2) != null && !m.group(2).isBlank() ? m.group(2).trim() : null;
        return new ParsedEpic(prefix + "-" + Long.parseLong(m.group(1)), name);
    }

    /** The prefix of a key from another project ("ABC" in "ABC-12 Name"), or null. */
    public String foreignPrefix(String text) {
        Matcher m = ANY_PREFIX.matcher(text);
        if (m.find() && !m.group(1).equalsIgnoreCase(prefix)) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** Epics whose number matches digits in the query or whose name is similar, best first. */
    public List<Epic> similar(String query, List<Epic> epics) {
        List<String> numbers = NUMBER.matcher(query).results().map(r -> r.group()).toList();
        ParsedEpic parsed = parse(query);
        String nameQuery = parsed != null
                ? (parsed.name() != null ? parsed.name() : "")
                : query.replaceAll("\\d+", " ");

        return epics.stream()
                .map(e -> Map.entry(e, Math.max(numberScore(numbers, e), nameScore(nameQuery, e.name()))))
                .filter(en -> en.getValue() >= MIN_SCORE)
                .sorted(Map.Entry.<Epic, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey(Epic.BY_KEY)))
                .limit(MAX_RESULTS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double numberScore(List<String> numbers, Epic epic) {
        String epicNumber = String.valueOf(epic.number());
        double best = 0;
        for (String n : numbers) {
            String stripped = n.replaceFirst("^0+(?=\\d)", "");
            if (stripped.equals(epicNumber)) {
                return 1.0;
            }
            if (stripped.length() >= 3 && epicNumber.length() >= 3
                    && (epicNumber.contains(stripped) || stripped.contains(epicNumber))) {
                best = 0.6;
            }
        }
        return best;
    }

    static double nameScore(String query, String name) {
        List<String> q = tokens(query);
        List<String> n = tokens(name);
        if (q.isEmpty() || n.isEmpty()) {
            return 0;
        }
        long matched = q.stream().filter(qt -> n.stream().anyMatch(nt -> tokensMatch(qt, nt))).count();
        double dice = 2.0 * matched / (q.size() + n.size());
        // All query words found in the name — a strong match even if the name is longer
        double coverage = matched == q.size() ? 0.75 : 0;
        String qs = String.join(" ", q);
        String ns = String.join(" ", n);
        double whole = 1.0 - (double) levenshtein(qs, ns) / Math.max(qs.length(), ns.length());
        return Math.max(dice, Math.max(coverage, whole));
    }

    private static List<String> tokens(String s) {
        String normalized = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ");
        List<String> result = new ArrayList<>();
        for (String t : normalized.trim().split(" ")) {
            if (t.length() >= 3) {
                result.add(t);
            }
        }
        return result;
    }

    /** Same word up to endings and typos: "оплаты" ~ "оплата", "платёж" ~ "платежи". */
    private static boolean tokensMatch(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int common = commonPrefix(a, b);
        if (common >= 4 && common >= Math.min(a.length(), b.length()) - 2) {
            return true;
        }
        int len = Math.min(a.length(), b.length());
        int allowed = len >= 7 ? 2 : len >= 4 ? 1 : 0;
        return levenshtein(a, b) <= allowed;
    }

    private static int commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
