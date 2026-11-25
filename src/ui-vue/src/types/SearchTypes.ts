export interface Snippet {
  text: string;
  page: number;
  /** Name of the  sheet (only relevant to spreadsheet) */
  sheetName: string;
  id: string;
}
/** The file families the result line has an icon for; anything unrecognised is 'other'. */
export type DocType =
  | 'pdf'
  | 'doc'
  | 'sheet'
  | 'slides'
  | 'image'
  | 'mail'
  | 'text'
  | 'other';

export interface SolrDocument {
  /** The unique ID of the specific page (e.g., fileId_p5) */
  id: string;

  /** The original ID of the file, used for grouping and downloading */
  parentId: string;

  /** The physical path or SMB path to the file */
  path: string;

  /** The filename extracted from the path */
  filename: string;

  /** The highlighted text fragment from this specific page */
  snippets: Snippet[];

  /** The specific page number within the document */
  pageNumber: number;

  /** Human-readable location (e.g., "Page 5" or "Appendix A") */
  locationLabel: string;


  /** Whether the page number is accurate (PDF, slideshows) or not (text) */
  isPageApprox: boolean;

  /** Whether the document is a spreadsheet */
  isSpreadsheet: boolean;

  /** Coarse file family used to pick the type icon shown on the result line */
  docType?: DocType;

  /**
   * Email headers. Present only on 'mail' hits (.eml / .msg), and only for messages
   * that actually carried the header — test with `?.` rather than assuming they are set.
   */
  /** Subject line; shown as the result title, since an .eml filename is rarely meaningful */
  emailSubject?: string;
  /** Sender(s), as displayed in the message ("Marie Dupont <marie.dupont@example.fr>") */
  emailFrom?: string[];
  /** Sent date, epoch milliseconds — same shape as mtime */
  emailDate?: number | null;




  /** Modification timestamp in milliseconds */
  mtime?: number | null;


  /** The name of the Solr core/index this result came from */
  source: string;

  /** NER-extracted cities mentioned in the document */
  geolocationCities?: string[];
  /** NER-extracted departments mentioned in the document */
  geolocationDepts?: string[];
  /** NER-extracted regions mentioned in the document */
  geolocationRegions?: string[];
  /** NER-extracted countries mentioned in the document */
  geolocationCountries?: string[];

  /** Catch-all for messy 'meta_*' fields returned by Tika */
  metadata?: Record<string, any>;
}


/** Interface for the structured response from the Spring Boot search endpoint */
export interface SearchResponse {
  results: SolrDocument[];
  totalResults: number;
  searchTime: number;
  currentPage: number;
  /** Map showing document counts across different indices for the sidebar facets */
  resultsPerIndex: Record<string, number>;
  /** True when a strict AND search found nothing and the server relaxed it to OR. */
  relaxedToOr?: boolean;
  /**
   * A spelling correction of the query, present only when the words typed found nothing
   * together and the corrected ones do, among the documents this user may read.
   */
  suggestion?: string;
  /**
   * Present when the search should have been hybrid (keywords + meaning) but ran on keywords
   * only: HIGH_LOAD when the server was too busy to embed the query in time,
   * SEMANTIC_UNAVAILABLE when the embedding service failed.
   */
  degraded?: 'HIGH_LOAD' | 'SEMANTIC_UNAVAILABLE';
}

/** Fixed place names the NER service can emit — the choices for the geo filters. */
export interface Gazetteer {
  countries: string[];
  regions: string[];
  departments: { name: string; region: string }[];
}

/** One choice in a GeoCombobox; the hint is shown greyed next to the value. */
export interface GeoOption {
  value: string;
  hint?: string;
}