package com.example.util;

/**
 * Helpers for Telegram HTML parse mode.
 */
public final class Html {

    private Html() {}

    public static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String link(String url, String text) {
        return "<a href=\"" + escape(url) + "\">" + escape(text) + "</a>";
    }
}
