package eu.intracherche.intracherche.discovery;

import com.hierynomus.msdtyp.*;
import com.hierynomus.msdtyp.ace.ACE;
import com.hierynomus.msdtyp.ace.AceFlags;
import com.hierynomus.msdtyp.ace.AceType;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msdtyp.SecurityDescriptor;
import com.hierynomus.msdtyp.SecurityInformation;
import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public class SmbjScanner extends AbstractScanner {

    private static final Logger log = LoggerFactory.getLogger(SmbjScanner.class);
    private IndexedDirConfig config; // Store the config for the duration of the scan
    private SmbClientManager smbClientManager;
    private final Set<String> allowedExtensions;

    public SmbjScanner(DiscoveryQueue discoveryQueue, SmbClientManager smbClientManager, Set<String> allowedExtensions) {
        super(discoveryQueue);
        this.smbClientManager = smbClientManager;
        this.allowedExtensions = allowedExtensions;
    }


    @Override
    public void scan(IndexedDirConfig config) throws Exception {
        this.config = config;

        // We use the share name from the config to "borrow" a share from the manager
        // The manager already has the credentials because we registered them in the Orchestrator

        /** We use try-with-resources on the DiskShare.
         * We are doing one long, continuous operation on a single share.
         * When recursiveScan finally finishes (even if it takes hours),
         * closing the DiskShare tells the Windows server you are done with
         * that specific "Tree Connect."
         *
         * Crucially: Closing the DiskShare does not close the Session or
         * the Connection inside the SmbClientManager.
         * It just cleans up the specific "folder mount" on the server.
         */
        try (DiskShare share = smbClientManager.getShare(config.getSmbServer(), config.getSmbShare())) {
            log.info("Starting recursive scan for share: {}", config.getSmbShare());
            recursiveScan(share, "", config);
        } catch (Exception e) {
            log.error("Failed to connect to SMB share {}: {}", config.getSmbShare(), e.getMessage());
            throw e;
        }

    }

    private void recursiveScan(DiskShare share, String folderPath, IndexedDirConfig config) throws InterruptedException {
        // 1. List the contents of the current directory
        List<FileIdBothDirectoryInformation> list;
        try {
            list = share.list(folderPath);
        } catch (SMBApiException e) {
            if (e.getStatus() == NtStatus.STATUS_ACCESS_DENIED) {
                log.warn("Access Denied for folder: {}. Skipping contents.", folderPath);
                return; // Back out of this specific folder and continue others
            }
            throw e; // Re-throw if it's a network error or something critical
        }

        for (FileIdBothDirectoryInformation info : list) {
            String name = info.getFileName();
            if (name.equals(".") || name.equals("..")) continue;

            String fullPath = folderPath.isEmpty() ? name : folderPath + "\\" + name;

            // Check the bitmask
            long attributes = info.getFileAttributes();
            boolean isDirectory = (attributes & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;

            if (isDirectory) {
                try {
                    log.debug("Descending into directory: {}", fullPath);
                    // 1. Process the folder itself (Save record to DB with folder-level ACLs)
                    // If this fails (Access Denied), we jump to the catch block.
                    processEntry(share, fullPath, info);

                    // 2. Descend into the folder to find its children
                    // If this fails (Access Denied), we jump to the catch block.
                    recursiveScan(share, fullPath, config);
                } catch (SMBApiException e) {
                    if (e.getStatus() == NtStatus.STATUS_ACCESS_DENIED) {
                        log.warn("Access Denied for directory {}. Skipping this branch.", fullPath);
                    } else {
                        log.error("SMB Error at {}: {}", fullPath, e.getMessage());
                    }
                    // Do NOT rethrow. The loop in the caller will move to the next file/folder.
                } catch (Exception e) {
                    log.error("Unexpected error at {}: {}", fullPath, e.getMessage());
                }
            } else {
                try {
                    // Process the individual file
                    processEntry(share, fullPath, info);
                } catch (Exception e) {
                    log.warn("Skipping file {} due to error: {}", fullPath, e.getMessage());
                }
            }
        }
    }

    private void processEntry(DiskShare share, String relativePath, FileIdBothDirectoryInformation info) throws InterruptedException {
        Set<String> allowSids = new HashSet<>();
        Set<String> denySids = new HashSet<>();

        long attributes = info.getFileAttributes();
        boolean isDirectory = (attributes & com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;

        // Use DiskEntry - it is the common interface for File and Directory in smbj
        try (DiskEntry entry = isDirectory
                ? share.openDirectory(relativePath, EnumSet.of(AccessMask.READ_CONTROL), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                : share.openFile(relativePath, EnumSet.of(AccessMask.READ_CONTROL), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {

            // The method name is getSecurityDescriptor in many smbj versions
            SecurityDescriptor sd = entry.getSecurityInformation(EnumSet.of(SecurityInformation.DACL_SECURITY_INFORMATION));

            if (sd != null && sd.getDacl() != null) {
                // In mssdk, the list returns ACE objects
                for (ACE ace : sd.getDacl().getAces()) {

                    // USE THE SET DIRECTLY
                    Set<com.hierynomus.msdtyp.ace.AceFlags> flags = ace.getAceHeader().getAceFlags();
                    if (flags.contains(com.hierynomus.msdtyp.ace.AceFlags.INHERIT_ONLY_ACE)) {
                        continue;
                    }

                    if (ace.getSid() == null) continue;
                    String sid = ace.getSid().toString();

                    // getAccessMask() returns long
                    long mask = ace.getAccessMask();

                    boolean canRead = (mask & AccessMask.GENERIC_READ.getValue()) != 0 ||
                            (mask & AccessMask.FILE_READ_DATA.getValue()) != 0 ||
                            (mask & AccessMask.FILE_EXECUTE.getValue()) != 0;

                    if (!canRead) continue;

                    AceType type = ace.getAceHeader().getAceType();
                    if (type == AceType.ACCESS_ALLOWED_ACE_TYPE) {
                        allowSids.add(sid);
                    } else if (type == AceType.ACCESS_DENIED_ACE_TYPE) {
                        denySids.add(sid);
                    }
                }
            }
            /**
             * We catch separately ACCESS DENIED ERRORS to prevent the log from being flooded
             * with stack traces on Exceptions that are only due to (lack of) permissions
             */
        } catch (com.hierynomus.mssmb2.SMBApiException e) {
            if (e.getStatus() == com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED) {
                log.warn("Access Denied for {}. Skipping ACL metadata for this {}.",
                        relativePath, isDirectory ? "folder" : "file");
            } else if (e.getStatus() == com.hierynomus.mserref.NtStatus.STATUS_SHARING_VIOLATION) {
                log.warn("File {} is currently locked by another process (Sharing Violation).", relativePath);
            } else {
                log.error("SMB API Error for {}: {}", relativePath, e.getStatus());
            }
        } catch (Exception e) {
            log.error("General error processing {}: {}", relativePath, e.getMessage(), e);
        }

            // Only emit events for files with an allowed extension.
            if (!isDirectory) {
                String ext = getFileExtension(relativePath);
                if (!allowedExtensions.contains(ext.toLowerCase())) {
                    log.debug("Skipping file with non-allowed extension: {}", relativePath);
                    return;
                }
                List<String> sortedAllow = new ArrayList<>(allowSids);
                List<String> sortedDeny = new ArrayList<>(denySids);
                Collections.sort(sortedAllow);
                Collections.sort(sortedDeny);

                String aclHash = sha256(String.join(",", sortedAllow) + "|" + String.join(",", sortedDeny));
                
                // Log the access tokens (SIDs) found for this file, similar to DirectoryScanner
                log.debug("Discovered file with {} allow SIDs and {} deny SIDs: {} ({} bytes, mtime: {})", 
                        sortedAllow.size(), sortedDeny.size(), relativePath, info.getEndOfFile(), 
                        info.getChangeTime().toInstant());

                FileDiscoveredEvent event = new FileDiscoveredEvent(
                        Paths.get(relativePath),
                        config.getSmbShare(),
                        config.getSmbServer(),  // Use the SMB server name as smbHost
                        info.getEndOfFile(),
                        info.getChangeTime().toInstant(),
                        Instant.now(),
                        new HashSet<>(sortedAllow),
                        new HashSet<>(sortedDeny),
                        aclHash,
                        true
                );

                discoveryQueue.publish(event);
            }
    }

    private static String getFileExtension(String path) {
        String fileName = path.contains("\\") ? path.substring(path.lastIndexOf('\\') + 1) : path;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This is already done in code manually
     * @param allow
     * @param deny
     * @return
     */
    private String generateTokenHash(Set<String> allow, Set<String> deny) {
        List<String> allTokens = new ArrayList<>();
        allTokens.addAll(allow);
        allTokens.addAll(deny);
        // Sorting is critical so the hash is deterministic
        Collections.sort(allTokens);
        return Integer.toHexString(allTokens.hashCode());
    }
}
