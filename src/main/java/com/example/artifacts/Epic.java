package com.example.artifacts;

import java.time.Instant;
import java.util.Comparator;

/**
 * Epic identified by a Jira-like key (STRLPL-123) plus a human-readable name.
 */
public record Epic(long id, String key, String name, Instant archivedAt) {

    /** STRLPL-2 &lt; STRLPL-10 &lt; STRLPDML-1: by prefix, then by number. */
    public static final Comparator<Epic> BY_KEY = Comparator
            .comparing((Epic e) -> e.prefix())
            .thenComparingLong(Epic::number);

    public boolean archived() {
        return archivedAt != null;
    }

    public String prefix() {
        int dash = key.lastIndexOf('-');
        return dash >= 0 ? key.substring(0, dash) : key;
    }

    public long number() {
        int dash = key.lastIndexOf('-');
        try {
            return Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "STRLPL-123 · Name" */
    public String title() {
        return key + " · " + name;
    }
}
