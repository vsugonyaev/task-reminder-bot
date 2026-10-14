package com.example.access;

/**
 * Actions restricted by member tags. Config key: access.&lt;key&gt; = comma-separated tags (empty — everyone).
 */
public enum Permission {
    /** Add artifacts, replace and rename links, change assignees (/artifact_add). */
    ARTIFACT_EDIT("artifact-edit"),
    /** Delete artifact links (/artifact_add → link → delete). */
    ARTIFACT_DELETE("artifact-delete"),
    /** Archive epics (/archivate). */
    EPIC_ARCHIVE("epic-archive");

    private final String key;

    Permission(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
