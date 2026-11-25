package eu.intracherche.intracherche.common;

public class MimeTypeUtils {

    /**
     * Checks if the given MIME type represents a presentation document.
     *
     * @param mimeType the MIME type to check
     * @return true if the MIME type represents a presentation document, false otherwise
     */
    public static boolean isPresentation(String mimeType) {
        return mimeType != null && (
                mimeType.contains("presentationml") || // .pptx
                        mimeType.contains("powerpoint") ||     // .ppt
                        mimeType.contains("opendocument.presentation") // .odp
        );
    }

    /**
     * Checks if the given MIME type represents a text document.
     *
     * @param mimeType the MIME type to check
     * @return true if the MIME type represents a text document, false otherwise
     */
    public static boolean isTextDocument(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.contains("wordprocessingml") || // .docx
                mimeType.contains("msword") ||            // .doc
                mimeType.contains("opendocument.text");   // .odt
    }

    public static boolean isSpreadsheet(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.contains("spreadsheet") ||
                mimeType.contains("excel") ||
                mimeType.contains("officedocument.spreadsheetml");
    }

    /**
     * Checks if the given MIME type represents an email message.
     *
     * <p>Covers RFC822 ({@code .eml}) and Outlook ({@code .msg}, plus TNEF
     * {@code winmail.dat} attachments), which Tika parses into the same metadata keys.</p>
     *
     * @param mimeType the MIME type to check
     * @return true if the MIME type represents an email message, false otherwise
     */
    public static boolean isEmail(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        return type.contains("rfc822") ||       // .eml
                type.contains("outlook") ||      // .msg
                type.contains("ms-tnef");        // winmail.dat
    }

    /**
     * Sorts a MIME type into one of the coarse families the search results show an icon for.
     *
     * <p>The MIME type comes from Tika, so it is right even when the extension lies
     * (or is missing, which happens a lot on SMB shares).
     *
     * @param mimeType the MIME type to classify, may be null or empty
     * @return one of "pdf", "doc", "sheet", "slides", "image", "mail", "text", "other"
     */
    public static String categorize(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) return "other";

        String type = mimeType.toLowerCase();

        if (type.contains("pdf")) return "pdf";
        if (isPresentation(type)) return "slides";
        if (isSpreadsheet(type)) return "sheet";
        if (isTextDocument(type) || type.contains("rtf")) return "doc";
        if (type.startsWith("image/")) return "image";
        if (isEmail(type)) return "mail";
        if (type.startsWith("text/")) return "text";

        return "other";
    }
}
