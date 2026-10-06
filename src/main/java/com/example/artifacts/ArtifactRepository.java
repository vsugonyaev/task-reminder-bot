package com.example.artifacts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite storage for epics and artifacts.
 *
 * Epics belong to the chat they were created in. Work data is never deleted: epics are archived,
 * artifacts are soft-deleted, and every change of an artifact is written to artifact_history.
 * The only hard delete is {@link #deleteChatData} for test chats.
 */
public class ArtifactRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRepository.class);

    private final Connection connection;

    private static final String EPICS_DDL = """
            CREATE TABLE %s (
                id          INTEGER PRIMARY KEY,
                chat_id     INTEGER NOT NULL,
                key         TEXT NOT NULL,
                name        TEXT NOT NULL,
                created_by  TEXT,
                created_at  TEXT NOT NULL,
                archived_by TEXT,
                archived_at TEXT,
                UNIQUE (chat_id, key)
            )""";

    /**
     * @param legacyChatId chat that owns epics created before epics were bound to chats
     */
    public ArtifactRepository(Path dbPath, long legacyChatId) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            try (Statement st = connection.createStatement()) {
                if (tableExists("epics") && !columnExists("epics", "chat_id")) {
                    migrateEpicsToChats(legacyChatId);
                }
                st.execute("PRAGMA foreign_keys = ON");
                if (!tableExists("epics")) {
                    st.execute(EPICS_DDL.formatted("epics"));
                }
                st.execute("""
                        CREATE TABLE IF NOT EXISTS artifacts (
                            id         INTEGER PRIMARY KEY,
                            epic_id    INTEGER NOT NULL REFERENCES epics(id),
                            type       TEXT NOT NULL,
                            url        TEXT NOT NULL,
                            created_by TEXT,
                            created_at TEXT NOT NULL,
                            updated_by TEXT,
                            updated_at TEXT,
                            deleted_by TEXT,
                            deleted_at TEXT
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS artifact_history (
                            id          INTEGER PRIMARY KEY,
                            artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
                            action      TEXT NOT NULL,
                            old_url     TEXT,
                            new_url     TEXT,
                            changed_by  TEXT,
                            changed_at  TEXT NOT NULL
                        )""");
            }
            log.info("Artifacts database: {}", dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open artifacts database " + dbPath.toAbsolutePath(), e);
        }
    }

    /** Rebuilds epics with chat_id and UNIQUE(chat_id, key); existing epics go to legacyChatId. */
    private void migrateEpicsToChats(long legacyChatId) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                st.execute(EPICS_DDL.formatted("epics_new"));
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO epics_new (id, chat_id, key, name, created_by, created_at, archived_by, archived_at)
                        SELECT id, ?, key, name, created_by, created_at, archived_by, archived_at FROM epics""")) {
                    ps.setLong(1, legacyChatId);
                    ps.executeUpdate();
                }
                st.execute("DROP TABLE epics");
                st.execute("ALTER TABLE epics_new RENAME TO epics");
                connection.commit();
                log.info("Migrated epics: bound existing epics to chat {}", legacyChatId);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // --- Epics (each chat has its own) ---

    private static final String EPIC_COLUMNS = "SELECT id, chat_id, key, name, archived_at FROM epics ";

    public synchronized List<Epic> epics(long chatId, boolean archived) {
        String sql = EPIC_COLUMNS + "WHERE chat_id = ? AND archived_at IS " + (archived ? "NOT NULL" : "NULL");
        List<Epic> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(epic(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load epics", e);
        }
        result.sort(Epic.BY_KEY);
        return result;
    }

    public synchronized Optional<Epic> epic(long chatId, long id) {
        return queryEpic(EPIC_COLUMNS + "WHERE chat_id = ? AND id = ?", ps -> {
            ps.setLong(1, chatId);
            ps.setLong(2, id);
        });
    }

    public synchronized Optional<Epic> epicByKey(long chatId, String key) {
        return queryEpic(EPIC_COLUMNS + "WHERE chat_id = ? AND key = ?", ps -> {
            ps.setLong(1, chatId);
            ps.setString(2, key);
        });
    }

    public synchronized Epic createEpic(long chatId, String key, String name, String user) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO epics (chat_id, key, name, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, chatId);
            ps.setString(2, key);
            ps.setString(3, name);
            ps.setString(4, user);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
            log.info("Epic created in chat {}: {} {} by {}", chatId, key, name, user);
            return new Epic(generatedId(ps), chatId, key, name, null);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create epic " + key, e);
        }
    }

    public record ChatDataStats(int epics, int artifacts) {}

    public synchronized ChatDataStats chatDataStats(long chatId) {
        return new ChatDataStats(
                count("SELECT COUNT(*) FROM epics WHERE chat_id = ?", chatId),
                count("SELECT COUNT(*) FROM artifacts WHERE epic_id IN (SELECT id FROM epics WHERE chat_id = ?)", chatId));
    }

    /**
     * Permanently deletes all epics, artifacts and history of the chat. Only for test chats —
     * work data is never deleted.
     */
    public synchronized ChatDataStats deleteChatData(long chatId) {
        ChatDataStats stats = chatDataStats(chatId);
        try {
            connection.setAutoCommit(false);
            update("DELETE FROM artifact_history WHERE artifact_id IN (SELECT a.id FROM artifacts a "
                    + "JOIN epics e ON e.id = a.epic_id WHERE e.chat_id = ?)", chatId);
            update("DELETE FROM artifacts WHERE epic_id IN (SELECT id FROM epics WHERE chat_id = ?)", chatId);
            update("DELETE FROM epics WHERE chat_id = ?", chatId);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // original error is more important
            }
            throw new IllegalStateException("Failed to delete data of chat " + chatId, e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                log.error("Failed to restore auto-commit", e);
            }
        }
        log.info("Deleted data of chat {}: {}", chatId, stats);
        return stats;
    }

    public synchronized void archiveEpic(long id, String user) {
        update("UPDATE epics SET archived_by = ?, archived_at = ? WHERE id = ? AND archived_at IS NULL",
                user, Instant.now().toString(), id);
        log.info("Epic {} archived by {}", id, user);
    }

    // --- Artifacts ---

    public synchronized List<Artifact> artifacts(long epicId) {
        List<Artifact> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, epic_id, type, url FROM artifacts WHERE epic_id = ? AND deleted_at IS NULL ORDER BY id")) {
            ps.setLong(1, epicId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(artifact(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load artifacts of epic " + epicId, e);
        }
        return result;
    }

    public synchronized Optional<Artifact> artifact(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, epic_id, type, url FROM artifacts WHERE id = ? AND deleted_at IS NULL")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(artifact(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load artifact " + id, e);
        }
    }

    public synchronized void addArtifact(long epicId, String type, String url, String user) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO artifacts (epic_id, type, url, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, epicId);
            ps.setString(2, type);
            ps.setString(3, url);
            ps.setString(4, user);
            ps.setString(5, now);
            ps.executeUpdate();
            history(generatedId(ps), "ADD", null, url, user, now);
            log.info("Artifact added: epic={}, type={} by {}", epicId, type, user);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add artifact", e);
        }
    }

    public synchronized void replaceUrl(long artifactId, String newUrl, String user) {
        Artifact old = artifact(artifactId).orElseThrow();
        String now = Instant.now().toString();
        update("UPDATE artifacts SET url = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                newUrl, user, now, artifactId);
        history(artifactId, "REPLACE", old.url(), newUrl, user, now);
        log.info("Artifact {} url replaced by {}", artifactId, user);
    }

    public synchronized void deleteArtifact(long artifactId, String user) {
        Artifact old = artifact(artifactId).orElseThrow();
        String now = Instant.now().toString();
        update("UPDATE artifacts SET deleted_by = ?, deleted_at = ? WHERE id = ?", user, now, artifactId);
        history(artifactId, "DELETE", old.url(), null, user, now);
        log.info("Artifact {} deleted by {}", artifactId, user);
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    // --- Helpers ---

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Epic> queryEpic(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(epic(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load epic", e);
        }
    }

    private void history(long artifactId, String action, String oldUrl, String newUrl, String user, String at) {
        update("INSERT INTO artifact_history (artifact_id, action, old_url, new_url, changed_by, changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", artifactId, action, oldUrl, newUrl, user, at);
    }

    private void update(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Database update failed: " + sql, e);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private int count(String sql, long param) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static Epic epic(ResultSet rs) throws SQLException {
        String archivedAt = rs.getString("archived_at");
        return new Epic(rs.getLong("id"), rs.getLong("chat_id"), rs.getString("key"), rs.getString("name"),
                archivedAt != null ? Instant.parse(archivedAt) : null);
    }

    private static Artifact artifact(ResultSet rs) throws SQLException {
        return new Artifact(rs.getLong("id"), rs.getLong("epic_id"), rs.getString("type"), rs.getString("url"));
    }
}
