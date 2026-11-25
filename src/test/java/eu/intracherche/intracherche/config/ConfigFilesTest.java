package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {

    @TempDir Path dir;

    private static String perms(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void replacingKeepsTheFilesPermissionsOnTheNewFileAndItsBackup() throws Exception {
        Path f = dir.resolve("indexes.yaml");
        Files.writeString(f, "old");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r-----"));
        String backup = ConfigFiles.replace(f, "new");
        assertEquals("new", Files.readString(f));
        assertEquals("old", Files.readString(Path.of(backup)));
        assertEquals("rw-r-----", perms(f));
        assertEquals("rw-r-----", perms(Path.of(backup)));
        assertFalse(Files.exists(dir.resolve("indexes.yaml.tmp")));
    }

    @Test
    void aTighterFileStaysTighter() throws Exception {
        Path f = dir.resolve("a.yaml");
        Files.writeString(f, "old");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        ConfigFiles.replace(f, "new");
        assertEquals("rw-------", perms(f));
    }

    @Test
    void aNewFileIsNeverWorldReadable() throws Exception {
        Path f = dir.resolve("jvm.conf");
        assertNull(ConfigFiles.replace(f, "IC_HEAP_MB=2048\n"));
        assertEquals("rw-r-----", perms(f));
    }

    @Test
    void onlyTheMostRecentBackupsAreKept() throws Exception {
        Path f = dir.resolve("x.yaml");
        Files.writeString(f, "v");
        for (int i = 0; i < ConfigFiles.BACKUPS_KEPT + 4; i++) { ConfigFiles.replace(f, "v" + i); Thread.sleep(3); }
        try (var s = Files.list(dir)) {
            assertEquals(ConfigFiles.BACKUPS_KEPT, s.filter(p -> p.getFileName().toString().contains(".bak-")).count());
        }
    }

    @Test
    void aDirectoryThatTakesNoNewFilesIsReported() throws Exception {
        Path ro = Files.createDirectory(dir.resolve("ro"));
        Path f = ro.resolve("application.yaml");
        Files.writeString(f, "x");
        ro.toFile().setWritable(false);
        try {
            if (Files.isWritable(ro)) return;   // running as root: nothing to prove
            assertFalse(ConfigFiles.canReplace(f));
            assertTrue(ConfigFiles.cannotReplaceReason(f).contains("INSTALL.md"));
        } finally {
            ro.toFile().setWritable(true);
        }
    }
}
