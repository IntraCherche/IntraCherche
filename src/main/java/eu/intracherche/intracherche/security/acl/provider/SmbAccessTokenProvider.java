package eu.intracherche.intracherche.security.acl.provider;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.SecurityDescriptor;
import com.hierynomus.msdtyp.SecurityInformation;
import com.hierynomus.msdtyp.ace.ACE;
import com.hierynomus.msdtyp.ace.AceType;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import eu.intracherche.intracherche.web.SearchAccessControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class SmbAccessTokenProvider implements AccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(SmbAccessTokenProvider.class);

    private final DiskShare share;

    public SmbAccessTokenProvider(DiskShare share) {
        this.share = share;
    }

    /**
     * Convenience method for the Indexer (manages its own file lifecycle)
     */
    @Override
    public Set<String> extractAccessTokens(Path relativePath) {
        try (com.hierynomus.smbj.share.File smbFile = share.openFile(
                relativePath.toString(),
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.READ_CONTROL),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null)) {

            return extractAccessTokens(smbFile); // Delegate to the overloaded method
        }
    }

    /**
     * Convenience method for the Indexer (manages its own file lifecycle)
     */
    @Override
    public Set<String> extractDenyTokens(Path relativePath) throws IOException {
        try (com.hierynomus.smbj.share.File smbFile = share.openFile(
                relativePath.toString(),
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.READ_CONTROL),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null)) {

            return extractDenyTokens(smbFile);
        }
    }

    /**
     * Core logic reused by the Controller (uses an existing open file handle)
     */
    public Set<String> extractAccessTokens(com.hierynomus.smbj.share.File smbFile) {
        return extractTokens(smbFile, AceType.ACCESS_ALLOWED_ACE_TYPE);
    }

    /**
     * SIDs with an explicit Deny ACE, reused by the Controller's live re-check. Must always be
     * checked alongside {@link #extractAccessTokens}: a SID here overrides that same SID matching
     * the allow set.
     */
    public Set<String> extractDenyTokens(com.hierynomus.smbj.share.File smbFile) {
        return extractTokens(smbFile, AceType.ACCESS_DENIED_ACE_TYPE);
    }

    /**
     * Same filtering as SmbjScanner.processEntry (the discovery-time computation this live
     * re-check must agree with): skip inherit-only ACEs (they say nothing about this object
     * itself), and keep only ACEs whose mask actually grants/denies read. Without this, an
     * unrelated Deny (e.g. "Deny Delete", "Deny Write") made every holder of that SID fail
     * this check, even though it says nothing about read access.
     */
    private static Set<String> extractTokens(com.hierynomus.smbj.share.File smbFile, AceType wanted) {
        Set<String> tokens = new HashSet<>();

        SecurityDescriptor sd = smbFile.getSecurityInformation(
                EnumSet.of(SecurityInformation.DACL_SECURITY_INFORMATION)
        );

        if (sd.getDacl() != null) {
            for (ACE ace : sd.getDacl().getAces()) {
                if (ace.getAceHeader().getAceFlags().contains(com.hierynomus.msdtyp.ace.AceFlags.INHERIT_ONLY_ACE)) {
                    continue;
                }
                if (ace.getSid() == null || ace.getAceHeader().getAceType() != wanted) {
                    continue;
                }
                long mask = ace.getAccessMask();
                boolean readRelated = (mask & AccessMask.GENERIC_READ.getValue()) != 0 ||
                        (mask & AccessMask.FILE_READ_DATA.getValue()) != 0 ||
                        (mask & AccessMask.FILE_EXECUTE.getValue()) != 0;
                if (!readRelated) continue;
                tokens.add(ace.getSid().toString());
            }
        }
        return tokens;
    }
}


