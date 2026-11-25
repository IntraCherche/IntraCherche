package eu.intracherche.intracherche.discovery;

import eu.intracherche.intracherche.common.ContentSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalContentSource implements ContentSource {
    private final Path path;

    public LocalContentSource(Path path) { this.path = path; }

    @Override
    public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }

    @Override
    public String getIdentifier() { return path.toString(); }

    @Override
    public long getSize() throws IOException { return Files.size(path); }

    @Override
    public void close() { /* Nothing to close for local paths until stream is opened */ }
}