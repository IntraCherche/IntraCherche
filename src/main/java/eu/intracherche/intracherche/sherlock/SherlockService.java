package eu.intracherche.intracherche.sherlock;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SherlockService {

    private static final Logger log = LoggerFactory.getLogger(SherlockService.class);

    private final SherlockQuestionDao dao;
    private final SherlockQueue queue;

    public SherlockService(SherlockQuestionDao dao, SherlockQueue queue) {
        this.dao = dao;
        this.queue = queue;
    }

    @PostConstruct
    public void reloadPending() {
        try {
            List<SherlockQuestion> pending = dao.loadQueued();
            if (pending.isEmpty()) return;
            log.info("Reloading {} pending Sherlock question(s) from database", pending.size());
            for (SherlockQuestion q : pending) {
                queue.publish(q);
            }
        } catch (Exception e) {
            log.error("Failed to reload pending Sherlock questions", e);
        }
    }

    /**
     * Queues a question.
     *
     * @param requestedIndices the cores to search: {@code null} inherits the parent's scope (every
     *                         index for a top-level question), an empty list means every index.
     *                         Names are only stored here; the worker intersects them with the live
     *                         cores before any reaches Solr.
     * @param documentId       the file to ask about; {@code null} inherits the parent's (none for a
     *                         top-level question). Like the indices, only stored here: the worker
     *                         searches it under the user's own access filter.
     * @throws IllegalArgumentException when {@code parentId} is not one of the user's own questions
     */
    public SherlockQuestion ask(String username, String userEmail, List<String> userSids, String question,
                                String parentId, List<String> requestedIndices,
                                String documentId, String documentName)
            throws SQLException, InterruptedException {
        List<String> indices = requestedIndices == null ? List.of() : normalizeIndices(requestedIndices);
        if (documentId != null && documentId.isBlank()) documentId = null;
        if (documentId == null) documentName = null;
        if (parentId != null) {
            // The worker feeds the parent's question and answer to the LLM as history, so a parent
            // must belong to the asker — otherwise any id would leak someone else's answer.
            SherlockQuestion parent = getQuestionById(parentId, username);
            if (parent == null) throw new IllegalArgumentException("Unknown parent question");
            if (requestedIndices == null) indices = parent.indices();
            // A follow-up keeps talking about the same document: that is the conversation.
            if (documentId == null) {
                documentId = parent.documentId();
                documentName = parent.documentName();
            }
        }
        SherlockQuestion q = new SherlockQuestion(
                UUID.randomUUID().toString(),
                username,
                userEmail != null ? userEmail : "",
                question,
                SherlockQuestionStatus.QUEUED,
                userSids,
                null,
                List.of(),
                System.currentTimeMillis(),
                null,
                parentId,
                indices,
                List.of(),
                documentId,
                documentName,
                Map.of(),
                null
        );
        dao.save(q);
        boolean accepted = queue.publish(q);
        if (!accepted) {
            dao.deleteById(q.id());
            throw new SherlockQueueFullException();
        }
        return q;
    }

    private static List<String> normalizeIndices(List<String> indices) {
        return indices.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public boolean deleteQuestion(String id, String username) throws SQLException {
        SherlockQuestion q = dao.findById(id);
        if (q == null || !q.username().equals(username)) return false;
        if (q.status() == SherlockQuestionStatus.QUEUED || q.status() == SherlockQuestionStatus.PROCESSING) return false;
        dao.deleteByParentId(id);
        return dao.deleteById(id);
    }

    public List<SherlockQuestion> getQuestionsForUser(String username, int limit, int offset) throws SQLException {
        return dao.findByUsername(username, limit, offset);
    }

    public SherlockQuestion getQuestionById(String id, String username) throws SQLException {
        SherlockQuestion q = dao.findById(id);
        if (q == null || !q.username().equals(username)) return null;
        return q;
    }
}
