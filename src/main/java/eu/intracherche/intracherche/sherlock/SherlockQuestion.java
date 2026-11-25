package eu.intracherche.intracherche.sherlock;

import java.util.List;
import java.util.Map;

public record SherlockQuestion(
        String id,
        String username,
        String userEmail,
        String question,
        SherlockQuestionStatus status,
        List<String> userSids,
        String answer,
        List<SherlockCitation> citations,
        long createdAt,
        Long answeredAt,
        String parentId,
        // The Solr cores this question may search; empty means every live index.
        List<String> indices,
        // Each quotation in the answer, checked against the passages the model read.
        List<SherlockQuote> quotes,
        // The one file ({@code parent_id}) the question is about, or null for the whole scope
        String documentId,
        // Its file name, to show the user what the question was asked of
        String documentName,
        // Misspelled words corrected before the question was searched: typed word to correction
        Map<String, String> spelling,
        // What the user should know about how the question was read, e.g. a long document read slowly
        String notice
) {}
