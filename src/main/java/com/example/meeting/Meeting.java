package com.example.meeting;

import com.example.sprint.SprintSchedule;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A recurring team meeting bound to sprint days.
 */
public record Meeting(Type type, LocalTime time, String room) {

    public enum Type {
        /** First working day of a sprint. */
        PLANNING("planning", "🗓", "Планирование спринта"),
        /** Every working day except the first and the last day of a sprint. */
        DAILY("daily", "☀️", "Дейлик"),
        /** Last working day of a sprint. */
        REVIEW("review", "🎬", "Обзор спринта");

        private final String key;
        private final String emoji;
        private final String title;

        Type(String key, String emoji, String title) {
            this.key = key;
            this.emoji = emoji;
            this.title = title;
        }

        /** Config key part and /test argument, e.g. "daily". */
        public String key() {
            return key;
        }

        public String emoji() {
            return emoji;
        }

        public String title() {
            return title;
        }

        public boolean occursOn(SprintSchedule sprints, LocalDate date) {
            boolean lastDay = sprints.lastReminderDay(date).map(date::equals).orElse(false);
            return switch (this) {
                case PLANNING -> sprints.isPlanningDay(date);
                case REVIEW -> lastDay;
                case DAILY -> sprints.isReminderDay(date) && !lastDay;
            };
        }

        public static Type byKey(String key) {
            for (Type t : values()) {
                if (t.key.equalsIgnoreCase(key)) {
                    return t;
                }
            }
            return null;
        }
    }
}
