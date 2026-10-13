package com.example.artifacts;

/**
 * Telegram user who can be assigned to an epic: seen in the chat or an administrator.
 */
public record Person(long userId, String name, String username) {}
