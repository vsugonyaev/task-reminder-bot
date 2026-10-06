package com.example.config;

import com.example.meeting.Meeting;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Application settings.
 *
 * Sources, from highest priority to lowest:
 *  1. environment variables TG_BOT_TOKEN, TG_CHAT_ID (secrets are better kept out of files);
 *  2. properties file (UTF-8): path from the CONFIG_FILE env var, otherwise ./config.properties;
 *  3. defaults below.
 *
 * See config.example.properties for all keys.
 */
public record AppConfig(
        String botToken,
        long chatId,
        ZoneId zone,
        LocalTime reminderTime,
        LocalTime sprintStartTime,
        LocalTime unpinTime,
        LocalDate sprintAnchor,
        int sprintLengthDays,
        String scrumMaster,
        Duration testPinDuration,
        String meetingTeam,
        Duration meetingRemindBefore,
        Duration meetingDeleteAfter,
        /** Enabled meetings only (those with a room set). */
        Map<Meeting.Type, Meeting> meetings,
        Path source
) {

    private static final String DEFAULT_FILE = "config.properties";

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("timezone", "Europe/Moscow"),
            Map.entry("reminder.time", "16:30"),
            Map.entry("sprint-start.time", "10:00"),
            Map.entry("unpin.time", "23:59"),
            Map.entry("sprint.anchor", "2026-10-07"),
            Map.entry("sprint.length-days", "14"),
            Map.entry("scrum-master", "@vsugonyaev"),
            Map.entry("test.pin-minutes", "5"),
            Map.entry("meeting.team", "СУБО2_1-STRLPL"),
            Map.entry("meeting.remind-before-minutes", "5"),
            Map.entry("meeting.delete-after-minutes", "5"),
            Map.entry("meeting.planning.time", "10:00"),
            Map.entry("meeting.planning.room", "https://dion.vc/event/mestnikovat"),
            Map.entry("meeting.daily.time", "10:00"),
            Map.entry("meeting.daily.room", "https://dion.vc/event/mestnikovat"),
            Map.entry("meeting.review.time", "10:00"),
            Map.entry("meeting.review.room", "https://dion.vc/event/ptohov")
    );

    private static final Map<String, String> ENV_OVERRIDES = Map.of(
            "telegram.token", "TG_BOT_TOKEN",
            "telegram.chat-id", "TG_CHAT_ID"
    );

    public static AppConfig load() {
        String customPath = System.getenv("CONFIG_FILE");
        Path path = Path.of(customPath != null && !customPath.isBlank() ? customPath : DEFAULT_FILE);

        Properties props = new Properties();
        DEFAULTS.forEach(props::setProperty);

        boolean fileFound = Files.isRegularFile(path);
        if (fileFound) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read config " + path.toAbsolutePath(), e);
            }
        } else if (customPath != null && !customPath.isBlank()) {
            throw new IllegalStateException("Config file not found: " + path.toAbsolutePath());
        }

        ENV_OVERRIDES.forEach((key, env) -> {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                props.setProperty(key, value);
            }
        });

        return new AppConfig(
                required(props, "telegram.token", Function.identity()),
                required(props, "telegram.chat-id", Long::parseLong),
                required(props, "timezone", ZoneId::of),
                required(props, "reminder.time", LocalTime::parse),
                required(props, "sprint-start.time", LocalTime::parse),
                required(props, "unpin.time", LocalTime::parse),
                required(props, "sprint.anchor", LocalDate::parse),
                required(props, "sprint.length-days", AppConfig::positiveInt),
                required(props, "scrum-master", Function.identity()),
                required(props, "test.pin-minutes", v -> Duration.ofMinutes(positiveInt(v))),
                required(props, "meeting.team", Function.identity()),
                required(props, "meeting.remind-before-minutes", v -> Duration.ofMinutes(positiveInt(v))),
                required(props, "meeting.delete-after-minutes", v -> Duration.ofMinutes(positiveInt(v))),
                meetings(props),
                fileFound ? path.toAbsolutePath() : null
        );
    }

    /** meeting.&lt;type&gt;.room / meeting.&lt;type&gt;.time; an empty room disables the meeting. */
    private static Map<Meeting.Type, Meeting> meetings(Properties props) {
        Map<Meeting.Type, Meeting> result = new EnumMap<>(Meeting.Type.class);
        for (Meeting.Type type : Meeting.Type.values()) {
            String prefix = "meeting." + type.key() + ".";
            String room = props.getProperty(prefix + "room", "").trim();
            if (room.isEmpty()) {
                continue;
            }
            if (!room.startsWith("https://") && !room.startsWith("http://")) {
                throw new IllegalStateException("Invalid value for '" + prefix + "room': must be a link, got " + room);
            }
            result.put(type, new Meeting(type, required(props, prefix + "time", LocalTime::parse), room));
        }
        return Collections.unmodifiableMap(result);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** How long a message sent now should stay pinned (until unpin.time today). */
    public Duration untilUnpinTime() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        Duration d = Duration.between(now, now.with(unpinTime));
        return d.isNegative() ? Duration.ZERO : d;
    }

    /** Short label for messages, e.g. "МСК" for Europe/Moscow. */
    public String zoneLabel() {
        return zone.getId().equals("Europe/Moscow") ? "МСК" : zone.getId();
    }

    private static <T> T required(Properties props, String key, Function<String, T> parser) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            String env = ENV_OVERRIDES.get(key);
            throw new IllegalStateException("Missing setting '" + key + "'"
                    + (env != null ? " (set it in config file or env var " + env + ")" : ""));
        }
        try {
            return parser.apply(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid value for '" + key + "': " + value, e);
        }
    }

    private static int positiveInt(String value) {
        int n = Integer.parseInt(value);
        if (n <= 0) {
            throw new IllegalArgumentException("must be positive");
        }
        return n;
    }

    @Override
    public String toString() {
        // Never log the token
        return "AppConfig[source=" + (source != null ? source : "defaults + env")
                + ", chatId=" + chatId + ", zone=" + zone
                + ", sprintStartTime=" + sprintStartTime + ", reminderTime=" + reminderTime
                + ", unpinTime=" + unpinTime + ", sprintAnchor=" + sprintAnchor
                + ", sprintLengthDays=" + sprintLengthDays + ", scrumMaster=" + scrumMaster
                + ", testPinDuration=" + testPinDuration
                + ", meetingTeam=" + meetingTeam + ", meetingRemindBefore=" + meetingRemindBefore
                + ", meetingDeleteAfter=" + meetingDeleteAfter + ", meetings=" + meetings.values() + "]";
    }
}
