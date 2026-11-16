package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
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

    @Test
    void concurrentAppendsKeepEveryLineWholeAndReadBack() throws Exception {
        var journal = new ErasureJournal(dir.resolve("erasure.log"));
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
        Path file = dir.resolve("erasure.log");
        var journal = new ErasureJournal(file);
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
        Files.writeString(file, "", UTF_8);
        assertThat(journal.readStrict()).isEmpty();
        assertThat(new ErasureJournal(dir.resolve("missing.log")).readStrict()).isEmpty();
    }
}
