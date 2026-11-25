package eu.intracherche.intracherche.indexing.parser;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.TextPosition;

import java.util.List;
import java.util.Map;

/**
 * Restores the letters a PDF font maps to Unicode's private-use area.
 *
 * <p>Some fonts give their ligatures a private-use code point in the PDF's own Unicode table, so
 * text extraction returns that code point instead of the letters. Linux Libertine, the font of
 * Wikipedia's PDF export, does it for "tt": "attentats" came out as "aentats", which Solr
 * tokenizes as "a" + "entats". Measured on the demo Wikipedia core (Sept. 2026), a search for
 * "cette" found 8 pages and missed 45; "attaque" found 54 and missed 20.
 *
 * <p>The code points only mean something within their font — another font puts different glyphs
 * there — so a replacement applies only to text drawn in the font that defines it. PDFBox cannot
 * name these glyphs instead: Libertine is embedded as a CID font, which carries no glyph names.
 * Code points not listed are left as they are.
 */
final class PrivateUseGlyphs {

    /** Linux Libertine (LinLibertineO, LinLibertineOI…): read from the demo corpus's contexts. */
    private static final Map<Integer, String> LIBERTINE = Map.of(
            0xE03C, "tt",   // a·entats, ce·e, me·re
            0xE039, "ft",   // Microso· France, Wissenscha·
            0xE048, "Qu",   // ·ébec, Saint-·entin, ·elques
            0xE049, "Th",   // ·alys
            0xE0E0, "f");   // the short f before "è" or ")": pré·ère, mammi·ères, constati·)

    /** Pictogram fonts put every glyph at U+F0xx: bullets and arrows, never a letter. */
    private static final List<String> PICTOGRAM_FONTS = List.of("wingdings", "webdings", "symbol");

    private PrivateUseGlyphs() {}

    /** {@code text} as drawn by {@code positions}, with the private-use glyphs of known fonts restored. */
    static String restore(String text, List<TextPosition> positions) {
        if (text == null || text.codePoints().noneMatch(PrivateUseGlyphs::isPrivateUse)) return text;
        String restored = text;
        for (TextPosition position : positions) {
            String unicode = position.getUnicode();
            if (unicode == null || unicode.codePoints().noneMatch(PrivateUseGlyphs::isPrivateUse)) continue;
            String replacement = replacement(fontName(position.getFont()), unicode.codePointAt(0));
            if (replacement != null) restored = restored.replace(unicode, replacement);
        }
        return restored;
    }

    /** What a private-use code point stands for in this font, or null when it is not known. */
    static String replacement(String fontName, int codePoint) {
        if (fontName == null) return null;
        String font = fontName.toLowerCase();
        if (font.contains("libertine")) return LIBERTINE.get(codePoint);
        if (codePoint >= 0xF000 && codePoint <= 0xF0FF && PICTOGRAM_FONTS.stream().anyMatch(font::contains)) return " ";
        return null;
    }

    private static String fontName(PDFont font) {
        return font == null ? null : font.getName();
    }

    private static boolean isPrivateUse(int codePoint) {
        return Character.getType(codePoint) == Character.PRIVATE_USE;
    }
}
