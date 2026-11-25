package eu.intracherche.intracherche.sherlock;

import java.util.List;
import java.util.Map;

public record SherlockQuestionResponse(
        String id,
        String question,
        String status,
        String answer,
        List<SherlockCitation> citations,
        long createdAt,
        Long answeredAt,
        String parentId,
        List<String> indices,
        List<SherlockQuote> quotes,
        String documentId,
        String documentName,
        Map<String, String> spelling,
        String notice
) {}
