package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SHERLOCK_QUESTIONS_TABLE;

public class SherlockQuestionDao {

    private static final Logger log = LoggerFactory.getLogger(SherlockQuestionDao.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SherlockCitation>> CITATION_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SherlockQuote>> QUOTE_LIST = new TypeReference<>() {};
    private static final TypeReference<java.util.LinkedHashMap<String, String>> STRING_MAP = new TypeReference<>() {};

    private final Connection conn;

    public SherlockQuestionDao(Connection conn) {
        this.conn = conn;
    }

    public void save(SherlockQuestion q) throws SQLException {
        String sql = """
                INSERT INTO %s (id, username, user_email, question, status, user_sids, answer, citations, created_at, answered_at, parent_id, indices, quotes, document_id, document_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(SHERLOCK_QUESTIONS_TABLE);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, q.id());
            ps.setString(2, q.username());
            ps.setString(3, q.userEmail());
            ps.setString(4, q.question());
            ps.setString(5, q.status().name());
            ps.setString(6, toJson(q.userSids()));
            ps.setString(7, q.answer());
            ps.setString(8, toJsonCitations(q.citations()));
            ps.setLong(9, q.createdAt());
            if (q.answeredAt() != null) ps.setLong(10, q.answeredAt());
            else ps.setNull(10, Types.INTEGER);
            ps.setString(11, q.parentId());
            ps.setString(12, toJson(q.indices()));
            ps.setString(13, q.quotes() == null || q.status() != SherlockQuestionStatus.ANSWERED ? null : toJsonQuotes(q.quotes()));
            ps.setString(14, q.documentId());
            ps.setString(15, q.documentName());
            ps.executeUpdate();
        }
        conn.commit();
    }

    public void updateStatusProcessing(String id) throws SQLException {
        update("UPDATE " + SHERLOCK_QUESTIONS_TABLE + " SET status = 'PROCESSING' WHERE id = ?", id);
    }

    public void updateAnswered(String id, String answer, List<SherlockCitation> citations,
                               List<SherlockQuote> quotes, long answeredAt) throws SQLException {
        String sql = "UPDATE " + SHERLOCK_QUESTIONS_TABLE +
                " SET status = 'ANSWERED', answer = ?, citations = ?, quotes = ?, answered_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, answer);
            ps.setString(2, toJsonCitations(citations));
            ps.setString(3, toJsonQuotes(quotes));
            ps.setLong(4, answeredAt);
            ps.setString(5, id);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /** The corrections applied to the question before it was searched; nothing is written when there are none. */
    public void updateSpelling(String id, Map<String, String> spelling) throws SQLException {
        if (spelling == null || spelling.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + SHERLOCK_QUESTIONS_TABLE + " SET spelling = ? WHERE id = ?")) {
            ps.setString(1, MAPPER.writeValueAsString(spelling));
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (JsonProcessingException e) {
            return;
        }
        conn.commit();
    }

    public void updateNotice(String id, String notice) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + SHERLOCK_QUESTIONS_TABLE + " SET notice = ? WHERE id = ?")) {
            ps.setString(1, notice);
            ps.setString(2, id);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public void updateFailed(String id) throws SQLException {
        update("UPDATE " + SHERLOCK_QUESTIONS_TABLE + " SET status = 'FAILED' WHERE id = ?", id);
    }

    public List<SherlockQuestion> findByUsername(String username, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM " + SHERLOCK_QUESTIONS_TABLE +
                " WHERE username = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResultSet(ps.executeQuery());
        }
    }

    public SherlockQuestion findById(String id) throws SQLException {
        String sql = "SELECT * FROM " + SHERLOCK_QUESTIONS_TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            List<SherlockQuestion> results = mapResultSet(ps.executeQuery());
            return results.isEmpty() ? null : results.get(0);
        }
    }

    public boolean deleteById(String id) throws SQLException {
        String sql = "DELETE FROM " + SHERLOCK_QUESTIONS_TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            int rows = ps.executeUpdate();
            conn.commit();
            return rows > 0;
        }
    }

    public void deleteByParentId(String parentId) throws SQLException {
        String sql = "DELETE FROM " + SHERLOCK_QUESTIONS_TABLE + " WHERE parent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, parentId);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public List<SherlockQuestion> loadQueued() throws SQLException {
        String sql = "SELECT * FROM " + SHERLOCK_QUESTIONS_TABLE +
                " WHERE status IN ('QUEUED', 'PROCESSING') ORDER BY created_at ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return mapResultSet(ps.executeQuery());
        }
    }

    /** The user's questions waiting in the queue or being answered. */
    public int countPending(String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + SHERLOCK_QUESTIONS_TABLE +
                " WHERE username = ? AND status IN ('QUEUED', 'PROCESSING')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void update(String sql, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private List<SherlockQuestion> mapResultSet(ResultSet rs) throws SQLException {
        List<SherlockQuestion> list = new ArrayList<>();
        while (rs.next()) {
            list.add(new SherlockQuestion(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("user_email"),
                    rs.getString("question"),
                    SherlockQuestionStatus.valueOf(rs.getString("status")),
                    fromJson(rs.getString("user_sids")),
                    rs.getString("answer"),
                    fromJsonCitations(rs.getString("citations")),
                    rs.getLong("created_at"),
                    rs.getLong("answered_at") == 0 ? null : rs.getLong("answered_at"),
                    rs.getString("parent_id"),
                    fromJson(rs.getString("indices")),
                    fromJsonQuotes(rs.getString("quotes")),
                    rs.getString("document_id"),
                    rs.getString("document_name"),
                    fromJsonMap(rs.getString("spelling")),
                    rs.getString("notice")
            ));
        }
        return list;
    }

    private String toJson(List<String> list) {
        if (list == null) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toJsonCitations(List<SherlockCitation> list) {
        if (list == null) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toJsonQuotes(List<SherlockQuote> list) {
        if (list == null) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * Null — not empty — for a question not answered yet, or answered before quotations were
     * checked: the pane must not present an unchecked answer as one that quotes nothing.
     */
    private List<SherlockQuote> fromJsonQuotes(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, QUOTE_LIST);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private List<SherlockCitation> fromJsonCitations(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, CITATION_LIST);
        } catch (JsonProcessingException e) {
            // Backward compat: old data was stored as a plain string array
            try {
                List<String> paths = MAPPER.readValue(json, STRING_LIST);
                return paths.stream()
                        .map(p -> {
                            String fname = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1)
                                         : p.contains("\\") ? p.substring(p.lastIndexOf('\\') + 1)
                                         : p;
                            return new SherlockCitation(null, p, fname, null);
                        })
                        .collect(java.util.stream.Collectors.toList());
            } catch (JsonProcessingException e2) {
                return Collections.emptyList();
            }
        }
    }
}
