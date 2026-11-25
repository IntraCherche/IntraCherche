package eu.intracherche.intracherche.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_MEMBERSHIPS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_REGISTRY_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SHERLOCK_QUESTIONS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.NOTIFICATIONS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SEARCH_SUBSCRIPTIONS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.INDEXING_ERRORS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.LAST_ROUND_INDEXES_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.LAST_ROUND_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.REVOKED_SESSIONS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SHERLOCK_USAGE_TABLE;

/**
 * Creates tables & indexes
 * Please note :
 * indexed_at can be null . we use this default value ( not 0) to distinguish it from real timestamp and
 * mean that it has never been indexed.
 *  => But caution never use getObject(..., Long.class) with SQLite for nullable columns (because of SQLite JDBC quirk).
 *
 *  The following fields will be stored as JSON array since SQlite supports them like `index_membership_in_solr = ["index_1", "index_2"]`
 *  indexMembershipSolr corresponds to the SOLR indexes this file has been indexed into.
 *  indexMembershipActual corresponds to the actual indexes this file belongs to based on the yaml index config.
 *  
 *  These fields help track the relationship between files and indexes:
 *  - indexMembershipActual: What indexes the file SHOULD be in (based on path configuration)
 *  - indexMembershipSolr: What indexes the file HAS BEEN indexed into (based on actual indexing operations)
 */
public class RegistrySchema {
    private static final Logger log = LoggerFactory.getLogger(RegistrySchema.class);

    public static void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
        CREATE TABLE IF NOT EXISTS %s(
            id TEXT PRIMARY KEY,
            path TEXT,
            mtime INTEGER NOT NULL,
            size INTEGER NOT NULL,
            content_hash TEXT,
            last_scan_id INTEGER NOT NULL,
            indexed_at INTEGER,
            state TEXT DEFAULT 'FAILED',
            last_seen INTEGER,
            is_smb INTEGER DEFAULT 0,
            smb_share_name TEXT,
            smb_host TEXT,
            index_membership_solr TEXT,
            last_access_tokens_check INTEGER,
            access_tokens TEXT,
            deny_tokens TEXT,
            access_tokens_hash  TEXT
        )
            """.formatted(FILE_REGISTRY_TABLE));

            // Create the new file_memberships table with foreign key and ON DELETE CASCADE
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    file_id TEXT,
                    index_name TEXT,
                    PRIMARY KEY (file_id, index_name),
                    FOREIGN KEY (file_id) REFERENCES %s(id) ON DELETE CASCADE
                )
                """.formatted(FILE_MEMBERSHIPS_TABLE, FILE_REGISTRY_TABLE));

            st.execute("CREATE INDEX IF NOT EXISTS idx_scan ON " + FILE_REGISTRY_TABLE + "(last_scan_id)");
            // We add a composite index (an index on 2 columns at once) to ease cleanup queries
            st.execute("CREATE INDEX IF NOT EXISTS idx_share_cleanup ON " +
                    FILE_REGISTRY_TABLE + "(smb_share_name, last_seen)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_file_memberships_file_id ON " + FILE_MEMBERSHIPS_TABLE + "(file_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_file_memberships_index_name ON " + FILE_MEMBERSHIPS_TABLE + "(index_name)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id               TEXT PRIMARY KEY,
                    username         TEXT NOT NULL,
                    user_email       TEXT NOT NULL,
                    query            TEXT NOT NULL,
                    indices          TEXT NOT NULL,
                    filename         TEXT,
                    geo_city         TEXT,
                    geo_dpt          TEXT,
                    geo_region       TEXT,
                    geo_country      TEXT,
                    date_offset_from INTEGER,
                    date_offset_to   INTEGER,
                    sort             TEXT DEFAULT 'score',
                    frequency        TEXT NOT NULL DEFAULT 'IMMEDIATE',
                    last_result_hash  TEXT,
                    last_result_count INTEGER,
                    last_checked_at   INTEGER,
                    created_at        INTEGER NOT NULL,
                    is_active         INTEGER NOT NULL DEFAULT 1
                )
                """.formatted(SEARCH_SUBSCRIPTIONS_TABLE));

            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id               TEXT PRIMARY KEY,
                    username         TEXT NOT NULL,
                    subscription_id  TEXT NOT NULL,
                    message          TEXT NOT NULL,
                    query_summary    TEXT,
                    created_at       INTEGER NOT NULL,
                    is_read          INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (subscription_id) REFERENCES %s(id) ON DELETE CASCADE
                )
                """.formatted(NOTIFICATIONS_TABLE, SEARCH_SUBSCRIPTIONS_TABLE));

            st.execute("CREATE INDEX IF NOT EXISTS idx_subscriptions_username ON " + SEARCH_SUBSCRIPTIONS_TABLE + "(username)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_subscriptions_frequency ON " + SEARCH_SUBSCRIPTIONS_TABLE + "(frequency, is_active)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_notifications_username_read ON " + NOTIFICATIONS_TABLE + "(username, is_read)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_notifications_subscription ON " + NOTIFICATIONS_TABLE + "(subscription_id)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id          TEXT PRIMARY KEY,
                    username    TEXT NOT NULL,
                    user_email  TEXT NOT NULL,
                    question    TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'QUEUED',
                    user_sids   TEXT NOT NULL,
                    answer      TEXT,
                    citations   TEXT,
                    created_at  INTEGER NOT NULL,
                    answered_at INTEGER
                )
                """.formatted(SHERLOCK_QUESTIONS_TABLE));

            st.execute("CREATE INDEX IF NOT EXISTS idx_sherlock_username ON " + SHERLOCK_QUESTIONS_TABLE + "(username)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    occurred_at INTEGER NOT NULL,
                    kind        TEXT    NOT NULL,
                    core_name   TEXT    NOT NULL,
                    path        TEXT    NOT NULL,
                    message     TEXT    NOT NULL
                )
                """.formatted(INDEXING_ERRORS_TABLE));
            st.execute("CREATE INDEX IF NOT EXISTS idx_indexing_errors_occurred_at ON " + INDEXING_ERRORS_TABLE + "(occurred_at DESC)");

            // Sessions ended before their token expires (logout, account disabled…): one row per
            // token id, kept until the token would have expired anyway.
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    jti         TEXT    PRIMARY KEY,
                    username    TEXT    NOT NULL,
                    expires_at  INTEGER NOT NULL,
                    reason      TEXT    NOT NULL
                )
                """.formatted(REVOKED_SESSIONS_TABLE));

            // Sherlock questions asked per user and per day, against the quota. Kept apart from
            // the questions themselves: deleting a question must not give it back.
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    username  TEXT    NOT NULL,
                    day       TEXT    NOT NULL,
                    count     INTEGER NOT NULL,
                    tier      TEXT    NOT NULL,
                    PRIMARY KEY (username, day)
                )
                """.formatted(SHERLOCK_USAGE_TABLE));

            // The last indexing round that had files to process — one row, replaced at the
            // end of each such round, so the admin panel still shows it after a restart.
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id            INTEGER PRIMARY KEY CHECK (id = 1),
                    completed_at  INTEGER NOT NULL,
                    duration_ms   INTEGER NOT NULL,
                    files         INTEGER NOT NULL,
                    text_pages    INTEGER NOT NULL,
                    ocr_pages     INTEGER NOT NULL,
                    ocr_active_ms INTEGER NOT NULL
                )
                """.formatted(LAST_ROUND_TABLE));
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    core_name  TEXT    PRIMARY KEY,
                    succeeded  INTEGER NOT NULL,
                    failed     INTEGER NOT NULL,
                    skipped    INTEGER NOT NULL,
                    text_pages    INTEGER NOT NULL,
                    ocr_pages     INTEGER NOT NULL,
                    duration_ms   INTEGER NOT NULL,
                    ocr_active_ms INTEGER NOT NULL
                )
                """.formatted(LAST_ROUND_INDEXES_TABLE));
        }
        // Migrate old table name if needed
        if (tableExists(conn, "librarian_questions") && !tableExists(conn, SHERLOCK_QUESTIONS_TABLE)) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE librarian_questions RENAME TO " + SHERLOCK_QUESTIONS_TABLE);
                log.info("Migrated librarian_questions → {}", SHERLOCK_QUESTIONS_TABLE);
            }
        }
        // Which limit cut a file's content short ("size:<bytes>" or "chars:<chars>"), or NULL.
        // Kept so that raising the limit queues exactly those files again (see requeueLimitedFiles).
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "content_limit")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN content_limit TEXT");
            }
        }
        // "<mtime>:<size>" of the file as it was when its content could not be parsed, or NULL. While the
        // file still has this mtime and size it is not queued again (see findFilesToIndex).
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "parse_failed_sig")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN parse_failed_sig TEXT");
            }
        }
        // Crash tracking. attempt_started_at is set while a file is being parsed and cleared when that ends:
        // a value found at startup means the JVM died on it (or on a file parsed beside it). crash_count
        // counts those deaths for the version of the file identified by crash_sig ("<mtime>:<size>").
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "attempt_started_at")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN attempt_started_at INTEGER");
            }
        }
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "crash_count")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN crash_count INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "crash_sig")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN crash_sig TEXT");
            }
        }
        if (!columnExists(conn, SEARCH_SUBSCRIPTIONS_TABLE, "last_result_count")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SEARCH_SUBSCRIPTIONS_TABLE + " ADD COLUMN last_result_count INTEGER");
            }
        }
        if (!columnExists(conn, NOTIFICATIONS_TABLE, "notification_type")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + NOTIFICATIONS_TABLE +
                        " ADD COLUMN notification_type TEXT NOT NULL DEFAULT 'SEARCH_ALERT'");
            }
        }
        // Migrate old notification type value if needed (must run after column is guaranteed to exist)
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + NOTIFICATIONS_TABLE + " SET notification_type = 'SHERLOCK_ANSWER' WHERE notification_type = 'LIBRARIAN_ANSWER'")) {
            int updated = ps.executeUpdate();
            if (updated > 0) log.info("Migrated {} LIBRARIAN_ANSWER notifications → SHERLOCK_ANSWER", updated);
        }
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "parent_id")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN parent_id TEXT");
            }
        }
        // JSON array of core names; NULL (questions asked before scoping existed) reads as every index
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "indices")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN indices TEXT");
            }
        }
        // JSON array of SherlockQuote; NULL (answers written before quotations were checked) reads as none
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "quotes")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN quotes TEXT");
            }
        }
        // The file a question is about (Solr parent_id) and its name; NULL for the whole scope
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "document_id")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN document_id TEXT");
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN document_name TEXT");
            }
        }
        // JSON object, typed word to correction; NULL when nothing was corrected
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "spelling")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN spelling TEXT");
            }
        }
        // A sentence for the user about how the question was read; NULL when there is nothing to say
        if (!columnExists(conn, SHERLOCK_QUESTIONS_TABLE, "notice")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + SHERLOCK_QUESTIONS_TABLE + " ADD COLUMN notice TEXT");
            }
        }
        // JSON array of SIDs with an explicit Deny ACE (Windows/SMB only). NULL (rows written
        // before deny ACEs were tracked) reads as empty — nothing denied — until the next scan
        // recomputes the file's ACL and fills it in; a full reindex is needed to close the gap
        // for files whose ACL hasn't changed since (see access_tokens_hash, which already
        // reflects deny ACEs and so won't by itself flag these rows for a rescan).
        if (!columnExists(conn, FILE_REGISTRY_TABLE, "deny_tokens")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " ADD COLUMN deny_tokens TEXT");
            }
        }
        conn.commit();
    }

    static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            return ps.executeQuery().next();
        }
    }
}
