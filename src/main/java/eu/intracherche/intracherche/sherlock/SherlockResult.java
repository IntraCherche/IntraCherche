package eu.intracherche.intracherche.sherlock;

/**
 * One retrieved passage.
 *
 * @param extract the matched text: the highlight fragments, spliced with " … " when Solr returns
 *                several. Short and centred on the query terms, which is what the cross-encoder
 *                needs — it is what {@code RerankerService} scores, and what the evaluation report
 *                shows.
 * @param context what the LLM reads: one contiguous run of the page, never spliced. Splicing three
 *                windows of a page into one paragraph is how facts about two different people came
 *                back as facts about one; and a fragment cut around a term routinely starts after
 *                the sentence that says whose facts these are, leaving nothing but "il" for the
 *                model to bind. See {@code SherlockRetriever#contextFor}.
 */
public record SherlockResult(String id, String path, String filename, String extract, String context, Integer page) {}
