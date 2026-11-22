package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErasureJournalToolTest {
    @TempDir Path dir;

    static final UUID M1 = ErasureJournalTest.M1;
    static final Pattern ANY_UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    record Result(int exit, String out) {}

    static Result run(Function<Path, ErasureJournal> journals, String... args) {
        var buffer = new ByteArrayOutputStream();
        int exit = new ErasureJournalTool(journals, new PrintStream(buffer, true, UTF_8)).run(args);
        String out = buffer.toString(UTF_8).strip();
        assertThat(out).as("one line of codes and counts, never an id or a path").doesNotContain("\n").doesNotContainPattern(ANY_UUID)
                .doesNotContain("/").doesNotContain("\\");
        return new Result(exit, out);
    }

    static Result run(String... args) { return run(ErasureJournal::new, args); }

    /** A journal whose directory sync fails, or counts its calls. */
    static Function<Path, ErasureJournal> sync(AtomicInteger calls, boolean fail) {
        return path -> new ErasureJournal(path) {
            @Override void syncDirectory(Path directory) throws IOException {
                calls.incrementAndGet();
                if (fail) throw new IOException("directory force failed " + directory);
            }
        };
    }

    static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"), "Linux POSIX file tool");
    }

    static String line(UUID id) { return ErasureJournalTest.line(id); }

    Path journal(String content, int mode) throws IOException {
        Path file = dir.resolve("erasure.log");
        Files.deleteIfExists(file);  // the previous case may have left it read-only
        Files.writeString(file, content, UTF_8);
        Files.setAttribute(file, "unix:mode", mode);
        return file;
    }

    static long unix(Path path, String name) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:" + name, NOFOLLOW_LINKS)).longValue();
    }

    List<Path> others(Path file) throws IOException {
        try (var listing = Files.list(file.getParent())) { return listing.filter(path -> !path.equals(file)).toList(); }
    }

    @Test
    void versionAndUsage() {
        assertThat(run("version")).isEqualTo(new Result(0, "ERASURE_TOOL v1"));
        for (String[] bad : new String[][] {{}, {"version", "x"}, {"mark"}, {"init", "a", "b"}, {"compact", "x"}, {"apply", "x"}})
            assertThat(run(bad)).as(Arrays.toString(bad)).isEqualTo(new Result(2, "ERASURE_TOOL_USAGE"));
    }

    @Test
    void checkIsAStrictReadOnlyParseThatPrintsOnlyCodesAndCounts() throws Exception {
        Path file = dir.resolve("copy.log");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Files.writeString(file, line(a) + line(M1) + line(b), UTF_8);
        assertThat(file.toFile().setWritable(false)).isTrue();
        try {
            assertThat(run("check", file.toString())).isEqualTo(new Result(0, "ERASURE_CHECK_OK lines=3 markers=1 head=line tail=line"));
        } finally {
            file.toFile().setWritable(true);
        }
        Files.writeString(file, "", UTF_8);
        assertThat(run("check", file.toString())).isEqualTo(new Result(0, "ERASURE_CHECK_OK lines=0 markers=0 head=empty tail=empty"));
        Files.writeString(file, line(a) + a, UTF_8);
        assertThat(run("check", file.toString())).isEqualTo(new Result(3, "ERASURE_BUNDLE_TORN_LAST_LINE"));
        assertThat(run("check", dir.resolve("missing.log").toString())).isEqualTo(new Result(3, "ERASURE_BUNDLE_MISSING"));
    }

    @Test
    void initPublishesOnlyAMissingFileHoldingOneNewMarker() throws Exception {
        assumePosix();
        Path file = dir.resolve("erasure.log");
        assertThat(run("init", file.toString())).isEqualTo(new Result(0, "ERASURE_INIT_DONE"));
        byte[] made = Files.readAllBytes(file);
        ErasureJournal.Snapshot snapshot = ErasureJournal.parse(made, "ERASURE_LOG");
        assertThat(snapshot.lines()).singleElement().satisfies(only -> assertThat(only.marker()).isTrue());
        assertThat(Instant.parse(new String(made, UTF_8).strip().split("\t")[1])).isNotNull();
        assertThat(unix(file, "mode") & 07777).isEqualTo(0640);
        assertThat(unix(file, "uid")).isEqualTo(unix(dir, "uid"));
        assertThat(unix(file, "gid")).isEqualTo(unix(dir, "gid"));
        assertThat(unix(file, "nlink")).as("the temporary link is gone").isEqualTo(1);
        assertThat(others(file)).as("no temporary file left").isEmpty();

        assertThat(run("init", file.toString())).isEqualTo(new Result(0, "ERASURE_INIT_ALREADY"));
        assertThat(Files.readAllBytes(file)).as("ALREADY changes nothing").isEqualTo(made);
        assertThat(run("verify", file.toString())).isEqualTo(new Result(0, "ERASURE_VERIFY_OK lines=1 markers=1 head=marker tail=marker temps=0"));
    }

    @Test
    void initRefusesEveryExistingFileThatIsNotExactlyOneMarker() throws Exception {
        assumePosix();
        UUID a = UUID.randomUUID();
        String[][] cases = {
            {"", "ERASURE_INIT_EXISTING_EMPTY"},
            {line(a), "ERASURE_INIT_EXISTING_JOURNAL"},
            {line(M1) + line(a), "ERASURE_INIT_EXISTING_JOURNAL"},
            {line(M1) + line(ErasureJournalTest.M2), "ERASURE_INIT_EXISTING_JOURNAL"},
            {line(a) + a, "ERASURE_LOG_TORN_LAST_LINE"},
            {line(M1) + line(M1), "ERASURE_LOG_DUPLICATE_MARKER"},
        };
        for (String[] existing : cases) {
            Path file = journal(existing[0], 0600);
            assertThat(run("init", file.toString())).as(existing[1]).isEqualTo(new Result(3, existing[1]));
            assertThat(Files.readString(file, UTF_8)).isEqualTo(existing[0]);
            assertThat(others(file)).isEmpty();
        }
        Path directory = Files.createDirectory(dir.resolve("a-directory"));
        assertThat(run("init", directory.toString())).isEqualTo(new Result(3, "ERASURE_TOOL_NOT_REGULAR_FILE"));
        Path target = journal(line(M1), 0640);
        Path link = Files.createSymbolicLink(dir.resolve("link.log"), target);
        assertThat(run("init", link.toString())).as("a link is never followed").isEqualTo(new Result(3, "ERASURE_TOOL_NOT_REGULAR_FILE"));
        assertThat(run("mark", link.toString())).isEqualTo(new Result(3, "ERASURE_TOOL_NOT_REGULAR_FILE"));
        assertThat(run("verify", link.toString())).isEqualTo(new Result(3, "ERASURE_TOOL_NOT_REGULAR_FILE"));
        assertThat(run("init", dir.resolve("no-such-dir/erasure.log").toString())).isEqualTo(new Result(3, "ERASURE_TOOL_NO_DIRECTORY"));
    }

    @Test
    void markAppendsOneNewMarkerAfterAnUnsubscribeKeepingEveryByteOwnerGroupAndMode() throws Exception {
        assumePosix();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int mode : new int[] {0600, 0640}) {
            for (String before : new String[] {line(M1) + line(a), line(a) + line(b)}) {
                Path file = journal(before, mode);
                long inode = unix(file, "ino");
                Result done = run("mark", file.toString());
                assertThat(done.exit()).isZero();
                assertThat(done.out()).startsWith("ERASURE_MARK_DONE lines=3 ").endsWith(" tail=marker");
                byte[] after = Files.readAllBytes(file);
                assertThat(Arrays.copyOf(after, before.length())).as("every original byte").isEqualTo(before.getBytes(UTF_8));
                List<ErasureJournal.Line> lines = ErasureJournal.parse(after, "ERASURE_LOG").lines();
                assertThat(lines).hasSize(3);
                assertThat(lines.getLast().marker()).isTrue();
                assertThat(lines.getLast().id()).isNotEqualTo(M1);
                assertThat(unix(file, "mode") & 07777).isEqualTo(mode);
                assertThat(unix(file, "uid")).isEqualTo(unix(dir, "uid"));
                assertThat(unix(file, "gid")).isEqualTo(unix(dir, "gid"));
                assertThat(unix(file, "ino")).as("published by rename, not rewritten in place").isNotEqualTo(inode);
                assertThat(others(file)).isEmpty();

                assertThat(run("mark", file.toString())).as("tail is a marker")
                        .isEqualTo(new Result(0, done.out().replace("ERASURE_MARK_DONE", "ERASURE_MARK_NOOP")));
                assertThat(Files.readAllBytes(file)).isEqualTo(after);
            }
        }
        Path marked = journal(line(M1), 0600);
        assertThat(run("mark", marked.toString())).isEqualTo(new Result(0, "ERASURE_MARK_NOOP lines=1 markers=1 head=marker tail=marker"));
        assertThat(Files.readString(marked, UTF_8)).isEqualTo(line(M1));
    }

    @Test
    void markRefusesAMissingEmptyCorruptOrUnsafeFileAndChangesNothing() throws Exception {
        assumePosix();
        Path missing = dir.resolve("erasure.log");
        assertThat(run("mark", missing.toString())).isEqualTo(new Result(3, "ERASURE_LOG_MISSING"));
        assertThat(run("verify", missing.toString())).isEqualTo(new Result(3, "ERASURE_LOG_MISSING"));
        assertThat(missing).as("never created").doesNotExist();
        UUID a = UUID.randomUUID();
        Object[][] cases = {
            {"", 0600, "ERASURE_MARK_EMPTY"},
            {line(a) + a, 0600, "ERASURE_LOG_TORN_LAST_LINE"},
            {a.toString().toUpperCase() + "\t2026-11-22T00:00:00Z\n", 0600, "ERASURE_LOG_BAD_LINE_1"},
            {line(a), 0644, "ERASURE_TOOL_MODE_UNSUPPORTED"},
            {line(a), 0660, "ERASURE_TOOL_MODE_UNSUPPORTED"},
            {line(a), 0400, "ERASURE_TOOL_MODE_UNSUPPORTED"},
        };
        for (Object[] existing : cases) {
            Path file = journal((String) existing[0], (int) existing[1]);
            assertThat(run("mark", file.toString())).as((String) existing[2]).isEqualTo(new Result(3, (String) existing[2]));
            assertThat(Files.readString(file, UTF_8)).isEqualTo(existing[0]);
            assertThat(others(file)).isEmpty();
        }
        Path unsafe = journal(line(M1), 0644);
        assertThat(run("verify", unsafe.toString())).isEqualTo(new Result(3, "ERASURE_TOOL_MODE_UNSUPPORTED"));
        assertThat(run("mark", unsafe.toString())).as("NOOP checks the contract too").isEqualTo(new Result(3, "ERASURE_TOOL_MODE_UNSUPPORTED"));
    }

    @Test
    void aFailedDirectorySyncIsNeverHiddenByALaterNoopOrAlready() throws Exception {
        assumePosix();
        AtomicInteger calls = new AtomicInteger();
        Path file = journal(line(UUID.randomUUID()), 0640);
        assertThat(run(sync(calls, true), "mark", file.toString())).isEqualTo(new Result(4, "ERASURE_MARK_UNCONFIRMED"));
        byte[] published = Files.readAllBytes(file);
        assertThat(ErasureJournal.parse(published, "ERASURE_LOG").lines().getLast().marker()).as("renamed before the sync failed").isTrue();
        assertThat(run(sync(calls, true), "mark", file.toString())).as("same content, still not durable").isEqualTo(new Result(4, "ERASURE_TOOL_IO_FAILED"));
        assertThat(run(sync(calls, true), "verify", file.toString())).isEqualTo(new Result(4, "ERASURE_TOOL_IO_FAILED"));
        calls.set(0);
        assertThat(run(sync(calls, false), "mark", file.toString()).out()).startsWith("ERASURE_MARK_NOOP ");
        assertThat(calls).as("the NOOP forced the directory").hasValue(1);
        assertThat(Files.readAllBytes(file)).isEqualTo(published);

        Path fresh = dir.resolve("sub").resolve("erasure.log");
        Files.createDirectory(fresh.getParent());
        assertThat(run(sync(calls, true), "init", fresh.toString())).isEqualTo(new Result(4, "ERASURE_INIT_UNCONFIRMED"));
        byte[] marker = Files.readAllBytes(fresh);
        assertThat(run(sync(calls, true), "init", fresh.toString())).isEqualTo(new Result(4, "ERASURE_TOOL_IO_FAILED"));
        calls.set(0);
        assertThat(run(sync(calls, false), "init", fresh.toString())).isEqualTo(new Result(0, "ERASURE_INIT_ALREADY"));
        assertThat(calls).hasValue(1);
        assertThat(Files.readAllBytes(fresh)).isEqualTo(marker);
    }

    @Test
    void nothingIsPublishedWhenTheTemporaryFileCannotBeMade() throws Exception {
        assumePosix();
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root ignores directory permissions");
        Path file = journal(line(UUID.randomUUID()), 0640);
        byte[] before = Files.readAllBytes(file);
        Files.setAttribute(dir, "unix:mode", 0500);
        try {
            assertThat(run("mark", file.toString())).isEqualTo(new Result(4, "ERASURE_TOOL_ACCESS_DENIED"));
            assertThat(run("init", dir.resolve("new.log").toString())).isEqualTo(new Result(4, "ERASURE_TOOL_ACCESS_DENIED"));
        } finally {
            Files.setAttribute(dir, "unix:mode", 0700);
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(others(file)).isEmpty();
    }

    @Test
    void aHeldLockIsRefusedWithoutWaiting() throws Exception {
        assumePosix();
        Path file = journal(line(UUID.randomUUID()), 0640);
        byte[] before = Files.readAllBytes(file);
        try (FileChannel writer = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock held = writer.lock()) {
            for (String command : new String[] {"mark", "verify", "init"})
                assertThat(run(command, file.toString())).as(command).isEqualTo(new Result(3, "ERASURE_TOOL_LOCK_BUSY"));
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void concurrentInitsPublishExactlyOneMarkerAndNeverReplaceIt() throws Exception {
        assumePosix();
        for (int round = 0; round < 20; round++) {
            Path file = Files.createDirectory(dir.resolve("round" + round)).resolve("erasure.log");
            List<Callable<Result>> inits = new ArrayList<>();
            for (int i = 0; i < 6; i++) inits.add(() -> run("init", file.toString()));
            List<Result> results = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(6)) {
                for (Future<Result> result : pool.invokeAll(inits)) results.add(result.get());
            }
            // One JVM shares one lock table, so the publisher's own reread can meet a sibling's lock: UNCONFIRMED, never a second file.
            assertThat(results).filteredOn(result -> result.out().equals("ERASURE_INIT_DONE") || result.out().equals("ERASURE_INIT_UNCONFIRMED"))
                    .as("exactly one publisher").hasSize(1);
            assertThat(results).allSatisfy(result -> assertThat(result.out()).isIn("ERASURE_INIT_DONE", "ERASURE_INIT_UNCONFIRMED",
                    "ERASURE_INIT_ALREADY", "ERASURE_INIT_EXISTS", "ERASURE_TOOL_LOCK_BUSY"));
            byte[] published = Files.readAllBytes(file);
            assertThat(ErasureJournal.parse(published, "ERASURE_LOG").lines()).singleElement()
                    .satisfies(only -> assertThat(only.marker()).isTrue());
            assertThat(others(file)).isEmpty();
            assertThat(run("init", file.toString())).isEqualTo(new Result(0, "ERASURE_INIT_ALREADY"));
            assertThat(Files.readAllBytes(file)).isEqualTo(published);
        }
    }

    /** Needs a supplementary group: skipped where the user has none. */
    @Test
    void aSetgidDirectoryHandsDownItsGroupAndAWrongGroupIsRefusedBeforePublishing() throws Exception {
        assumePosix();
        int primary = (int) unix(dir, "gid");
        int other = Files.readAllLines(Path.of("/proc/self/status")).stream().filter(row -> row.startsWith("Groups:"))
                .flatMap(row -> Arrays.stream(row.substring(7).trim().split("\\s+"))).filter(gid -> !gid.isEmpty())
                .mapToInt(Integer::parseInt).filter(gid -> gid != primary).findFirst().orElse(-1);
        assumeTrue(other >= 0, "a supplementary group");

        Files.setAttribute(dir, "unix:gid", other);
        Files.setAttribute(dir, "unix:mode", 02750);
        Path file = dir.resolve("erasure.log");
        assertThat(run("init", file.toString())).isEqualTo(new Result(0, "ERASURE_INIT_DONE"));
        assertThat(unix(file, "gid")).as("inherited from the setgid directory").isEqualTo(other);
        assertThat(unix(file, "mode") & 07777).isEqualTo(0640);
        Files.writeString(file, line(UUID.randomUUID()), UTF_8, StandardOpenOption.APPEND);
        assertThat(run("mark", file.toString()).out()).startsWith("ERASURE_MARK_DONE ");
        assertThat(unix(file, "gid")).isEqualTo(other);

        Files.setAttribute(dir, "unix:mode", 0750);  // no setgid: a new file gets the process group
        byte[] before = Files.readAllBytes(file);
        Files.writeString(file, line(UUID.randomUUID()), UTF_8, StandardOpenOption.APPEND);
        byte[] appended = Files.readAllBytes(file);
        assertThat(run("mark", file.toString())).isEqualTo(new Result(3, "ERASURE_TOOL_GROUP_MISMATCH"));
        assertThat(Files.readAllBytes(file)).isEqualTo(appended).startsWith(before);
        assertThat(others(file)).isEmpty();

        Files.setAttribute(file, "unix:gid", primary);
        assertThat(run("verify", file.toString())).as("file group differs from the directory").isEqualTo(new Result(3, "ERASURE_TOOL_GROUP_MISMATCH"));
    }

    @Test
    void newMarkersAreReservedVersion8IdsThatDoNotRepeat() {
        Random random = new Random(17);
        var seen = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            UUID marker = ErasureJournal.newMarker(random);
            assertThat(ErasureJournal.isMarker(marker)).isTrue();
            assertThat(marker.toString()).startsWith("67636862-6172-8");
            assertThat(seen.add(marker)).isTrue();
        }
    }
}
