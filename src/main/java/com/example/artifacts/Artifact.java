package com.example.artifacts;

/**
 * Link to a document of a given type (SA, BA, ...) for an epic.
 */
public record Artifact(long id, long epicId, String type, String url) {}
