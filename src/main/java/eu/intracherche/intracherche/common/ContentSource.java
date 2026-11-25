package eu.intracherche.intracherche.common;

import java.io.IOException;
import java.io.InputStream;

public interface ContentSource extends AutoCloseable {
    InputStream getInputStream() throws IOException;
    String getIdentifier(); // The relative path or URI
    long getSize() throws IOException;
}
