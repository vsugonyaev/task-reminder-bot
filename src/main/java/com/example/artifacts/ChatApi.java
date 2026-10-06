package com.example.artifacts;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Duration;

/**
 * Telegram operations the artifact dialogs need. All texts are Telegram HTML, link previews are disabled.
 */
public interface ChatApi {

    /** Returns the id of the sent message. Keyboard may be null. */
    int send(long chatId, String html, InlineKeyboardMarkup keyboard);

    void edit(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard);

    void delete(long chatId, int messageId);

    void answerCallback(String callbackId, String text, boolean alert);

    void deleteLater(long chatId, int messageId, Duration after);
}
