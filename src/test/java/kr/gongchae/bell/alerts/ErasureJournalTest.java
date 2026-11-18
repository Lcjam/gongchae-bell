package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErasureJournalTest {
    @TempDir Path dir;

    /** The operator's one-time preparation: an existing empty file. */
    Path prepared() throws IOException { return Files.createFile(dir.resolve("erasure.log")); }

    @Test
    void concurrentAppendsKeepEveryLineWholeAndReadBack() throws Exception {
        var journal = new ErasureJournal(prepared());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) ids.add(UUID.randomUUID());
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writes = new ArrayList<>();
            for (UUID id : ids) writes.add(pool.submit(() -> { journal.append(id, Instant.parse("2026-11-16T00:00:00Z")); return null; }));
            for (Future<?> write : writes) write.get();
        }
        assertThat(new HashSet<>(journal.readStrict())).isEqualTo(new HashSet<>(ids));
        List<String> lines = Files.readAllLines(dir.resolve("erasure.log"), UTF_8);
        assertThat(lines).hasSize(200).allMatch(line -> line.matches("[0-9a-f-]{36}\t2026-11-16T00:00:00Z"));
    }

    @Test
    void tornOrMalformedJournalFailsClosedAndANewLineNeverJoinsATornOne() throws Exception {
        Path file = prepared();
        var journal = new ErasureJournal(file);
        assertThat(journal.readStrict()).as("a prepared empty journal is a valid empty journal").isEmpty();
        UUID first = UUID.randomUUID();
        journal.append(first, Instant.parse("2026-11-16T00:00:00Z"));
        Files.writeString(file, "0b4c", UTF_8, StandardOpenOption.APPEND);  // a crash mid-write
        assertThatThrownBy(journal::readStrict).isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_TORN_LAST_LINE");
        UUID next = UUID.randomUUID();
        journal.append(next, Instant.parse("2026-11-16T00:01:00Z"));
        List<String> lines = Files.readAllLines(file, UTF_8);
        assertThat(lines).containsExactly(first + "\t2026-11-16T00:00:00Z", "0b4c", next + "\t2026-11-16T00:01:00Z");
        assertThatThrownBy(journal::readStrict).hasMessage("ERASURE_LOG_BAD_LINE_2");

        Files.writeString(file, first.toString().toUpperCase() + "\t2026-11-16T00:00:00Z\n", UTF_8);
        assertThatThrownBy(journal::readStrict).hasMessage("ERASURE_LOG_BAD_LINE_1");
        Files.writeString(file, first + "\tyesterday\n", UTF_8);
        assertThatThrownBy(journal::readStrict).hasMessage("ERASURE_LOG_BAD_LINE_1");
    }

    @Test
    void aMissingFileOrAWrongPathIsNeverAnEmptyJournal() throws Exception {
        assertThatThrownBy(() -> new ErasureJournal(dir.resolve("missing.log")).readStrict())
                .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_MISSING");
        assertThatThrownBy(() -> new ErasureJournal(dir).readStrict()).as("a directory, e.g. a bind mount Docker made up")
                .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_MISSING");
        assertThatThrownBy(() -> new ErasureJournal(dir.resolve("missing.log")).append(UUID.randomUUID(), Instant.now()))
                .as("the app never creates the journal").isInstanceOf(NoSuchFileException.class);
        assertThat(dir.resolve("missing.log")).doesNotExist();

        AlertsProperties base = AlertsSecurityTest.enabled();
        AlertsProperties missing = new AlertsProperties(true, base.tokenKey(), base.hmacKey(), base.webhookKey(), base.webhookKeyId(),
                dir.resolve("missing.log").toString(), base.mail(), base.privacy());
        assertThatThrownBy(() -> new ErasureJournal(missing)).as("enabled alerts refuse to start without the prepared file")
                .hasMessage("ALERTS_ERASURE_LOG_MISSING");
        AlertsProperties ready = new AlertsProperties(true, base.tokenKey(), base.hmacKey(), base.webhookKey(), base.webhookKeyId(),
                prepared().toString(), base.mail(), base.privacy());
        assertThat(new ErasureJournal(ready).readStrict()).isEmpty();
        assertThat(new ErasureJournal(new AlertsProperties(false, null, null, null, null, null, null, null))).as("off: not checked").isNotNull();
    }

    @Test
    void everyAppendForcesTheDirectoryWhileStillHoldingTheFileLockAndAFailureReachesTheCaller() throws Exception {
        Path file = prepared();
        List<Boolean> heldDuringSync = new ArrayList<>();
        List<FileChannel> probes = new ArrayList<>();
        var journal = new ErasureJournal(file) {
            @Override void syncDirectory(Path directory) throws IOException {
                assertThat(directory).isEqualTo(file.toAbsolutePath().getParent());
                FileChannel probe = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                probes.add(probe);  // closed only after the append: closing it could drop this JVM's POSIX lock early
                try { probe.tryLock().release(); heldDuringSync.add(false); }
                catch (OverlappingFileLockException held) { heldDuringSync.add(true); }
            }
        };
        try {
            journal.append(UUID.randomUUID(), Instant.parse("2026-11-16T00:00:00Z"));
            journal.append(UUID.randomUUID(), Instant.parse("2026-11-16T00:00:01Z"));
        } finally {
            for (FileChannel probe : probes) probe.close();
        }
        assertThat(heldDuringSync).as("not only for the first writer, and before the lock is released").containsExactly(true, true);

        var failing = new ErasureJournal(file) {
            @Override void syncDirectory(Path directory) throws IOException { throw new IOException("directory force failed"); }
        };
        assertThatThrownBy(() -> failing.append(UUID.randomUUID(), Instant.now()))
                .as("the caller (unsubscribe) fails and deletes nothing").isInstanceOf(IOException.class).hasMessage("directory force failed");
    }
}
