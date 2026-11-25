package eu.intracherche.intracherche.common;

import java.util.regex.Pattern;

/**
 * Rejects Solr "local params" ({!...}) and the {@code _query_}/{@code _val_} magic fields in
 * free-text search input before it reaches Solr as a raw, unescaped {@code q} parameter
 * ({@code SolrQuery.setQuery(rawUserString)} with no {@code defType} pinned to a parser that
 * doesn't honour them).
 *
 * <p>Without this, a query such as
 * {@code {!join from=lang to=lang fromIndex=OtherCore}content:word} lets an authenticated user
 * learn whether ANY document in ANY Solr core — including one the {@code accessTokens}/
 * {@code denyTokens} filter query would otherwise hide entirely — contains a given word: the
 * join's "from" subquery runs against the whole target core BEFORE this request's own {@code fq}
 * is applied, so it is not access-filtered at all. A user with zero overlap between their SIDs
 * and a core's {@code accessTokens} can still tell, one word at a time, whether that core
 * contains it — and, keyed on a near-unique field, narrow that down to a single document.
 * Confirmed exploitable against a live instance (two calls, same word present vs. absent,
 * returned 36 vs. 0 hits for a core the test SID had no {@code accessTokens} overlap with).</p>
 *
 * <p>Legitimate free-text search has no reason to contain this syntax, so it is rejected outright
 * rather than stripped: a user who genuinely typed a literal "{!" gets a clear error instead of a
 * silently rewritten (and, if the rewrite is imperfect, possibly still exploitable) query. Normal
 * syntax this app does mean to support — quoted phrases, AND/OR, wildcards — is untouched.</p>
 */
public final class SolrQuerySyntaxGuard {

    private static final Pattern DANGEROUS = Pattern.compile("\\{!|_query_|_val_", Pattern.CASE_INSENSITIVE);

    private SolrQuerySyntaxGuard() {}

    /** @throws InvalidSearchQueryException if {@code q} contains disallowed Solr query syntax. */
    public static void check(String q) {
        if (q != null && DANGEROUS.matcher(q).find()) {
            throw new InvalidSearchQueryException("La requête contient une syntaxe non autorisée.");
        }
    }
}
