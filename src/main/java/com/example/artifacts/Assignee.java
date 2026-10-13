package com.example.artifacts;

/**
 * Person assigned to an epic in a role (БА, СА, Дизайн). userId is null for a name typed by hand.
 */
public record Assignee(long id, long epicId, String role, Long userId, String name) {}
