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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0013 host-file erasure journal, shared by blue and green: one line {@code <subscription uuid>\t<instant>\n}
 * per unsubscribe, written under the subscription row lock before the DB delete.
 *
 * <p>The operator creates the empty file once, before alerts are first enabled. With alerts enabled the app refuses
 * to start without it, and the app never creates it. So a missing file always means a wrong path or mount, never
 * "nothing erased yet", both in service and at restore.
 *
 * <p>Writers in one JVM are serialized here, and writers across containers by an OS file lock. A write loop puts
 * every byte, and a newline guard keeps a new line from joining a torn one. Before the lock is released, the file is
 * forced and so is its directory (POSIX), on every append, so the entry is durable before any DB delete commits,
 * whichever process wrote first. Reading for restore is strict: a missing file or a malformed or torn line fails the
 * whole read, and nothing is re-applied from a journal we cannot trust (fail closed).
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

    /** Every subscription id in the journal, or {@link CorruptJournal} naming why it cannot be used. Reads under the lock. */
    public List<UUID> readStrict() throws IOException {
        if (path == null) throw new IllegalStateException("ALERTS_ERASURE_LOG_REQUIRED");
        if (!Files.isRegularFile(path)) throw new CorruptJournal("ERASURE_LOG_MISSING");
        byte[] bytes;
        synchronized (writers) {
            try (FileChannel channel = FileChannel.open(path, READ, WRITE); FileLock lock = channel.lock()) {
                ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(channel.size()));
                while (all.hasRemaining() && channel.read(all, all.position()) >= 0) { }
                bytes = all.array();
            }
        }
        if (bytes.length == 0) return List.of();  // a prepared journal with no unsubscribe yet
        if (bytes[bytes.length - 1] != '\n') throw new CorruptJournal("ERASURE_LOG_TORN_LAST_LINE");
        List<UUID> ids = new ArrayList<>();
        String[] lines = new String(bytes, UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            String[] parts = lines[i].split("\t", -1);
            try {
                if (parts.length != 2 || parts[0].length() != 36) throw new IllegalArgumentException();
                UUID id = UUID.fromString(parts[0]);
                if (!id.toString().equals(parts[0])) throw new IllegalArgumentException();  // canonical lower-case form only
                Instant.parse(parts[1]);
                ids.add(id);
            } catch (IllegalArgumentException | DateTimeParseException bad) {
                throw new CorruptJournal("ERASURE_LOG_BAD_LINE_" + (i + 1));
            }
        }
        return ids;
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
