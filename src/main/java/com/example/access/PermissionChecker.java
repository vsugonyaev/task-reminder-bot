package com.example.access;

@FunctionalInterface
public interface PermissionChecker {

    boolean allowed(long chatId, long userId, Permission permission);
}
