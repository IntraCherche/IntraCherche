package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One quotation in an answer, and whether the passages the model read really contain it.
 *
 * @param start    offset of the opening quotation mark in the answer (UTF-16, as JavaScript counts)
 * @param end      offset just past the closing quotation mark
 * @param document the 1-based passage the answer cites for it, or null when no citation follows it
 * @param foundIn  the 1-based passage that contains it word for word, or null when none does
 */
// "verified" is written for the UI and derived again on reading.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SherlockQuote(int start, int end, Integer document, Integer foundIn) {

    /**
     * In the passage it is cited from — or, cited from nowhere, in one of the passages.
     * Named explicitly: Jackson writes a record's components, and this is not one, so without
     * the annotation the pane never received it and flagged every quotation.
     */
    @JsonProperty("verified")
    public boolean verified() {
        return foundIn != null && (document == null || document.equals(foundIn));
    }
}
