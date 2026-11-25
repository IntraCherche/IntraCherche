package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * How the administration panel replaces a configuration file: a backup beside it, the new content
 * written to a temporary file and moved over the old one, with the old file's permissions.
 *
 * <p>The files hold secrets (SMB and LDAP passwords), so no copy of them, the backup and the
 * temporary file included, is ever created with wider permissions than the original: the file is
 * created with them, not tightened afterwards. A new file gets {@code rw-r-----}.
 *
 * <p>Replacing is a rename, so what is needed is write access to the <em>directory</em>, not to the
 * file itself: that is what the installer grants the service user on /etc/intracherche.
 */
final class ConfigFiles {

    private static final Logger log = LoggerFactory.getLogger(ConfigFiles.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Set<PosixFilePermission> DEFAULT_PERMS = PosixFilePermissions.fromString("rw-r-----");
    static final int BACKUPS_KEPT = 10;

    private ConfigFiles() {}

    /** True when {@link #replace} can work: the directory takes new files, and an existing file can be read. */
    static boolean canReplace(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        return dir != null && Files.isWritable(dir) && (!Files.exists(file) || Files.isReadable(file));
    }

    /** Why {@link #canReplace} is false, in the administrator's words. */
    static String cannotReplaceReason(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        return "Le compte du service n'a pas le droit d'écrire dans le dossier " + dir
                + " : voir « Droits d'écriture » dans INSTALL.md.";
    }

    /** Writes {@code content} over {@code file}; returns the backup's path, or null when there was no file. */
    static String replace(Path file, String content) throws IOException {
        Set<PosixFilePermission> perms = permissionsOf(file);
        String backup = null;
        if (Files.isRegularFile(file)) {
            Path b = file.resolveSibling(file.getFileName() + ".bak-" + LocalDateTime.now().format(STAMP));
            create(b, perms);
            Files.copy(file, b, StandardCopyOption.REPLACE_EXISTING);
            backup = b.toString();
            prune(file);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        create(tmp, perms);
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    private static Set<PosixFilePermission> permissionsOf(Path file) {
        try {
            if (Files.isRegularFile(file)) return Files.getPosixFilePermissions(file);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Permissions of {} not readable: {}", file, e.getMessage());
        }
        return DEFAULT_PERMS;
    }

    private static void create(Path file, Set<PosixFilePermission> perms) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(perms));
        } catch (UnsupportedOperationException e) {   // not a POSIX file system (development on Windows)
            Files.createFile(file);
        }
    }

    private static void prune(Path file) {
        String prefix = file.getFileName() + ".bak-";
        try (Stream<Path> s = Files.list(file.toAbsolutePath().getParent())) {
            List<Path> old = s.filter(p -> p.getFileName().toString().startsWith(prefix)).sorted().toList();
            for (int i = 0; i < old.size() - BACKUPS_KEPT; i++) Files.deleteIfExists(old.get(i));
        } catch (IOException e) {
            log.warn("Could not prune the backups of {}: {}", file, e.getMessage());
        }
    }
}
