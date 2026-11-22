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

    static final UUID M1 = UUID.fromString("67636862-6172-8a1b-9c3d-4e5f60718293");
    static final UUID M2 = UUID.fromString("67636862-6172-8fff-bfff-000000000001");

    static String line(UUID id) { return id + "\t2026-11-20T01:02:03.456789Z\n"; }

    static ErasureJournal.Snapshot snap(UUID... ids) throws IOException {
        StringBuilder text = new StringBuilder();
        for (UUID id : ids) text.append(line(id));
        return ErasureJournal.parse(text.toString().getBytes(UTF_8), "ERASURE_LOG");
    }

    @Test
    void onlyTheReservedVersion8TagIsAMarker() {
        assertThat(ErasureJournal.isMarker(M1)).isTrue();
        assertThat(ErasureJournal.isMarker(M2)).isTrue();
        assertThat(ErasureJournal.isMarker(UUID.randomUUID())).as("a subscription id (v4)").isFalse();
        assertThat(ErasureJournal.isMarker(UUID.fromString("67636862-6172-4a1b-9c3d-4e5f60718293"))).as("tag but v4").isFalse();
        assertThat(ErasureJournal.isMarker(UUID.fromString("67636862-6173-8a1b-9c3d-4e5f60718293"))).as("other v8 tag").isFalse();
        assertThat(ErasureJournal.isMarker(UUID.fromString("67636862-6172-8a1b-cc3d-4e5f60718293"))).as("not the IETF variant").isFalse();
    }

    @Test
    void markersAreNeverSubscriptionIdsAndARepeatedMarkerFailsTheWholeJournal() throws Exception {
        Path file = prepared();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Files.writeString(file, line(a) + line(M1) + line(b), UTF_8);
        assertThat(new ErasureJournal(file).readStrict()).containsExactly(a, b);
        Files.writeString(file, line(M1) + line(a) + line(M1), UTF_8);
        assertThatThrownBy(() -> new ErasureJournal(file).readStrict()).hasMessage("ERASURE_LOG_DUPLICATE_MARKER");
        Files.writeString(file, line(a) + line(a), UTF_8);
        assertThat(new ErasureJournal(file).readStrict()).as("a repeated subscription line is harmless, as before").containsExactly(a, a);
    }

    @Test
    void theBundleUsesTheSameStrictGrammarAndCanBeReadOnly() throws Exception {
        Path bundle = dir.resolve("bundle.log");
        UUID a = UUID.randomUUID();
        Files.writeString(bundle, line(a) + line(M1), UTF_8);
        assertThat(bundle.toFile().setWritable(false)).isTrue();
        try {
            assertThat(ErasureJournal.readBundle(bundle).subscriptionIds()).as("read-only mount").containsExactly(a);
        } finally {
            bundle.toFile().setWritable(true);
        }
        assertThatThrownBy(() -> ErasureJournal.readBundle(null)).hasMessage("ERASURE_BUNDLE_REQUIRED");
        assertThatThrownBy(() -> ErasureJournal.readBundle(dir.resolve("missing.log"))).hasMessage("ERASURE_BUNDLE_MISSING");
        assertThatThrownBy(() -> ErasureJournal.readBundle(dir)).hasMessage("ERASURE_BUNDLE_MISSING");
        Files.writeString(bundle, line(a) + a, UTF_8);
        assertThatThrownBy(() -> ErasureJournal.readBundle(bundle)).hasMessage("ERASURE_BUNDLE_TORN_LAST_LINE");
        Files.writeString(bundle, line(a) + a + "\t2026-02-30T00:00:00Z\n", UTF_8);
        assertThatThrownBy(() -> ErasureJournal.readBundle(bundle)).as("not a calendar date").hasMessage("ERASURE_BUNDLE_BAD_LINE_2");
        Files.writeString(bundle, M1.toString().toUpperCase() + "\t2026-11-20T00:00:00Z\n", UTF_8);
        assertThatThrownBy(() -> ErasureJournal.readBundle(bundle)).as("canonical form only").hasMessage("ERASURE_BUNDLE_BAD_LINE_1");
        Files.writeString(bundle, line(M1) + line(M1), UTF_8);
        assertThatThrownBy(() -> ErasureJournal.readBundle(bundle)).hasMessage("ERASURE_BUNDLE_DUPLICATE_MARKER");
        Files.writeString(bundle, a + "\t2026-11-20T00:00:00Z\n" + a + "\t2026-11-20T00:00:00.1Z\n", UTF_8);
        assertThat(ErasureJournal.readBundle(bundle).lines()).as("any fraction Instant.parse accepts").hasSize(2);
    }

    @Test
    void withoutAHeadMarkerTheWholeBundleMustBeAPrefixOfTheLatestJournal() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        // S0: no marker in L.
        assertThat(ErasureJournal.verifyCoverage(snap(), snap())).isEqualTo(ErasureJournal.Coverage.LEGACY_PREFIX);
        assertThat(ErasureJournal.verifyCoverage(snap(a, b), snap(a, b, c))).isEqualTo(ErasureJournal.Coverage.LEGACY_PREFIX);
        assertThat(ErasureJournal.verifyCoverage(snap(), snap(a))).as("an empty B, e.g. a dump before any unsubscribe").isNotNull();
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(b, a), snap(a, b, c))).as("reordered")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(a, b, c), snap(a, b))).as("B longer than L")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(a), snap())).as("L recreated empty")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(M1), snap())).as("B marked, L recreated empty")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(a, M1), snap(a, b))).as("B marked, L lost its marker")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        // S1: a marker in L but not first. Nothing was cut, so the same prefix rule.
        assertThat(ErasureJournal.verifyCoverage(snap(a), snap(a, b, M1, c))).isEqualTo(ErasureJournal.Coverage.LEGACY_PREFIX);
        assertThat(ErasureJournal.verifyCoverage(snap(a, b, M1), snap(a, b, M1, c))).isEqualTo(ErasureJournal.Coverage.LEGACY_PREFIX);
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(M1, c), snap(c, M2))).as("head marker removed from L")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        // Only bytes count: the same ids with another timestamp text are not the same journal.
        ErasureJournal.Snapshot other = ErasureJournal.parse((a + "\t2026-11-20T01:02:03Z\n").getBytes(UTF_8), "ERASURE_LOG");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(other, snap(a))).hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
    }

    @Test
    void withAHeadMarkerTheBundleFromThatMarkerOnMustBeAPrefixOfTheLatestJournal() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        // S2: L = [M1, c, M2, d] after the lines before M1 were cut.
        ErasureJournal.Snapshot latest = snap(M1, c, M2, d);
        assertThat(ErasureJournal.verifyCoverage(snap(a, b, M1, c), latest)).isEqualTo(ErasureJournal.Coverage.MARKER_SUFFIX);
        assertThat(ErasureJournal.verifyCoverage(snap(a, M1), latest)).isEqualTo(ErasureJournal.Coverage.MARKER_SUFFIX);
        assertThat(ErasureJournal.verifyCoverage(snap(M1, c, M2, d), latest)).as("B == L").isEqualTo(ErasureJournal.Coverage.MARKER_SUFFIX);
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(a, b), latest)).as("a dump from before the kept marker")
                .hasMessage("ERASURE_COVERAGE_MARKER_ABSENT");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(), latest)).as("an empty B before any marker")
                .hasMessage("ERASURE_COVERAGE_MARKER_ABSENT");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(a, M2, d), snap(M1, c))).as("B newer than L")
                .hasMessage("ERASURE_COVERAGE_MARKER_ABSENT");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(M1, d), latest)).as("L lost c after the marker")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThatThrownBy(() -> ErasureJournal.verifyCoverage(snap(M1, c, M2, d, a), latest)).as("B longer than L")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        assertThat(ErasureJournal.verifyCoverage(snap(M1), snap(M1))).as("a marked, still empty journal").isNotNull();
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
