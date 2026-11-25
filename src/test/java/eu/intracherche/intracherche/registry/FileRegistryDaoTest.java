package eu.intracherche.intracherche.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_MEMBERSHIPS_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_REGISTRY_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

class FileRegistryDaoTest {

    private Connection conn;
    private FileRegistryDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        dao = new FileRegistryDao(conn);
    }

    @Test
    void markFailed_sets_state_without_touching_indexed_at_and_preserves_access_tokens_hash() throws SQLException {
        insertFile("file-1", "/docs/report.pdf");

        dao.markFailed("file-1");

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, indexed_at, access_tokens_hash FROM " + FILE_REGISTRY_TABLE + " WHERE id = ?")) {
            ps.setString(1, "file-1");
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("state")).isEqualTo("FAILED");
            // indexed_at means "this file is in Solr". A failed file is not, so it must stay unset.
            assertThat(rs.getObject("indexed_at")).isNull();
            // access_tokens_hash is set during discovery (before parsing); markFailed must not wipe it
            assertThat(rs.getString("access_tokens_hash")).isEqualTo("hash-placeholder");
        }
    }

    @Test
    void markFailed_file_is_still_retried_next_cycle() throws SQLException {
        insertFile("file-2", "/docs/corrupt.pdf");

        assertThat(dao.findFilesToIndex()).hasSize(1);

        dao.markFailed("file-2");

        // Regression: markFailed used to set indexed_at, which dropped the file from this
        // query for good. A share that went down mid-cycle silently lost all its files.
        assertThat(dao.findFilesToIndex()).hasSize(1);
    }

    @Test
    void markFailed_does_not_affect_other_files() throws SQLException {
        insertFile("file-3", "/docs/good.pdf");
        insertFile("file-4", "/docs/bad.pdf");

        dao.markFailed("file-4");

        assertThat(stateOf("file-3")).isEqualTo("DISCOVERED");
        assertThat(stateOf("file-4")).isEqualTo("FAILED");
    }

    @Test
    void raising_a_limit_queues_again_exactly_the_files_it_had_cut_short() throws SQLException {
        insertFile("big", "/docs/scan.pdf");
        insertFile("long", "/docs/registre.ods");
        insertFile("normal", "/docs/note.pdf");
        for (String id : new String[]{"big", "long", "normal"}) dao.markIndexed(id);
        dao.setContentLimit("big", "size:268435456");      // 256 MB
        dao.setContentLimit("long", "chars:20000000");

        // Same limits: nothing to do.
        assertThat(dao.requeueLimitedFiles(268435456L, 20000000L)).isZero();
        assertThat(dao.findFilesToIndex()).isEmpty();

        // maxFileSizeMb raised to 512: only the file indexed by name comes back, content_limit cleared.
        assertThat(dao.requeueLimitedFiles(536870912L, 20000000L)).isEqualTo(1);
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("big");

        // maxTextChars raised: the file cut at the old text limit comes back.
        assertThat(dao.requeueLimitedFiles(536870912L, 50000000L)).isEqualTo(1);
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactlyInAnyOrder("big", "long");
    }

    @Test
    void countOrphanFiles_counts_only_rows_without_any_membership() throws SQLException {
        insertFile("member", "/docs/kept.pdf");
        insertMembership("member", "Index_Archives");
        insertFile("orphan", "/docs/dropped.pdf");

        assertThat(dao.countOrphanFiles()).isEqualTo(1);
    }

    @Test
    void deleteOrphanFiles_removes_rows_without_membership_and_keeps_the_others() throws SQLException {
        insertFile("member", "/docs/kept.pdf");
        insertMembership("member", "Index_Archives");
        insertFile("orphan-1", "/docs/dropped-1.pdf");
        insertFile("orphan-2", "/docs/dropped-2.pdf");

        assertThat(dao.deleteOrphanFiles()).isEqualTo(2);

        assertThat(dao.countOrphanFiles()).isZero();
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("member");
    }

    @Test
    void deleteOrphanFiles_keeps_files_of_an_index_whose_share_is_unreachable() throws SQLException {
        // A missed scan writes nothing, so an offline share's files keep their membership.
        // They must survive the purge — that is what the stalled-file threshold is for.
        insertFile("offline", "/docs/on-a-share-that-is-down.pdf");
        insertMembership("offline", "Index_Demo_Wikipedia");

        assertThat(dao.deleteOrphanFiles()).isZero();
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("offline");
    }

    @Test
    void requeueFailedFiles_clears_indexed_at_left_by_the_old_markFailed() throws SQLException {
        // A file the pre-3.1.0 markFailed() touched: FAILED *and* indexed_at set, which hid
        // it from findFilesToIndex() for good.
        insertFile("dropped", "/docs/lost-during-an-outage.pdf");
        setStateAndIndexedAt("dropped", "FAILED", 5_000L);
        assertThat(dao.findFilesToIndex()).isEmpty();

        assertThat(dao.requeueFailedFiles()).isEqualTo(1);

        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("dropped");
    }

    @Test
    void requeueFailedFiles_leaves_successfully_indexed_files_alone() throws SQLException {
        // markIndexed() sets indexed_at and leaves the state as it is, so an indexed file is
        // never FAILED and must not be queued again.
        insertFile("indexed", "/docs/fine.pdf");
        dao.markIndexed("indexed");

        assertThat(dao.requeueFailedFiles()).isZero();

        assertThat(dao.findFilesToIndex()).isEmpty();
    }

    @Test
    void requeueFailedFiles_is_a_no_op_on_files_failed_by_the_current_code() throws SQLException {
        // The fixed markFailed() leaves indexed_at NULL: the file is already pending, so
        // there is nothing to queue again and the count must not include it.
        insertFile("failed-now", "/docs/corrupt.pdf");
        dao.markFailed("failed-now");

        assertThat(dao.requeueFailedFiles()).isZero();

        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("failed-now");
    }

    @Test
    void unparsable_file_is_not_queued_again_until_its_size_or_mtime_changes() throws SQLException {
        insertFile("zero", "/docs/empty.pdf");
        insertFile("ok", "/docs/good.pdf");

        dao.markParseFailed("zero");

        assertThat(stateOf("zero")).isEqualTo("FAILED");
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("ok");
        assertThat(dao.countFilesToIndex()).isEqualTo(1);

        // The scan finds the file again, unchanged: still left alone.
        run("UPDATE " + FILE_REGISTRY_TABLE + " SET state = 'DISCOVERED', last_seen = 2000 WHERE id = 'zero'");
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("ok");

        // Its modification date changes: tried again.
        run("UPDATE " + FILE_REGISTRY_TABLE + " SET mtime = 5000 WHERE id = 'zero'");
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactlyInAnyOrder("ok", "zero");

        // Same for a change of size alone.
        dao.markParseFailed("zero");
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("ok");
        run("UPDATE " + FILE_REGISTRY_TABLE + " SET size = 7 WHERE id = 'zero'");
        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactlyInAnyOrder("ok", "zero");
    }

    @Test
    void a_transient_failure_is_still_retried_but_a_parse_failure_is_not() throws SQLException {
        insertFile("transient", "/docs/a.pdf");
        insertFile("corrupt", "/docs/b.ppt");

        dao.markFailed("transient");
        dao.markParseFailed("corrupt");

        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("transient");
    }

    @Test
    void reindexing_everything_forgets_parse_failures() throws SQLException {
        insertFile("corrupt", "/docs/b.ppt");
        dao.markParseFailed("corrupt");
        assertThat(dao.findFilesToIndex()).isEmpty();

        run("UPDATE " + FILE_REGISTRY_TABLE + " SET indexed_at = NULL, parse_failed_sig = NULL");

        assertThat(dao.findFilesToIndex()).hasSize(1);
    }

    @Test
    void a_registry_created_before_parse_failures_were_remembered_gets_the_column() throws SQLException {
        try (Connection old = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            old.setAutoCommit(false);
            RegistrySchema.ensureSchema(old);
            try (Statement st = old.createStatement()) {
                st.execute("ALTER TABLE " + FILE_REGISTRY_TABLE + " DROP COLUMN parse_failed_sig");
            }
            old.commit();

            RegistrySchema.ensureSchema(old);

            try (Statement st = old.createStatement();
                 ResultSet rs = st.executeQuery("SELECT parse_failed_sig FROM " + FILE_REGISTRY_TABLE)) {
                assertThat(rs.next()).isFalse();
            }
        }
    }

    // ── Files that crash the application ──────────────────────────────────────

    @Test
    void a_file_still_marked_at_startup_gets_a_crash_counted_and_its_marker_cleared() throws SQLException {
        insertFile("guilty", "/docs/huge.xlsx");
        insertFile("fine", "/docs/small.pdf");
        dao.markAttemptStarted("guilty");
        dao.markAttemptStarted("fine");
        dao.clearAttempt("fine");     // parsing ended normally

        assertThat(dao.recoverInterruptedAttempts()).containsExactly("guilty");

        assertThat(dao.crashCount("guilty")).isEqualTo(1);
        assertThat(dao.crashCount("fine")).isZero();
        assertThat(dao.crashSuspectIds()).containsExactly("guilty");
        // The marker is gone: a second start does not count the same crash twice.
        assertThat(dao.recoverInterruptedAttempts()).isEmpty();
    }

    @Test
    void a_clean_shutdown_counts_no_crash() throws SQLException {
        insertFile("a", "/docs/a.pdf");
        dao.markAttemptStarted("a");

        dao.clearAllAttempts();

        assertThat(dao.recoverInterruptedAttempts()).isEmpty();
        assertThat(dao.crashCount("a")).isZero();
    }

    @Test
    void crashes_add_up_for_one_version_of_a_file_and_start_again_when_the_file_changes() throws SQLException {
        insertFile("a", "/docs/a.xlsx");
        dao.recordCrash("a");
        dao.recordCrash("a");
        assertThat(dao.crashCount("a")).isEqualTo(2);

        // Someone replaced the file: it deserves a fresh chance.
        run("UPDATE " + FILE_REGISTRY_TABLE + " SET mtime = 2000 WHERE id = 'a'");
        assertThat(dao.crashCount("a")).isZero();
        assertThat(dao.crashSuspectIds()).isEmpty();
        dao.recordCrash("a");
        assertThat(dao.crashCount("a")).isEqualTo(1);
    }

    @Test
    void a_file_that_crashed_stays_a_suspect_after_it_passes_alone_so_it_keeps_being_parsed_alone() throws SQLException {
        insertFile("a", "/docs/a.pdf");
        dao.recordCrash("a");

        dao.markIndexed("a");

        assertThat(dao.crashCount("a")).isEqualTo(1);
        assertThat(dao.crashSuspectIds()).containsExactly("a");
    }

    @Test
    void files_set_aside_for_lack_of_memory_are_queued_again_only_when_the_heap_has_grown() throws SQLException {
        insertFile("crashed", "/docs/crashed.xlsx");
        insertFile("big", "/docs/big.pdf");
        setStateAndIndexedAt("crashed", "INDEXED", 5000);
        setStateAndIndexedAt("big", "INDEXED", 5000);
        dao.setContentLimit("crashed", "crash:3995");   // set aside under a 3995 MB heap
        dao.setContentLimit("big", "size:1000");
        dao.recordCrash("crashed");

        // Same heap, or a smaller one: nothing to try again.
        assertThat(dao.requeueCrashLimitedFiles(3995)).isZero();
        // Raised to 7 GB: the file is read again. The file limited by its size is left alone.
        assertThat(dao.requeueCrashLimitedFiles(7168)).isEqualTo(1);

        assertThat(dao.findFilesToIndex()).extracting(FileRecord::id).containsExactly("crashed");
        assertThat(dao.crashCount("crashed")).isZero();
    }

    private void run(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
        conn.commit();
    }

    private void setStateAndIndexedAt(String id, String state, long indexedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET state = ?, indexed_at = ? WHERE id = ?")) {
            ps.setString(1, state);
            ps.setLong(2, indexedAt);
            ps.setString(3, id);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private String stateOf(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state FROM " + FILE_REGISTRY_TABLE + " WHERE id = ?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("state") : null;
        }
    }

    private void insertFile(String id, String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO %s (id, path, mtime, size, last_scan_id, state, last_seen, is_smb, access_tokens_hash)
                VALUES (?, ?, 1000, 100, 1, 'DISCOVERED', 1000, 0, 'hash-placeholder')
                """.formatted(FILE_REGISTRY_TABLE))) {
            ps.setString(1, id);
            ps.setString(2, path);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private void insertMembership(String fileId, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + FILE_MEMBERSHIPS_TABLE + " (file_id, index_name) VALUES (?, ?)")) {
            ps.setString(1, fileId);
            ps.setString(2, indexName);
            ps.executeUpdate();
        }
        conn.commit();
    }
}
