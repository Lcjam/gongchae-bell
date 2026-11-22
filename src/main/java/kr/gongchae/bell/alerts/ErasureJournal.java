package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0013 host-file erasure journal, shared by blue and green: one line {@code <subscription uuid>\t<instant>\n}
 * per unsubscribe, written under the subscription row lock before the DB delete.
 *
 * <p>The operator prepares the file once, before alerts are first enabled ({@link ErasureJournalTool} {@code init}, under
 * a supervised stop that does not exist yet). With alerts enabled the app refuses to start without it, and the app never
 * creates it. So a missing file always means a wrong path or mount, never
 * "nothing erased yet", both in service and at restore.
 *
 * <p>Writers in one JVM are serialized here, and writers across containers by an OS file lock. A write loop puts
 * every byte, and a newline guard keeps a new line from joining a torn one. Before the lock is released, the file is
 * forced and so is its directory (POSIX), on every append, so the entry is durable before any DB delete commits,
 * whichever process wrote first. Reading for restore is strict: a missing file or a malformed or torn line fails the
 * whole read, and nothing is re-applied from a journal we cannot trust (fail closed).
 *
 * <p>Barrier markers (reserved UUIDv8 lines, {@link #isMarker}) use the same line grammar, so older readers accept them,
 * but they are never subscription ids. The restore gate {@link #verifyCoverage} compares the journal bundled with a dump
 * (B) with the journal to apply (L).
 */
@Component
public class ErasureJournal {
    private final Object writers = new Object();
    private final Path path;

    @Autowired
    public ErasureJournal(AlertsProperties properties) {
        this(AlertsProperties.present(properties.erasureLog()) ? Path.of(properties.erasureLog()) : null);
        if (properties.enabled() && !Files.isRegularFile(path)) throw new IllegalStateException("ALERTS_ERASURE_LOG_MISSING");
    }

    ErasureJournal(Path path) { this.path = path; }

    /** The journal cannot be trusted for a restore: missing, torn or malformed. The message is a fixed code. */
    public static final class CorruptJournal extends IOException {
        CorruptJournal(String reason) { super(reason); }
    }

    public void append(UUID subscriptionId, Instant at) throws IOException {
        if (path == null) throw new IllegalStateException("ALERTS_DISABLED");
        synchronized (writers) {
            try (FileChannel channel = FileChannel.open(path, READ, WRITE); FileLock lock = channel.lock()) {  // no CREATE
                long end = channel.size();
                boolean torn = false;
                if (end > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.read(last, end - 1);
                    torn = last.get(0) != '\n';
                }
                ByteBuffer line = UTF_8.encode((torn ? "\n" : "") + subscriptionId + "\t" + at + "\n");
                while (line.hasRemaining()) end += channel.write(line, end);
                channel.force(true);
                syncDirectory(path.toAbsolutePath().getParent());  // still under the OS lock
            }
        }
    }

    /** Every subscription id in the journal (markers excluded), or {@link CorruptJournal} naming why it cannot be used. */
    public List<UUID> readStrict() throws IOException {
        return readLatest().subscriptionIds();
    }

    /** The journal L as one consistent byte snapshot, read under the same OS lock the writers use, then strictly parsed. */
    public Snapshot readLatest() throws IOException {
        if (path == null) throw new IllegalStateException("ALERTS_ERASURE_LOG_REQUIRED");
        if (!Files.isRegularFile(path)) throw new CorruptJournal("ERASURE_LOG_MISSING");
        byte[] bytes;
        synchronized (writers) {
            try (FileChannel channel = FileChannel.open(path, READ, WRITE); FileLock lock = channel.lock()) {
                bytes = readAll(channel);
            }
        }
        return parse(bytes, "ERASURE_LOG");
    }

    /** Every byte of an open file, from the start. */
    static byte[] readAll(FileChannel channel) throws IOException {
        ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        while (all.hasRemaining() && channel.read(all, all.position()) >= 0) { }
        return all.array();
    }

    /**
     * The journal B bundled with a dump snapshot. It is an immutable copy, so it is read without a lock and without write
     * access: a read-only mount works. Same strict grammar as L; error codes start with {@code ERASURE_BUNDLE_}.
     */
    public static Snapshot readBundle(Path bundle) throws IOException {
        if (bundle == null) throw new CorruptJournal("ERASURE_BUNDLE_REQUIRED");
        if (!Files.isRegularFile(bundle)) throw new CorruptJournal("ERASURE_BUNDLE_MISSING");
        return parse(Files.readAllBytes(bundle), "ERASURE_BUNDLE");
    }

    /** Reserved marker namespace (collab recovery 05/08): UUIDv8, IETF variant, top 48 bits {@code 67636862-6172}. */
    static final long MARKER_TAG = 0x676368626172L;

    /** A barrier marker line, never a subscription id. The app only creates version-4 subscription ids. */
    static boolean isMarker(UUID id) {
        return id.version() == 8 && id.variant() == 2 && (id.getMostSignificantBits() >>> 16) == MARKER_TAG;
    }

    /** A new barrier marker: the reserved tag, version 8, the IETF variant and 74 random bits. */
    static UUID newMarker(Random random) {
        return new UUID(MARKER_TAG << 16 | 0x8000L | (random.nextLong() & 0x0fffL), Long.MIN_VALUE | random.nextLong() >>> 2);
    }

    /** One line: its id, whether it is a marker, and its byte range {@code [start, end)} including the newline. */
    public record Line(UUID id, boolean marker, int start, int end) {}

    /** A strictly parsed journal: the exact bytes and the lines in file order. */
    public record Snapshot(byte[] bytes, List<Line> lines) {
        public List<UUID> subscriptionIds() {
            return lines.stream().filter(line -> !line.marker()).map(Line::id).toList();
        }
    }

    /** Which coverage rule a restore passed. Logged; never carries journal content. */
    public enum Coverage { LEGACY_PREFIX, MARKER_SUFFIX }

    /**
     * Restore gate (collab recovery 08): may the dump that came with {@code bundle} be restored and repaired with
     * {@code latest}? Pure byte comparison, no clock. If L starts with a marker H (state S2), H must be in B and B from
     * H to its end must be a byte prefix of L. Otherwise (S0: no marker, S1: a marker but not first) all of B must be a
     * byte prefix of L. A dump from before the oldest marker L keeps, a reordered or shorter L, an L recreated empty and
     * a B longer than L all fail. Limit kept on purpose: without a head marker, a whole tail lost after B was copied is
     * not detected.
     */
    public static Coverage verifyCoverage(Snapshot bundle, Snapshot latest) throws CorruptJournal {
        if (!latest.lines().isEmpty() && latest.lines().getFirst().marker()) {
            UUID head = latest.lines().getFirst().id();
            Line inBundle = bundle.lines().stream().filter(line -> line.marker() && line.id().equals(head)).findFirst()
                    .orElseThrow(() -> new CorruptJournal("ERASURE_COVERAGE_MARKER_ABSENT"));
            if (!isPrefix(bundle.bytes(), inBundle.start(), latest.bytes())) throw new CorruptJournal("ERASURE_COVERAGE_NOT_PREFIX");
            return Coverage.MARKER_SUFFIX;
        }
        if (!isPrefix(bundle.bytes(), 0, latest.bytes())) throw new CorruptJournal("ERASURE_COVERAGE_NOT_PREFIX");
        return Coverage.LEGACY_PREFIX;
    }

    private static boolean isPrefix(byte[] bundle, int from, byte[] latest) {
        int length = bundle.length - from;
        return length <= latest.length && Arrays.equals(bundle, from, bundle.length, latest, 0, length);
    }

    /** The one strict parser for L and B: a missing newline at the end, a bad line or a repeated marker fails it all. */
    static Snapshot parse(byte[] bytes, String code) throws CorruptJournal {
        if (bytes.length == 0) return new Snapshot(bytes, List.of());  // a prepared journal with no unsubscribe yet
        if (bytes[bytes.length - 1] != '\n') throw new CorruptJournal(code + "_TORN_LAST_LINE");
        List<Line> lines = new ArrayList<>();
        Set<UUID> markers = new HashSet<>();
        int start = 0;
        for (int number = 1; start < bytes.length; number++) {
            int end = start;
            while (bytes[end] != '\n') end++;
            String[] parts = new String(bytes, start, end - start, UTF_8).split("\t", -1);
            UUID id;
            try {
                if (parts.length != 2 || parts[0].length() != 36) throw new IllegalArgumentException();
                id = UUID.fromString(parts[0]);
                if (!id.toString().equals(parts[0])) throw new IllegalArgumentException();  // canonical lower-case form only
                Instant.parse(parts[1]);
            } catch (IllegalArgumentException | DateTimeParseException bad) {
                throw new CorruptJournal(code + "_BAD_LINE_" + number);
            }
            boolean marker = isMarker(id);
            if (marker && !markers.add(id)) throw new CorruptJournal(code + "_DUPLICATE_MARKER");
            lines.add(new Line(id, marker, start, end + 1));
            start = end + 1;
        }
        return new Snapshot(bytes, List.copyOf(lines));
    }

    /**
     * Makes the file's directory entry durable on POSIX (the deployment). Windows cannot open a directory for this,
     * so there it does nothing. Package-private so a test can check that it runs while the lock is held.
     */
    void syncDirectory(Path directory) throws IOException {
        if (directory == null || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (FileChannel dir = FileChannel.open(directory, READ)) { dir.force(true); }
    }
}
