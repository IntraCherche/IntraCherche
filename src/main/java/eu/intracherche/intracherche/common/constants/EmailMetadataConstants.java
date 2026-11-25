package eu.intracherche.intracherche.common.constants;

/**
 * Normalised metadata keys produced by
 * {@link eu.intracherche.intracherche.indexing.parser.EmailExtractor}.
 *
 * <p>Tika spells the same header differently depending on the format it came from
 * (RFC822 for {@code .eml}, MAPI for {@code .msg}), so the extractor collapses them
 * onto these keys and {@code IndexWorker} maps them to the {@code email_*} Solr fields.
 * Without this they would land in the {@code meta_*} catch-all under names like
 * {@code dc:title}, which the colon makes awkward to query.</p>
 */
public enum EmailMetadataConstants {
    EMAIL_SUBJECT("email.subject"),
    EMAIL_FROM("email.from"),
    EMAIL_TO("email.to"),
    /** Sent date, ISO-8601 in UTC (e.g. {@code 2026-09-16T08:30:00Z}) so Solr can parse it as a pdate. */
    EMAIL_DATE("email.date");

    private final String key;

    EmailMetadataConstants(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
