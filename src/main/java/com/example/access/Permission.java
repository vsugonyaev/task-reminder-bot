package com.example.access;

/**
 * Actions restricted by member tags. Config key: access.&lt;key&gt; = comma-separated tags (empty — everyone).
 */
public enum Permission {
    /** Add artifacts and replace links (/artifact_add, /artifact_edit → replace). */
    ARTIFACT_EDIT("artifact-edit"),
    /** Delete artifact links (/artifact_edit → delete). */
    ARTIFACT_DELETE("artifact-delete"),
    /** Archive epics (/epic_archive). */
    EPIC_ARCHIVE("epic-archive");

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
