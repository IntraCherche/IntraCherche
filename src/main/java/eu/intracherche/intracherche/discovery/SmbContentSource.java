package eu.intracherche.intracherche.discovery;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.common.ContentSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;

public class SmbContentSource implements ContentSource {
    private final DiskShare share;
    private final com.hierynomus.smbj.share.File smbFile;
    private final String path;

    public SmbContentSource(DiskShare share, String path) {
        this.share = share;
        this.path = path;
        this.smbFile = share.openFile(path, EnumSet.of(AccessMask.FILE_READ_DATA),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
    }

    @Override
    public InputStream getInputStream() { return smbFile.getInputStream(); }

    @Override
    public String getIdentifier() { return path; }

    @Override
    public long getSize() { return smbFile.getFileInformation().getStandardInformation().getEndOfFile(); }

    @Override
    public void close() throws IOException {
        // Close only the file handle; the DiskShare lifecycle is managed by
        // SmbClientManager and closed at end of cycle via closeSessions().
        // Closing the DiskShare here marks its shared TreeConnect as closed and
        // causes "DiskShare has already been closed" in parallel indexing threads.
        smbFile.close();
    }
}
