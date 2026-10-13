package com.example.artifacts;

/**
 * Link to a document of a given type (БТ, СТ, Макеты, ...) for an epic.
 * The title is what lists show ("СТ ВТБО", "Макеты ИБ Figma"); null — the type is shown instead.
 */
public record Artifact(long id, long epicId, String type, String title, String url) {

    public String displayTitle() {
        return title != null && !title.isBlank() ? title : type;
    }
}
