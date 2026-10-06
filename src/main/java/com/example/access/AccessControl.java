package com.example.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Checks permissions by the member's status and tag in the chat:
 *  - owner and administrators — everything;
 *  - member without a tag — nothing (viewing commands are not restricted at all);
 *  - member with a tag — per rules; an empty rule allows any tagged member.
 * Tags are compared case-insensitively; member info is cached for a few minutes.
 */
public class AccessControl implements PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(AccessControl.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private record Member(boolean admin, String tag, Instant loadedAt) {}

    private final TelegramClient client;
    /** Permission -> allowed tags (lower case); missing or empty — allowed to everyone. */
    private final Map<Permission, Set<String>> rules;
    private final Map<String, Member> cache = new ConcurrentHashMap<>();

    public AccessControl(TelegramClient client, Map<Permission, Set<String>> rules) {
        this.client = client;
        this.rules = rules.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().stream().map(AccessControl::normalize).collect(Collectors.toSet())));
    }

    @Override
    public boolean allowed(long chatId, long userId, Permission permission) {
        Member member = member(chatId, userId);
        if (member == null) {
            return false;
        }
        boolean ok = decide(member.admin(), member.tag(), rules.getOrDefault(permission, Set.of()));
        if (!ok) {
            log.info("Access denied: user {} (tag '{}') has no {} in chat {}", userId, member.tag(), permission, chatId);
        }
        return ok;
    }

    /** Warns if regular members may change their own tag — then anyone could grant themselves access. */
    public void checkChatSettings(long chatId) {
        try {
            ChatPermissions permissions = client.execute(GetChat.builder().chatId(chatId).build()).getPermissions();
            if (permissions != null && Boolean.TRUE.equals(permissions.getCanEditTag())) {
                log.warn("Members of chat {} can edit their own tags — anyone can grant themselves access. "
                        + "Disable it in group permissions.", chatId);
            }
        } catch (TelegramApiException e) {
            log.warn("Failed to check chat {} permissions: {}", chatId, e.getMessage());
        }
    }

    private Member member(long chatId, long userId) {
        String key = chatId + ":" + userId;
        Member cached = cache.get(key);
        if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        try {
            ChatMember m = client.execute(GetChatMember.builder().chatId(chatId).userId(userId).build());
            Member member = switch (m) {
                case ChatMemberOwner o -> new Member(true, o.getCustomTitle(), Instant.now());
                case ChatMemberAdministrator a -> new Member(true, a.getCustomTitle(), Instant.now());
                case ChatMemberMember mm -> new Member(false, mm.getTag(), Instant.now());
                case ChatMemberRestricted r -> new Member(false, r.getTag(), Instant.now());
                default -> new Member(false, null, Instant.now());
            };
            cache.put(key, member);
            return member;
        } catch (TelegramApiException e) {
            log.warn("Failed to get member {} of chat {}: {}", userId, chatId, e.getMessage());
            return cached;
        }
    }

    /** Admins — always; no tag — never; otherwise the tag must be in the rule (empty rule — any tag). */
    static boolean decide(boolean admin, String tag, Set<String> allowedTags) {
        if (admin) {
            return true;
        }
        if (tag == null || tag.isBlank()) {
            return false;
        }
        return allowedTags.isEmpty() || allowedTags.contains(normalize(tag));
    }

    private static String normalize(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT);
    }
}
