package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.List;

public class CustomSlideHandler extends BodyContentHandler {
    private final List<String> slides = new ArrayList<>();
    private StringBuilder currentSlide = new StringBuilder();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        // Tika's ODFParser wraps slides in <div> tags with specific classes
        if ("div".equalsIgnoreCase(qName) && "slide".equalsIgnoreCase(atts.getValue("class"))) {
            flushSlide();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        currentSlide.append(ch, start, length);
    }

    @Override
    public void endDocument() throws SAXException {
        flushSlide();
    }

    private void flushSlide() {
        String text = currentSlide.toString().trim();
        if (!text.isEmpty()) {
            slides.add(text);
        }
        currentSlide = new StringBuilder();
    }

    public List<String> getSlides() { return slides; }
}