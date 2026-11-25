package eu.intracherche.intracherche.indexing.parser;

import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class PresentationExtractor {

    public static List<IndexedSection> extractSlides(byte[] data, String mimeType) throws Exception {
        List<String> rawSlides = mimeType.contains("opendocument")
                ? extractOdpSlides(data)
                : extractPptSlides(data);

        List<IndexedSection> sections = new ArrayList<>();
        for (int i = 0; i < rawSlides.size(); i++) {
            String text = rawSlides.get(i);
            if (!text.isBlank()) {
                sections.add(new IndexedSection("Slide " + (i + 1), text));
            }
        }
        return sections;
    }

    private static List<String> extractPptSlides(byte[] data) throws Exception {
        List<String> slides = new ArrayList<>();
        // SlideShowFactory handles both .ppt (HSLF) and .pptx (XSLF)
        try (SlideShow<?, ?> screenshot = SlideShowFactory.create(new ByteArrayInputStream(data))) {
            for (Slide<?, ?> slide : screenshot.getSlides()) {
                StringBuilder slideText = new StringBuilder();
                slide.getShapes().forEach(shape -> {
                    if (shape instanceof org.apache.poi.sl.usermodel.TextShape<?,?> ts) {
                        slideText.append(ts.getText()).append(" ");
                    }
                });
                slides.add(slideText.toString().trim());
            }
        }
        return slides;
    }

    private static List<String> extractOdpSlides(byte[] data) throws Exception {
        // ODP is trickier with POI; use Tika's XHTML output and split by 'slide' divs
        List<String> slides = new ArrayList<>();
        CustomSlideHandler handler = new CustomSlideHandler();

        org.apache.tika.parser.AutoDetectParser parser = new org.apache.tika.parser.AutoDetectParser();
        org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();

        try (var is = new ByteArrayInputStream(data)) {
            parser.parse(is, handler, metadata);
        }

        return handler.getSlides();
    }
}
