package eu.intracherche.intracherche.web;

import com.github.benmanes.caffeine.cache.Ticker;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.common.DenyTokensCapability;
import eu.intracherche.intracherche.common.MimeTypeUtils;
import eu.intracherche.intracherche.common.SolrQuerySyntaxGuard;
import eu.intracherche.intracherche.config.ConfigurationException;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.SolrConfig;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.security.acl.provider.SmbAccessTokenProvider;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.IndexService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.GroupCommand;
import org.apache.solr.client.solrj.response.GroupResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SearchController {
    public static final String NOT_A_SHARD_REQUEST = "not a shard request";
    public static final String CONTENT_FIELD_NAME = "content";
    private static final String CONTENT_EXACT_FIELD_NAME = "content_exact";
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final int MAX_ROWS = 100;
    /** Snippets shown per file in the result list. */
    private static final int SNIPPETS_SHOWN = 3;
    /** Sections per file considered when choosing those snippets. */
    private static final int SNIPPET_CANDIDATES = 10;
    /** "degraded" values: why a search that should have been hybrid ran on keywords only. */
    static final String DEGRADED_HIGH_LOAD = "HIGH_LOAD";
    static final String DEGRADED_SEMANTIC_UNAVAILABLE = "SEMANTIC_UNAVAILABLE";

    private final SolrClient solrClient;

    @Autowired
    private SearchAccessControl searchAccessControl;

    @Autowired
    private IndexesConfig indexesConfig;

    @Autowired
    private IndexService indexService;

    @Autowired
    private SolrConfig solrConfig;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private SearchRateLimiter searchRateLimiter;

    /**
     * Spelling suggestions and completions asked while typing, together: debounced, a person
     * sends far fewer than this.
     */
    static final int TYPING_RATE_LIMIT_PER_MINUTE = 240;
    private final SearchRateLimiter typingRateLimiter =
            new SearchRateLimiter(TYPING_RATE_LIMIT_PER_MINUTE, Ticker.systemTicker());

    /** Thrown when a user goes over their searches-per-minute allowance. */
    static class SearchRateLimitedException extends RuntimeException {
        SearchRateLimitedException() { super("search rate limit exceeded"); }
    }

    /** Users whose going over the limit was audited this minute: one line per burst, not per request. */
    private final Cache<String, Boolean> rateLimitAudited = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @ExceptionHandler(SearchRateLimitedException.class)
    public ResponseEntity<Map<String, String>> handleRateLimited(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String key = auth != null ? auth.getName() : request.getRemoteAddr();
        if (rateLimitAudited.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            auditService.logSecurityEvent("RATE_LIMITED", "recherches par minute dépassées");
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(searchRateLimiter.retryAfterSeconds()))
                .body(Map.of("error", "Vous avez lancé trop de recherches en peu de temps. "
                        + "Merci de patienter une minute avant de relancer une recherche."));
    }

    public SearchController(SolrClient solrClient) {
        this.solrClient = solrClient;
    }

    /**
     * Extracts just the filename from a full path, handling both forward and backward slashes.
     *
     * @param fullPath the full path string
     * @return the filename portion of the path, or the full path if no separator is found
     */
    /**
     * Copies the email headers onto a result entry, when the hit is an email at all.
     *
     * <p>The keys are left out entirely for non-email hits so the frontend can test for
     * their presence rather than having to distinguish null from "not an email".</p>
     */
    private void addEmailHeaders(Map<String, Object> fileResult, SolrDocument doc) {
        Object subject = doc.getFirstValue("email_subject");
        if (subject != null) fileResult.put("emailSubject", subject);

        Collection<Object> from = doc.getFieldValues("email_from");
        if (from != null && !from.isEmpty()) {
            fileResult.put("emailFrom", from.stream().map(String::valueOf).toList());
        }

        // Epoch millis, the same shape as mtime, so the frontend formats both the same way
        // (and we do not depend on how Jackson happens to render a java.util.Date).
        Object date = doc.getFirstValue("email_date");
        if (date instanceof Date sentAt) {
            fileResult.put("emailDate", sentAt.getTime());
        } else if (date != null) {
            fileResult.put("emailDate", date);
        }
    }

    private String extractFilenameFromPath(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) {
            return "Unknown";
        }

        // Find the last occurrence of either forward or backward slash
        int lastForwardSlash = fullPath.lastIndexOf('/');
        int lastBackwardSlash = fullPath.lastIndexOf('\\');

        // Use the position of the last slash (whichever comes last in the string)
        int lastSlashIndex = Math.max(lastForwardSlash, lastBackwardSlash);

        if (lastSlashIndex != -1) {
            // Extract everything after the last slash
            return fullPath.substring(lastSlashIndex + 1);
        }

        // If no slash found, return the full path as the filename
        return fullPath;
    }

    /**
     * The indexes the current user may see: those Solr holds that indexes.yaml's visibleToGroups
     * opens to one of the user's groups. Every search path (search, spelling, completion,
     * opening a document) goes through this list, so a hidden index cannot be reached by
     * naming it in the request, nor by leaving the selection empty.
     */
    @GetMapping("/indices")
    public List<String> getAvailableIndices() throws SolrServerException, IOException {
        return indexesConfig.visibleCores(solrCoreNames(), searchAccessControl.getCurrentUserAuthorities());
    }

    private List<String> solrCoreNames() throws SolrServerException, IOException {
        // Query Solr's Core Admin API
        CoreAdminRequest request = new CoreAdminRequest();
        request.setAction(CoreAdminParams.CoreAdminAction.STATUS);
        CoreAdminResponse response = request.process(solrClient);

        // Extract the names of all active cores
        List<String> coreNames = new ArrayList<>();
        response.getCoreStatus().forEach((name, status) -> coreNames.add(name));

        return coreNames;
    }

    /**
     * CAUTION :
     * FOR MULTI INDEX SEARCH TO ACTUALLY WORK IT IS REQUIRED TO ENABLE SHARDING WHEN
     * LAUNCHING SOLR WITH : bin/solr start -Dsolr.allowUrls=http://localhost:8983/solr,http://127.0.0.1:8983/solr
     *
     * @param q
     * @param indices
     * @param page
     * @param rows
     * @return
     * @throws SolrServerException
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/search")
    public Map<String, Object> search(
            HttpServletRequest httpRequest,
            @RequestParam(name = "q") String q,
            @RequestParam(name = "indices", defaultValue = "Index_3") List<String> indices, // Accept a list
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "rows", defaultValue = "10") int rows,
            @RequestParam(name = "filename", required = false) String filename,
            @RequestParam(name = "geoCity", required = false) String geoCity,
            @RequestParam(name = "geoDpt", required = false) String geoDpt,
            @RequestParam(name = "geoRegion", required = false) String geoRegion,
            @RequestParam(name = "geoCountry", required = false) String geoCountry,
            @RequestParam(name = "sort", defaultValue = "score") String sort,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo,
            @RequestParam(name = "mm", required = false) String mm) throws SolrServerException, IOException {

        // Checked before any work, so an over-the-limit request costs neither Solr nor embedding.
        Authentication rateAuth = SecurityContextHolder.getContext().getAuthentication();
        String rateKey = rateAuth != null ? rateAuth.getName() : httpRequest.getRemoteAddr();
        if (!searchRateLimiter.tryAcquire(rateKey)) throw new SearchRateLimitedException();

        // q reaches Solr as a raw, unescaped q parameter (see addFilters below and the BM25 query
        // built from it) — reject Solr local-params/magic-field syntax before it can be used to
        // read across the accessTokens boundary. See SolrQuerySyntaxGuard for why.
        SolrQuerySyntaxGuard.check(q);

        List<String> cleanIndices = resolveIndices(indices);

        // --- STEP 0.4: TERMINATION ---
        // If there's still nothing (Solr is empty), return an empty result immediately
        if (cleanIndices.isEmpty()) {
            Map<String, Object> emptyResponse = new HashMap<>();
            emptyResponse.put("results", new ArrayList<>());
            emptyResponse.put("totalResults", 0);
            return emptyResponse;
        }

        // --- STEP 0.4: PAGINATION BOUNDS ---
        page = Math.max(0, page);
        rows = Math.min(Math.max(1, rows), MAX_ROWS);

        if (page == 0) {
            Authentication auditAuth = SecurityContextHolder.getContext().getAuthentication();
            auditService.logSearch(httpRequest, auditAuth != null ? auditAuth.getName() : "unknown", q, cleanIndices);
        }

        SolrQuery query = new SolrQuery();

        // Phrase queries (user wrapped the query in quotes) must bypass EdgeNGram so that "remi"
        // never matches "remilitarisation". Route them to content_exact (no EdgeNGram at index time).
        // content_exact is not stored, so snippets still come from content, re-analysed with content's
        // INDEX analyser; its EdgeNGram must keep the original tokens (preserveOriginal) or a phrase
        // containing short words like "1234 AB 56" matches but gets no snippet.
        boolean isPhraseQuery = q != null && q.contains("\"");
        String defaultField = isPhraseQuery ? CONTENT_EXACT_FIELD_NAME : CONTENT_FIELD_NAME;

        query.setParam("df", defaultField);
        query.setParam("q.op", "AND");
        query.setQuery((q == null || q.isBlank() || "*:*".equals(q)) ? "*:*" : q);
        if (mm != null && !mm.isBlank()) query.setParam("mm", mm);

//        // Enable debug query
//        query.setParam("debugQuery", "true");

        // 1. PAGE-AWARE GROUPING
        // Add Grouping parameters
        query.setParam("group", "true");
        query.setParam("group.field", "parent_id");
        // Fetch more sections than are shown so SnippetSelector can pick pages that, together,
        // show every search term instead of the 3 best-ranked ones.
        query.setParam("group.limit", String.valueOf(SNIPPET_CANDIDATES));
        query.setParam("group.ngroups", "true"); // To get the total count of files found

        // Pagination logic
        query.setStart(page * rows);
        query.setRows(rows);

        // 2. ENHANCED HIGHLIGHTING
        query.setHighlight(true);
        query.addHighlightField(CONTENT_FIELD_NAME);
        query.setHighlightSimplePre("<b>");
        query.setHighlightSimplePost("</b>");
        query.setHighlightSnippets(1); // 1 snippet per page (since we have 3 sections)
        query.setHighlightFragsize(180);
        query.setParam("hl.bs.type", "WORD");
        query.setParam("hl.maxAnalyzedChars", "2000000"); // 2MB limit (required for long spreadsheets
        // For phrase queries, re-analyse the stored content with content's index analyser
        // (see the preserveOriginal note above).
        if (isPhraseQuery) {
            query.setParam("f.content.hl.offsetSource", "ANALYSIS");
        }


        // Setup the "Where" (All possible shards to look at)
        // This ensures the Sidebar counts (Facets) are always accurate for all indices
        String shards = shardsValue(cleanIndices);
        if (shards != null) {
            query.setParam("shards", shards);
            query.setParam("distrib", "true");
        }

        // Use the first index as the primary endpoint, or a default
        String primaryCore = cleanIndices.get(0);

        // Debug: Log the core name being used
        log.info("Querying Solr core: {}", primaryCore);

        addFilters(query, cleanIndices, filename, geoCity, geoDpt, geoRegion, geoCountry, dateFrom, dateTo);

        // Sort order: anything other than the two mtime variants = relevance (Solr default: score desc)
        if ("mtime_desc".equals(sort)) {
            query.setSort("mtime", SolrQuery.ORDER.desc);
        } else if ("mtime_asc".equals(sort)) {
            query.setSort("mtime", SolrQuery.ORDER.asc);
        }

        // Define the Facet Request
        // 'counts_by_source' is a custom name we give to this facet block
        // This tells Solr: "Group by index_source and count them"

        //OLD
//        String jsonFacet = "{ \"counts_by_source\": { \"type\": \"terms\", \"field\": \"index_source\", \"limit\": 10, \"mincount\": 0 } }";

        // NEW: Counts unique parent_id values per index_source
        String jsonFacet = "{" +
                "  \"counts_by_source\": {" +
                "    \"type\": \"terms\"," +
                "    \"field\": \"index_source\"," +
                "    \"limit\": 10," +
                "    \"facet\": {" +
                "      \"unique_files\": \"unique(parent_id)\"" + // This is the magic line
                "    }" +
                "  }" +
                "}";
        query.setParam("json.facet", jsonFacet);

        // Hybrid search: BM25 + kNN + RRF — active when embedding is enabled and query is not a wildcard.
        // Date-based sort is incompatible with RRF ranking, so it stays on BM25 only.
        boolean useHybrid = embeddingService.isEnabled()
                && q != null && !q.isBlank() && !"*:*".equals(q)
                && "score".equals(sort);
        // Set when hybrid search was wanted but could not run, so the UI can explain why the
        // results differ from the usual ones. Absent when BM25 was the intended path.
        String degraded = null;
        // The filters as they stand before the exclusions: what the spelling suggestion runs under.
        String[] searchFilters = query.getFilterQueries();
        if (useHybrid) {
            // Bounded wait: while the indexer saturates the embedding service, searching
            // keyword-only now beats a semantic answer the browser has stopped waiting for.
            EmbeddingService.QueryEmbedding embedding = embeddingService.embedQuery(q);
            if (embedding.vector() != null) {
                try {
                    Map<String, Object> hybrid = hybridSearch(q, embedding.vector(), primaryCore, query, page, rows, cleanIndices);
                    addSpellingSuggestion(hybrid, q, searchFilters, query.get("shards"), cleanIndices);
                    return hybrid;
                } catch (Exception e) {
                    log.warn("Hybrid search failed ({}), falling back to BM25", e.getMessage());
                    degraded = DEGRADED_SEMANTIC_UNAVAILABLE;
                }
            } else {
                degraded = embedding.status() == EmbeddingService.QueryEmbeddingStatus.BUSY
                        ? DEGRADED_HIGH_LOAD
                        : DEGRADED_SEMANTIC_UNAVAILABLE;
            }
        }

        // -word excludes the whole document, not just the pages containing it. Applied here, after
        // the hybrid branch, because hybridSearch copies this query's filters but not the local
        // params the joins reference; it applies its own.
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse(q);
        DocumentLevelMatch.applyExclusions(query, units, defaultField);

        // EXECUTE THE QUERY
        QueryResponse response = solrClient.query(primaryCore, query, SolrRequest.METHOD.POST);
        // Strict AND found nothing → relax to OR so the user gets the best partial matches
        // instead of an empty page. Retried only on a zero-result query, so the extra
        // round-trip is the cheapest possible case and adds no meaningful load.
        //
        // CAUTION when adding a filter query: q.op is a request-level param, so this retry
        // changes the default operator for the fq clauses too, not just for q. It is harmless
        // today only because every fq built above is either a single clause (path_lc, mtime)
        // or spells its operator out explicitly (index_source, accessTokens, the geo filters).
        // An fq that relied on the implicit AND would silently widen on this second pass.
        // Filters must never relax: only the terms the user typed in the search bar do.
        //
        // Before OR, try AND at document level: every term somewhere in the document, not
        // necessarily on one page. Its join filters only narrow — they never relax a filter.
        boolean relaxedToOr = false;
        if (!"*:*".equals(query.getQuery()) && nGroupsOf(response) == 0) {
            query.setParam("q.op", "OR");
            boolean widen = DocumentLevelMatch.canWidenToDocument(units);
            if (widen) {
                DocumentLevelMatch.applyInclusions(query, units, defaultField);
                response = solrClient.query(primaryCore, query, SolrRequest.METHOD.POST);
                if (nGroupsOf(response) == 0) DocumentLevelMatch.removeInclusions(query, units);
            }
            if (!widen || nGroupsOf(response) == 0) {
                response = solrClient.query(primaryCore, query, SolrRequest.METHOD.POST);
                relaxedToOr = true;
            }
        }
        List<String> queryTerms = SnippetSelector.queryTerms(q);
        // IMPORTANT: This will be NULL when grouping is enabled
//        long totalFound = response.getResults().getNumFound();
        double searchTimeInSec = response.getQTime() / 1000.0;


        //  Counting the results per index
        Map<String, Long> resultsPerIndex = new HashMap<>();

        // Initialize all counts to 0 for all known index
        for (String name : getAvailableIndices()) {
            resultsPerIndex.put(name, 0L);
        }
        Object facetsObj = response.getResponse().get("facets");

        if (facetsObj instanceof NamedList) {
            NamedList<Object> facets = (NamedList<Object>) facetsObj;
            Object countsObj = facets.get("counts_by_source");

            if (countsObj instanceof NamedList) {
                NamedList<Object> countsBySource = (NamedList<Object>) countsObj;
                List<NamedList<Object>> buckets = (List<NamedList<Object>>) countsBySource.get("buckets");

                if (buckets != null) {
                    for (NamedList<Object> bucket : buckets) {
                        String sourceName = (String) bucket.get("val");
                        // Solr returns counts as Long or Integer depending on version

                        // OLD: Number count = (Number) bucket.get("count");

                        // NEW: Get the unique count we defined in the JSON facet
                        Number count = (Number) bucket.get("unique_files");

                        // Fallback to 0 if null
                        resultsPerIndex.put(sourceName, count != null ? count.longValue() : 0L);
                    }
                }
            }
        }


        // Highlights are stored in a separate Map in the response: Map<ID, Map<FieldName, List<Snippets>>>
        Map<String, Map<String, List<String>>> highlighting = response.getHighlighting();

        // 1. Extract the Grouping metadata
        GroupResponse groupResponse = response.getGroupResponse();
        // Note: "parent_id" corresponds to the field you set in query.setParam("group.field", "parent_id")
        GroupCommand groupCommand = groupResponse.getValues().get(0);

        // 2. This is your new "totalFound" - it counts unique files, not total sections
        long totalFound = groupCommand.getNGroups();

        // 3. Process the results
        List<Map<String, Object>> resultsList = groupCommand.getValues().stream().map(group -> {
                    Map<String, Object> fileResult = new HashMap<>();

                    // Each group contains the matching sections for ONE specific file
                    SolrDocumentList pages = group.getResult();
                    if (pages == null || pages.isEmpty()) return null;

                    // Use the first (best matching) page for the file-level metadata
                    SolrDocument firstPage = pages.get(0);

                    String fullPath = (String) firstPage.getFieldValue("path");

                    // This safely grabs the first element if it's a list, or the object itself if it's not.
                    String mimeType = Objects.toString(firstPage.getFirstValue("meta_Content-Type"), "");

                    // The specific Solr ID for the first page
                    // This will be used by the title click in Vue
                    fileResult.put("id", firstPage.getFieldValue("id"));

                    fileResult.put("path", fullPath);

                    // Extract filename from path for the title
                    fileResult.put("filename", fullPath != null ? extractFilenameFromPath(fullPath) : "Unknown");

                    fileResult.put("parentId", firstPage.getFieldValue("parent_id"));
                    fileResult.put("source", firstPage.getFieldValue("index_source"));
                    fileResult.put("mtime", firstPage.getFieldValue("mtime"));

                    // If the document is a text (docx, odt, txt, ...) the page number is approximate
                    // and we need to show it to the user
                    // If mimeType is null we trigger the approxiation tag so that we know there is a bug somewhere
                    fileResult.put("isPageApprox", mimeType != null ? MimeTypeUtils.isTextDocument(mimeType) : true) ;

                    // For spreadsheets we want to show the sheet number (not page)
                    fileResult.put("isSpreadsheet", mimeType != null ? MimeTypeUtils.isSpreadsheet(mimeType) : false) ;

                    // Coarse family (pdf, doc, sheet, ...) so the result line can show a type icon
                    fileResult.put("docType", MimeTypeUtils.categorize(mimeType));

                    // Subject/sender/date, present only on email hits
                    addEmailHeaders(fileResult, firstPage);

                    // Build the snippets array
                    List<Map<String, Object>> pageSnippets = new ArrayList<>();

                    List<String> firstSnippets = pages.stream()
                            .map(pageDoc -> firstSnippet(highlighting, (String) pageDoc.getFieldValue("id")))
                            .toList();
                    for (int pageIndex : SnippetSelector.select(firstSnippets, queryTerms, SNIPPETS_SHOWN)) {
                        SolrDocument pageDoc = pages.get(pageIndex);
                        String pId = (String) pageDoc.getFieldValue("id");
                        // Ensure page_number is treated as an Integer
                        Object pNumObj = pageDoc.getFieldValue("page_number");
                        // Extract the new sheet_name field
                        Object sheetNameObj = pageDoc.getFieldValue("sheet_name");

                        if (highlighting != null && highlighting.get(pId) != null && highlighting.get(pId).get(CONTENT_FIELD_NAME) != null) {
                            for (String snippetText : highlighting.get(pId).get(CONTENT_FIELD_NAME)) {
                                Map<String, Object> snippetObj = new HashMap<>();
                                snippetObj.put("text", snippetText);
                                // Keep as Number so Vue doesn't have to parse strings
                                snippetObj.put("page", pNumObj);
                                // Add sheetName to the snippet object
                                snippetObj.put("sheetName", sheetNameObj);
                                // Include the exact Solr ID for this specific page
                                snippetObj.put("id", pId);



                                pageSnippets.add(snippetObj);
                            }
                        }
                    }

                    fileResult.put("snippets", pageSnippets);

                    // All pages carry the same geo data stamped by IndexWorker; reading from firstPage is sufficient.
                    fileResult.put("geolocationCities",   toGeoList(firstPage.getFieldValues("geolocation_city")));
                    fileResult.put("geolocationDepts",    toGeoList(firstPage.getFieldValues("geolocation_dpt")));
                    fileResult.put("geolocationRegions",  toGeoList(firstPage.getFieldValues("geolocation_region")));
                    fileResult.put("geolocationCountries", toGeoList(firstPage.getFieldValues("geolocation_country")));

                    return fileResult;
                })
                .filter(Objects::nonNull) // Clean up any empty groups
                .collect(Collectors.toList());

        // 4. Wrap the response
        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("results", resultsList);
        finalResponse.put("totalResults", totalFound); // Correctly updated from nGroups
        finalResponse.put("relaxedToOr", relaxedToOr);
        if (degraded != null) finalResponse.put("degraded", degraded);
        finalResponse.put("resultsPerIndex", resultsPerIndex);
        finalResponse.put("searchTime", searchTimeInSec);
        finalResponse.put("currentPage", page);
        addSpellingSuggestion(finalResponse, q, searchFilters, query.get("shards"), cleanIndices);

        return finalResponse;

    }

    /**
     * "Vouliez-vous dire…" while the query is being typed, before any search: the query with its
     * misspelled words corrected, under the filters the search would run with. Empty when there
     * is nothing to correct.
     *
     * <p>Same verification as the search's suggestion (see {@link SpellingSuggestion}): a word is
     * proposed only if it finds a page the user can read, the accessTokens filter being built
     * here from the user's authorities, never taken from the request. Nothing is cached, so no
     * correction found for one user can be served to another.
     *
     * <p>Its own rate limit, shared with {@link #complete}, far above what debounced typing
     * sends, so that typing never eats into the searches allowed. Over it, or on any failure, the answer is simply empty: a
     * missing suggestion is not worth an error under the search bar. Not audited as a search.
     */
    @GetMapping("/spell")
    public Map<String, String> spell(
            HttpServletRequest httpRequest,
            @RequestParam(name = "q") String q,
            @RequestParam(name = "indices", required = false) List<String> indices,
            @RequestParam(name = "filename", required = false) String filename,
            @RequestParam(name = "geoCity", required = false) String geoCity,
            @RequestParam(name = "geoDpt", required = false) String geoDpt,
            @RequestParam(name = "geoRegion", required = false) String geoRegion,
            @RequestParam(name = "geoCountry", required = false) String geoCountry,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String rateKey = auth != null ? auth.getName() : httpRequest.getRemoteAddr();
        if (!typingRateLimiter.tryAcquire(rateKey)) return Map.of();
        try {
            List<String> cores = resolveIndices(indices == null ? List.of() : indices);
            if (cores.isEmpty()) return Map.of();
            SolrQuery filters = new SolrQuery();
            addFilters(filters, cores, filename, geoCity, geoDpt, geoRegion, geoCountry, dateFrom, dateTo);
            return spellingSuggestion(q, filters.getFilterQueries(), shardsValue(cores), cores)
                    .map(suggestion -> Map.of("suggestion", suggestion))
                    .orElse(Map.of());
        } catch (Exception e) {
            log.warn("Spelling suggestion while typing failed ({})", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Completions of the word being typed at the end of the query, under the filters the search
     * would run with: the query with that word completed, at most {@link PrefixCompletion#SHOWN}.
     *
     * <p>Only words found on a page the user can read (see {@link PrefixCompletion}), the
     * accessTokens filter being built here from the user's authorities, never taken from the
     * request. Nothing is cached. Same rate limit as {@link #spell}, and the same empty answer
     * over it or on any failure. Not audited as a search.
     */
    @GetMapping("/complete")
    public Map<String, List<String>> complete(
            HttpServletRequest httpRequest,
            @RequestParam(name = "q") String q,
            @RequestParam(name = "indices", required = false) List<String> indices,
            @RequestParam(name = "filename", required = false) String filename,
            @RequestParam(name = "geoCity", required = false) String geoCity,
            @RequestParam(name = "geoDpt", required = false) String geoDpt,
            @RequestParam(name = "geoRegion", required = false) String geoRegion,
            @RequestParam(name = "geoCountry", required = false) String geoCountry,
            @RequestParam(name = "dateFrom", required = false) String dateFrom,
            @RequestParam(name = "dateTo", required = false) String dateTo) {
        Optional<PrefixCompletion.Prefix> prefix = PrefixCompletion.lastWord(q);
        if (prefix.isEmpty()) return Map.of();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String rateKey = auth != null ? auth.getName() : httpRequest.getRemoteAddr();
        if (!typingRateLimiter.tryAcquire(rateKey)) return Map.of();
        try {
            List<String> cores = resolveIndices(indices == null ? List.of() : indices);
            if (cores.isEmpty()) return Map.of();
            SolrQuery filters = new SolrQuery();
            addFilters(filters, cores, filename, geoCity, geoDpt, geoRegion, geoCountry, dateFrom, dateTo);

            Set<String> candidates = new LinkedHashSet<>();
            for (String core : cores) {
                QueryResponse terms = new QueryRequest(PrefixCompletion.termsRequest(prefix.get()), SolrRequest.METHOD.POST)
                        .process(solrClient, core);
                PrefixCompletion.terms(terms).stream()
                        .limit(PrefixCompletion.MAX_CANDIDATES - candidates.size())
                        .forEach(candidates::add);
            }
            if (candidates.isEmpty()) return Map.of();
            List<String> candidateList = List.copyOf(candidates);
            QueryResponse counts = new QueryRequest(
                    PrefixCompletion.countRequest(candidateList, filters.getFilterQueries(), shardsValue(cores)),
                    SolrRequest.METHOD.POST).process(solrClient, cores.get(0));
            List<String> completions = PrefixCompletion.readable(counts, candidateList, prefix.get()).stream()
                    .map(term -> PrefixCompletion.complete(q, prefix.get(), term))
                    .toList();
            return completions.isEmpty() ? Map.of() : Map.of("completions", completions);
        } catch (Exception e) {
            log.warn("Completion while typing failed ({})", e.getMessage());
            return Map.of();
        }
    }

    /**
     * The indices to search: the requested ones Solr actually has, or all of them when none is
     * requested. Empty when Solr has none.
     */
    private List<String> resolveIndices(List<String> indices) throws SolrServerException, IOException {
        // --- SANITIZATION ---
        // Remove nulls, empty strings (""), or strings with only spaces (" ")
        List<String> cleanIndices = indices.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.toList());

        // --- FETCH AVAILABLE + FALLBACK ---
        // Only the indexes this user may see: an empty selection means all of those, not all of Solr's.
        List<String> availableIndices = getAvailableIndices();
        if (cleanIndices.isEmpty()) {
            cleanIndices = new ArrayList<>(availableIndices);
        }

        // --- ALLOWLIST VALIDATION ---
        // Reject any index name not returned by Solr itself — prevents Solr query injection and SSRF
        // via the index_source filter and shards parameter — or hidden from this user.
        Set<String> allowedSet = new HashSet<>(availableIndices);
        return cleanIndices.stream()
                .filter(allowedSet::contains)
                .collect(Collectors.toList());
    }

    /**
     * The shards parameter for a search across these indices, null for a single one.
     * Full urls to shards are required, not just index names.
     */
    private String shardsValue(List<String> cleanIndices) {
        if (cleanIndices.size() <= 1) return null;
        String solrHost = solrConfig.solrBaseUrl().replaceFirst("^https?://", "");
        return cleanIndices.stream()
                .map(name -> solrHost + "/" + name)
                .collect(Collectors.joining(","));
    }

    /**
     * The search's filters: the chosen indices, what the user may read, and the advanced
     * panel's filename, place and date filters.
     */
    private void addFilters(SolrQuery query, List<String> cleanIndices, String filename,
                            String geoCity, String geoDpt, String geoRegion, String geoCountry,
                            String dateFrom, String dateTo) {
        // This filters the actual results based on the Sidebar checkboxes
        if (cleanIndices != null && !cleanIndices.isEmpty()) {
            String fq = "index_source:(" + String.join(" OR ", cleanIndices) + ")";
            query.addFilterQuery(fq);
        }

        // Add access control filter to only return documents the logged in user is entitled to read
        List<String> userAuthorities = searchAccessControl.getCurrentUserAuthorities();

        if (userAuthorities != null && !userAuthorities.isEmpty()) {
            // Escape the SIDs (the S-1-5-... strings) so a token can never break out of its quoted
            // phrase and inject Solr query syntax. Inside a quoted phrase only backslash and double
            // quote are special; neutralise both. SIDs contain neither, so matching is unchanged —
            // this is defence-in-depth against a malformed/hostile authority value reaching here.
            String quotedSids = userAuthorities.stream()
                    .map(token -> "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(" OR "));
            String accessFilter = "accessTokens:(" + quotedSids + ")";

            query.addFilterQuery(accessFilter);
            log.debug("Applying security filter: {}", accessFilter);

            // A Windows Deny ACE on any of the user's SIDs overrides an Allow ACE elsewhere in
            // their token, so it must be excluded regardless of what matched above (see the
            // Deny-ACE fix: allow and deny are tracked as two separate fields, never merged).
            // Gated on the schema actually having the field: an upgrade leaves existing cores on
            // the old schema until reindex-all pushes the new one, and filtering on a field Solr
            // doesn't have fails the whole query, not just the deny check.
            String anyCore = (cleanIndices != null && !cleanIndices.isEmpty()) ? cleanIndices.get(0) : null;
            if (DenyTokensCapability.isAvailable(solrClient, anyCore)) {
                String denyFilter = "-denyTokens:(" + quotedSids + ")";
                query.addFilterQuery(denyFilter);
                log.debug("Applying deny filter: {}", denyFilter);
            }
        } else {
            // CRITICAL: If the user has no rights, restrict to a value that will never exist
            // or use a negative query to match nothing.
            query.addFilterQuery("-*:*");
            log.warn("User has no authorities! Blocking all results.");
        }

        // Optional filename filter (case-insensitive wildcard on path_lc).
        // Each word becomes a wildcard segment so "rapport annuel" matches "Rapport Annuel.pdf".
        if (filename != null && !filename.isBlank()) {
            String[] terms = filename.toLowerCase().trim().split("\\s+");
            String wildcardPattern = Arrays.stream(terms)
                    .map(ClientUtils::escapeQueryChars)
                    .collect(Collectors.joining("*", "*", "*"));
            query.addFilterQuery("path_lc:" + wildcardPattern);
        }

        // Optional geolocation filters — comma-separated values become OR clauses.
        // Values are phrase-quoted so multi-word place names (e.g. "Île-de-France") match exactly.
        addGeoFilter(query, "geolocation_city", geoCity);
        addGeoFilter(query, "geolocation_dpt", geoDpt);
        addGeoFilter(query, "geolocation_region", geoRegion);
        addGeoFilter(query, "geolocation_country", geoCountry);

        // Optional date range filter on mtime (epoch ms). Dates arrive as ISO strings (yyyy-MM-dd).
        try {
            ZoneId zone = ZoneId.systemDefault();
            String fromMs = dateFrom != null && !dateFrom.isBlank()
                    ? String.valueOf(LocalDate.parse(dateFrom).atStartOfDay(zone).toInstant().toEpochMilli())
                    : "*";
            String toMs = dateTo != null && !dateTo.isBlank()
                    ? String.valueOf(LocalDate.parse(dateTo).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli())
                    : "*";
            if (!"*".equals(fromMs) || !"*".equals(toMs)) {
                query.addFilterQuery("mtime:[" + fromMs + " TO " + toMs + "]");
            }
        } catch (Exception e) {
            log.warn("Invalid date range params (dateFrom={}, dateTo={}) — ignoring", dateFrom, dateTo);
        }
    }

    /**
     * Adds "suggestion", the query with its misspelled words corrected, when the words found
     * nothing together and some of them are on no page the user can read
     * (see {@link SpellingSuggestion}). Best effort: a failure costs the suggestion, never the search.
     */
    private void addSpellingSuggestion(Map<String, Object> response, String q, String[] filters,
                                       String shards, List<String> cores) {
        if (!Boolean.TRUE.equals(response.get("relaxedToOr"))) return;
        spellingSuggestion(q, filters, shards, cores).ifPresent(suggestion -> response.put("suggestion", suggestion));
    }

    /**
     * {@code q} with the words it has on no page the user can read corrected, if any could be.
     * Best effort: a failed request only costs the corrections it would have given.
     */
    private Optional<String> spellingSuggestion(String q, String[] filters, String shards, List<String> cores) {
        Map<String, String> corrections = SpellingSuggestion.corrections(
                solrClient, SpellingSuggestion.words(q), filters, shards, cores);
        return corrections.isEmpty() ? Optional.empty() : Optional.of(SpellingSuggestion.rewrite(q, corrections));
    }


    /**
     * Process a download request using the new design pattern
     *
     * @param docId    The document ID to download
     * @param response The HTTP response to stream the file to
     * @throws IOException If there's an error during download
     */
    @GetMapping("/download")
    @ResponseBody
    public void processDownload(
            @RequestParam(name = "id", required = false) String docId,
            @RequestParam(name = "path", required = false) String docPath,
            HttpServletResponse response) throws IOException, ConfigurationException {
        IndexedDocument solrDocument;
        if (docId != null && !docId.isBlank()) {
            solrDocument = findDocumentInSolr(docId);
        } else if (docPath != null && !docPath.isBlank()) {
            solrDocument = findDocumentByPath(docPath);
        } else {
            response.sendError(400, "Either id or path parameter is required");
            return;
        }

        if (solrDocument == null) {
            response.sendError(404, "Document not found");
            return;
        }


        // 3. Extract necessary fields from the Solr document
        String indexSource = solrDocument.indexSource();
        String smbServer = solrDocument.smbServer();
        String smbShare = solrDocument.smbShare();
        String path = solrDocument.path().toString();
        Set<String> accessTokens = solrDocument.accessTokens();
        Set<String> denyTokens = solrDocument.denyTokens();


        // Get current user name
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String currentUser = (auth != null) ? auth.getName() : "unknown-user";

        // Log information (legal obligation)
        String docInfo = String.format("SMB Server: %s, SMB Share: %s, Path: %s",
                smbServer, smbShare, path);

        log.info("User {} wants to open file : {}",
                currentUser, docInfo);

        // 2. Check user access rights against accessTokens from solrDocument (first line of defense, this is fast to check against the index)
        // NOte : we convert the user Authorities to a hashset for better perf in the
        // disjoint() that just follows.
        Set<String> userAuthorities = new HashSet<>(searchAccessControl.getCurrentUserAuthorities());

        // Collections.disjoint returns true if the two collections have NO elements in common.
        // So, we negate it (!) to find if they HAVE elements in common.
        //
        // Fail closed: a document with no access tokens is treated as inaccessible, NOT public.
        // Legitimately world-readable files carry the well-known "Everyone" SID (S-1-1-0), which
        // every authenticated user also holds (see SidAuthoritiesPopulator), so they still match
        // via disjoint() below. An empty token set means the ACL could not be extracted (or an
        // empty NTFS DACL = deny-all), so we deny rather than expose the file.
        //
        // A Windows Deny ACE on any of the user's SIDs overrides an Allow ACE elsewhere in their
        // token, so it is checked in addition to (never instead of) the allow check above.
        boolean hasAccess = accessTokens != null && !accessTokens.isEmpty() &&
                !Collections.disjoint(accessTokens, userAuthorities);
        boolean isDenied = denyTokens != null && !denyTokens.isEmpty() &&
                !Collections.disjoint(denyTokens, userAuthorities);

        if (!hasAccess || isDenied) {
            log.warn("Access Denied (INDEX BASED CHECK): User {} attempted to access document {} without matching SIDs (denied={}). Document SIDs (as stored in index {}): {}, deny SIDs: {}",
                    currentUser, docInfo, isDenied, indexSource, accessTokens, denyTokens);
            auditService.logSecurityEvent("ACCESS_DENIED", "téléchargement refusé (droits de l'index) : " + docInfo);
            response.sendError(403, "Access denied: Insufficient permissions");
            return;
        }

        log.info("Access Granted (INDEX BASED CHECK): User {} matches SIDs (as stored in index {}) for document {}", currentUser, indexSource, docInfo);


        // 4. YAML Lookup
        SmbCredentials creds = indexService.getSmbCredentialsFor(indexSource, smbServer, smbShare);

        // 5. Connect & Stream (Running on a Virtual Thread)
        try (SMBClient client = new SMBClient()) {
            try (Connection conn = client.connect(smbServer)) {
                Session session = conn.authenticate(
                        new AuthenticationContext(creds.user(), creds.pass().toCharArray(), creds.domain())
                );

                try (DiskShare share = (DiskShare) session.connectShare(smbShare)) {
                    // 5.1. Normalize the path for SMB (ensure backslashes and no leading slash)
                    String cleanPath = path.replace("/", "\\");
                    if (cleanPath.startsWith("\\")) {
                        cleanPath = cleanPath.substring(1);
                    }


                    // 5.2. Open the file on the remote share
                    // We use FILE_OPEN to ensure we only try to read existing files
                    try (com.hierynomus.smbj.share.File smbFile = share.openFile(
                            cleanPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null)) {


                        // Now we check again whether the user has read access on the actual file

                        SmbAccessTokenProvider tokenProvider = new SmbAccessTokenProvider(share);
                        Set<String> liveTokens = tokenProvider.extractAccessTokens(smbFile);
                        Set<String> liveDenyTokens = tokenProvider.extractDenyTokens(smbFile);

                        // Fail closed, consistent with the index-based check above: empty live
                        // tokens = deny (real public files carry S-1-1-0 and still match). A live
                        // Deny ACE overrides an Allow ACE elsewhere in the user's token, same as
                        // the index-based check.
                        boolean hasStillAccess = liveTokens != null && !liveTokens.isEmpty() &&
                                !Collections.disjoint(liveTokens, userAuthorities);
                        boolean stillDenied = liveDenyTokens != null && !liveDenyTokens.isEmpty() &&
                                !Collections.disjoint(liveDenyTokens, userAuthorities);

                        // 5.3. Security Check
                        if (!hasStillAccess || stillDenied) {
                            auditService.logSecurityEvent("ACCESS_DENIED", "téléchargement refusé (droits du partage) : " + docInfo);
                            response.sendError(403, "Access Denied: Insufficient permissions");
                            log.warn("Access Denied (LIVE CHECK): User {} attempted to access document {} without matching SIDs (denied={}). Document SIDs : {}, deny SIDs: {}",
                                    currentUser, docInfo, stillDenied, accessTokens, liveDenyTokens);
                            return;
                        }

                        log.info("Access Granted (LIVE CHECK): User {} matches SIDs (live filesystem check) for document {}", currentUser, docInfo);


                        // 5.4. Set the headers
                        response.setContentType("application/octet-stream");
                        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;

                        // We also set the disposition (inline : opens directly VS attachement : downloaded on disk)
                        // depending on the file format (PDF / IMG are opened directly because there is no risk,
                        // but other ones are not opened directly eg html to prevent xss attacks)
                        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase() : "";

                        // 5.4.1. Determine Content-Typ
                        String contentType = "application/octet-stream"; // Default
                        String disposition = "attachment";

                        // 5.4.2. Set Disposition
                        // We use "inline" for PDFs, text files, and images so they open in the tab
                        switch (extension) {
                            case "pdf":
                                contentType = "application/pdf";
                                disposition = "inline";
                                break;
                            case "txt", "log", "java", "conf":
                                // Adding the charset to display correctly text files in browser when user wants to view them in browser
                                contentType = "text/plain;charset=UTF-8";
                                disposition = "inline";
                                break;
                            case "jpg", "jpeg", "png", "gif":
                                contentType = "image/" + (extension.equals("jpg") ? "jpeg" : extension);
                                disposition = "inline";
                                break;
                            // svg deliberately excluded: SVG can embed <script> tags → served as attachment
                        }

                        // 5.5. Set headers ONCE
                        response.setContentType(contentType);
                        response.setHeader("Content-Disposition", String.format("%s; filename=\"%s\"", disposition, fileName));

                        //  Browsers sometimes try to be "too smart" and might
                        //  execute code if a file looks like HTML, even if you said it's a PDF.
                        // This line prevents the execution
                        response.setHeader("X-Content-Type-Options", "nosniff");

                        // 5.6. Set the content length so the browser shows a progress bar
                        long fileSize = share.getFileInformation(cleanPath).getStandardInformation().getEndOfFile();
                        response.setContentLengthLong(fileSize);

                        // 5.7. PIPE THE DATA
                        try (InputStream is = smbFile.getInputStream();
                             OutputStream os = response.getOutputStream()) {

                            log.debug("Starting stream for: {}", cleanPath);
                            is.transferTo(os); // This is where the actual byte transfer happens
                            os.flush();
                            log.debug("Stream completed for: {}", cleanPath);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Download failed for source: {}", indexSource, e);
            response.sendError(500, "Download failed");
        }
    }


    private static final int MAX_GEO_TERMS = 10;

    private void addGeoFilter(SolrQuery query, String fieldName, String values) {
        if (values == null || values.isBlank()) return;
        List<String> clauses = Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_GEO_TERMS)
                .map(s -> "\"" + ClientUtils.escapeQueryChars(s) + "\"")
                .collect(Collectors.toList());
        if (!clauses.isEmpty()) {
            query.addFilterQuery(fieldName + ":(" + String.join(" OR ", clauses) + ")");
        }
    }

    private static List<String> toGeoList(Collection<Object> values) {
        if (values == null) return List.of();
        return values.stream().map(Object::toString).collect(Collectors.toList());
    }

    /**
     * Hybrid search: runs BM25 and kNN in parallel, merges results with Reciprocal Rank Fusion,
     * then groups by parent_id and paginates — producing the same response structure as BM25-only search.
     *
     * The query vector is computed by the caller, which falls back to BM25 when it has none.
     */
    private Map<String, Object> hybridSearch(
            String userQuery,
            float[] queryVector,
            String primaryCore,
            SolrQuery bm25Template,
            int page,
            int rows,
            List<String> cleanIndices
    ) throws SolrServerException, IOException {

        // 1. BM25 sub-query: ungrouped, oversampled, with highlighting
        boolean isPhraseQuery = userQuery != null && userQuery.contains("\"");
        SolrQuery bm25Q = new SolrQuery();
        bm25Q.setQuery(bm25Template.getQuery());
        bm25Q.setParam("df", isPhraseQuery ? CONTENT_EXACT_FIELD_NAME : CONTENT_FIELD_NAME);
        // Match the non-hybrid path and the help text: all terms required by default.
        // (The template's q.op is not carried over by getQuery(), so set it explicitly.)
        bm25Q.setParam("q.op", "AND");
        bm25Q.setRows(200);
        bm25Q.setFields("id", "parent_id", "path", "smbServer", "smbShare", "mtime",
                "page_number", "sheet_name", "meta_Content-Type", "index_source",
                "geolocation_city", "geolocation_dpt", "geolocation_region", "geolocation_country",
                "email_subject", "email_from", "email_date");
        bm25Q.setHighlight(true);
        bm25Q.addHighlightField(CONTENT_FIELD_NAME);
        bm25Q.setHighlightSimplePre("<b>");
        bm25Q.setHighlightSimplePost("</b>");
        bm25Q.setHighlightSnippets(1);
        bm25Q.setHighlightFragsize(180);
        if (isPhraseQuery) {
            bm25Q.setParam("f.content.hl.offsetSource", "ANALYSIS");
        }
        bm25Q.setParam("hl.maxAnalyzedChars", "2000000");
        // Copy filter queries (access control, geo, date, filename, index_source)
        String[] fqs = bm25Template.getFilterQueries();
        if (fqs != null) Arrays.stream(fqs).forEach(bm25Q::addFilterQuery);
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse(userQuery);
        String unitField = isPhraseQuery ? CONTENT_EXACT_FIELD_NAME : CONTENT_FIELD_NAME;
        DocumentLevelMatch.applyExclusions(bm25Q, units, unitField);
        // Copy shards for multi-index
        String shards = bm25Template.get("shards");
        if (shards != null) {
            bm25Q.setParam("shards", shards);
            bm25Q.setParam("distrib", "true");
        }

        // 2. kNN sub-query
        SolrQuery knnQ = new SolrQuery();
        knnQ.setQuery("{!knn f=content_vector topK=100}" + vectorToSolrString(queryVector));
        knnQ.setRows(100);
        knnQ.setFields("id", "parent_id");
        if (fqs != null) Arrays.stream(fqs).forEach(knnQ::addFilterQuery);
        if (shards != null) {
            knnQ.setParam("shards", shards);
            knnQ.setParam("distrib", "true");
        }

        // 3. Execute both queries via POST — kNN vectors (768 floats) exceed GET URI limits.
        QueryResponse bm25Response = new QueryRequest(bm25Q, SolrRequest.METHOD.POST).process(solrClient, primaryCore);
        // Strict AND found no lexical matches → relax to OR so partial matches still feed the
        // fusion (otherwise kNN-only hits get skipped for lack of BM25 metadata and the page is
        // empty). Only fires on an empty BM25 result, so the extra round-trip is negligible.
        // Same q.op-reaches-fq caveat as the BM25 path — see the note there before adding a filter.
        // Document-level AND comes first, as in the BM25 path.
        boolean relaxedToOr = false;
        if (bm25Response.getResults().isEmpty() && !"*:*".equals(bm25Q.getQuery())) {
            bm25Q.setParam("q.op", "OR");
            boolean widen = DocumentLevelMatch.canWidenToDocument(units);
            if (widen) {
                DocumentLevelMatch.applyInclusions(bm25Q, units, unitField);
                bm25Response = new QueryRequest(bm25Q, SolrRequest.METHOD.POST).process(solrClient, primaryCore);
                if (bm25Response.getResults().isEmpty()) DocumentLevelMatch.removeInclusions(bm25Q, units);
            }
            if (!widen || bm25Response.getResults().isEmpty()) {
                bm25Response = new QueryRequest(bm25Q, SolrRequest.METHOD.POST).process(solrClient, primaryCore);
                relaxedToOr = true;
            }
        }
        QueryResponse knnResponse  = new QueryRequest(knnQ,  SolrRequest.METHOD.POST).process(solrClient, primaryCore);

        // 4. Build ranked ID lists
        List<String> bm25Ids = bm25Response.getResults().stream()
                .map(d -> (String) d.getFieldValue("id"))
                .toList();
        List<String> knnIds = knnResponse.getResults().stream()
                .map(d -> (String) d.getFieldValue("id"))
                .toList();

        // 5. RRF merge (k=60 is standard)
        Map<String, Double> rrfScores = reciprocalRankFusion(bm25Ids, knnIds, 60);

        // 6. Build a section lookup map from BM25 results (we have all metadata there)
        Map<String, SolrDocument> sectionById = new LinkedHashMap<>();
        for (SolrDocument doc : bm25Response.getResults()) {
            sectionById.put((String) doc.getFieldValue("id"), doc);
        }
        Map<String, Map<String, List<String>>> highlighting = bm25Response.getHighlighting();

        // 7. Group by parent_id in RRF score order (LinkedHashMap preserves insertion order)
        Map<String, List<SolrDocument>> byParent = new LinkedHashMap<>();
        rrfScores.keySet().forEach(sectionId -> {
            SolrDocument doc = sectionById.get(sectionId);
            if (doc == null) return; // kNN-only result without BM25 metadata — skip for now
            String parentId = (String) doc.getFieldValue("parent_id");
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(doc);
        });

        long totalFound = byParent.size();
        List<String> queryTerms = SnippetSelector.queryTerms(userQuery);

        // 8. Paginate over document groups
        List<Map<String, Object>> resultsList = byParent.entrySet().stream()
                .skip((long) page * rows)
                .limit(rows)
                .map(entry -> {
                    List<SolrDocument> sections = entry.getValue();
                    SolrDocument first = sections.get(0);
                    Map<String, Object> fileResult = new HashMap<>();

                    String fullPath = (String) first.getFieldValue("path");
                    String mimeType = Objects.toString(first.getFirstValue("meta_Content-Type"), "");

                    fileResult.put("id",         first.getFieldValue("id"));
                    fileResult.put("path",        fullPath);
                    fileResult.put("filename",    fullPath != null ? extractFilenameFromPath(fullPath) : "Unknown");
                    fileResult.put("parentId",    first.getFieldValue("parent_id"));
                    fileResult.put("source",      first.getFieldValue("index_source"));
                    fileResult.put("mtime",       first.getFieldValue("mtime"));
                    fileResult.put("isPageApprox",  mimeType != null ? MimeTypeUtils.isTextDocument(mimeType) : true);
                    fileResult.put("isSpreadsheet", mimeType != null ? MimeTypeUtils.isSpreadsheet(mimeType) : false);
                    fileResult.put("docType",       MimeTypeUtils.categorize(mimeType));
                    addEmailHeaders(fileResult, first);

                    List<Map<String, Object>> pageSnippets = new ArrayList<>();
                    List<SolrDocument> candidates = sections.subList(0, Math.min(SNIPPET_CANDIDATES, sections.size()));
                    List<String> snippetTexts = candidates.stream()
                            .map(section -> firstSnippet(highlighting, (String) section.getFieldValue("id")))
                            .toList();
                    for (int sectionIndex : SnippetSelector.select(snippetTexts, queryTerms, SNIPPETS_SHOWN)) {
                        SolrDocument section = candidates.get(sectionIndex);
                        Map<String, Object> snippetObj = new HashMap<>();
                        snippetObj.put("text",      snippetTexts.get(sectionIndex));
                        snippetObj.put("page",      section.getFieldValue("page_number"));
                        snippetObj.put("sheetName", section.getFieldValue("sheet_name"));
                        snippetObj.put("id",        section.getFieldValue("id"));
                        pageSnippets.add(snippetObj);
                    }
                    fileResult.put("snippets", pageSnippets);

                    fileResult.put("geolocationCities",    toGeoList(first.getFieldValues("geolocation_city")));
                    fileResult.put("geolocationDepts",     toGeoList(first.getFieldValues("geolocation_dpt")));
                    fileResult.put("geolocationRegions",   toGeoList(first.getFieldValues("geolocation_region")));
                    fileResult.put("geolocationCountries", toGeoList(first.getFieldValues("geolocation_country")));

                    return fileResult;
                })
                .collect(Collectors.toList());

        // 9. resultsPerIndex — count unique parent_ids per source from the full merged set
        Map<String, Long> resultsPerIndex = new HashMap<>();
        try { for (String name : getAvailableIndices()) resultsPerIndex.put(name, 0L); } catch (Exception ignored) {}
        byParent.values().forEach(sections -> {
            String src = (String) sections.get(0).getFieldValue("index_source");
            if (src != null) resultsPerIndex.merge(src, 1L, Long::sum);
        });

        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("results",         resultsList);
        finalResponse.put("totalResults",    totalFound);
        finalResponse.put("relaxedToOr",     relaxedToOr);
        finalResponse.put("resultsPerIndex", resultsPerIndex);
        finalResponse.put("searchTime",      bm25Response.getQTime() / 1000.0);
        finalResponse.put("currentPage",     page);
        return finalResponse;
    }

    /** First highlighted snippet of a section, or null when it has none. */
    private static String firstSnippet(Map<String, Map<String, List<String>>> highlighting, String sectionId) {
        if (highlighting == null || !highlighting.containsKey(sectionId)) return null;
        List<String> snippets = highlighting.get(sectionId).get(CONTENT_FIELD_NAME);
        return snippets == null || snippets.isEmpty() ? null : snippets.get(0);
    }

    /** Number of unique-file groups in a grouped response, or 0 if grouping returned nothing. */
    private static long nGroupsOf(QueryResponse response) {
        GroupResponse gr = response.getGroupResponse();
        if (gr == null || gr.getValues().isEmpty()) return 0;
        Integer n = gr.getValues().get(0).getNGroups();
        return n == null ? 0 : n;
    }

    /** Reciprocal Rank Fusion: score(d) = Σ 1/(k + rank) across both lists. */
    private static Map<String, Double> reciprocalRankFusion(List<String> list1, List<String> list2, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < list1.size(); i++) scores.merge(list1.get(i), 1.0 / (k + i + 1), Double::sum);
        for (int i = 0; i < list2.size(); i++) scores.merge(list2.get(i), 1.0 / (k + i + 1), Double::sum);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** Formats a float[] as the Solr kNN query vector literal: [f1, f2, ...] */
    private static String vectorToSolrString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }

    /**
     * Find a document in Solr by its ID
     *
     * @param docId The document ID to search for
     * @return IndexedDocument if found, null otherwise
     */
    private IndexedDocument findDocumentInSolr(String docId) {
        try {
            // First, let's try to find the document by querying all available cores
            // This is a simplified approach - in a production system, you might want to
            // use a more sophisticated approach like querying a sharded setup or using the shards parameter
            List<String> availableCores = getAvailableIndices();

            for (String coreName : availableCores) {
                try {
                    SolrQuery query = new SolrQuery();
                    query.setQuery("id:" + ClientUtils.escapeQueryChars(docId));
                    query.setRows(1);

                    QueryResponse response = solrClient.query(coreName, query, SolrRequest.METHOD.POST);

                    if (response.getResults().size() > 0) {
                        // Extract the first (and only) result
                        org.apache.solr.common.SolrDocument solrDoc = response.getResults().get(0);

                        // Extract fields from the Solr document
                        String id = (String) solrDoc.getFieldValue("id");
                        String pathStr = (String) solrDoc.getFieldValue("path");
                        String smbServer = (String) solrDoc.getFieldValue("smbServer");
                        String smbShare = (String) solrDoc.getFieldValue("smbShare");
                        String indexSource = (String) solrDoc.getFieldValue("index_source");
                        Long mtime = (Long) solrDoc.getFieldValue("mtime");

                        // Extract accessTokens and denyTokens from the Solr document
                        Set<String> accessTokens = extractTokenField(solrDoc, "accessTokens");
                        Set<String> denyTokens = extractTokenField(solrDoc, "denyTokens");

                        // Convert path string to Path object
                        Path path = pathStr != null ? Path.of(pathStr) : null;

                        // Create and return the IndexedDocument
                        return new IndexedDocument(
                                id,
                                path,
                                null, // content not needed for download
                                null, // metadata not needed for download
                                accessTokens, // Include accessTokens from Solr
                                denyTokens,   // Include denyTokens from Solr
                                indexSource,
                                mtime,
                                smbServer,
                                smbShare
                        );
                    }
                } catch (Exception e) {
                    // If querying this core fails, continue to the next one
                    log.debug("Failed to query core {}: {}", coreName, e.getMessage());
                    continue;
                }
            }
        } catch (Exception e) {
            log.error("Error querying Solr for document ID: {}", docId, e);
        }
        return null;
    }

    /** A multiValued string field (accessTokens, denyTokens) from a Solr document, or null if absent. */
    private static Set<String> extractTokenField(org.apache.solr.common.SolrDocument solrDoc, String field) {
        Object value = solrDoc.getFieldValue(field);
        if (!(value instanceof Collection)) return null;
        return ((Collection<?>) value).stream().map(Object::toString).collect(Collectors.toSet());
    }

    private IndexedDocument findDocumentByPath(String docPath) {
        try {
            List<String> availableCores = getAvailableIndices();
            for (String coreName : availableCores) {
                try {
                    SolrQuery query = new SolrQuery();
                    query.setQuery("*:*");
                    query.addFilterQuery("{!term f=path}" + docPath);
                    query.setRows(1);

                    QueryResponse response = solrClient.query(coreName, query, SolrRequest.METHOD.POST);
                    if (response.getResults().size() > 0) {
                        org.apache.solr.common.SolrDocument solrDoc = response.getResults().get(0);
                        String id = (String) solrDoc.getFieldValue("id");
                        String pathStr = (String) solrDoc.getFieldValue("path");
                        String smbServer = (String) solrDoc.getFieldValue("smbServer");
                        String smbShare = (String) solrDoc.getFieldValue("smbShare");
                        String indexSource = (String) solrDoc.getFieldValue("index_source");
                        Long mtime = (Long) solrDoc.getFieldValue("mtime");
                        Set<String> accessTokens = extractTokenField(solrDoc, "accessTokens");
                        Set<String> denyTokens = extractTokenField(solrDoc, "denyTokens");
                        Path path = pathStr != null ? Path.of(pathStr) : null;
                        return new IndexedDocument(id, path, null, null, accessTokens, denyTokens, indexSource, mtime, smbServer, smbShare);
                    }
                } catch (Exception e) {
                    log.debug("Failed to query core {} by path: {}", coreName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error querying Solr for document path: {}", docPath, e);
        }
        return null;
    }


}