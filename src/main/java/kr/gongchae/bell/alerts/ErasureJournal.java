package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
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
import org.springframework.stereotype.Component;

/**
 * ADR-0013 host-file erasure journal, shared by blue and green: one line {@code <subscription uuid>\t<instant>\n}
 * per unsubscribe, written under the subscription row lock before the DB delete. Writers in one JVM are serialized
 * here; across containers by an OS file lock. A write loop puts every byte, a newline guard keeps a new line from
 * joining a torn one, and force(true) runs before the DB delete. Reading for restore is strict: any malformed or torn
 * line fails the whole read, so nothing is re-applied from a journal we cannot trust (fail closed).
 */
@Component
public class ErasureJournal {
    private final Object writers = new Object();
    private final Path path;

    @org.springframework.beans.factory.annotation.Autowired
    public ErasureJournal(AlertsProperties properties) {
        this(AlertsProperties.present(properties.erasureLog()) ? Path.of(properties.erasureLog()) : null);
    }

    ErasureJournal(Path path) { this.path = path; }

    public static final class CorruptJournal extends IOException {
        CorruptJournal(String reason) { super(reason); }
    }

    public void append(UUID subscriptionId, Instant at) throws IOException {
        if (path == null) throw new IllegalStateException("ALERTS_DISABLED");
        synchronized (writers) {
            boolean created = Files.notExists(path);
            try (FileChannel channel = FileChannel.open(path, CREATE, READ, WRITE); FileLock lock = channel.lock()) {
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
            }
            if (created) forceDirectory(path.toAbsolutePath().getParent());
        }
    }

    /** Every subscription id in the journal, or {@link CorruptJournal} naming the first bad line. Reads under the lock. */
    public List<UUID> readStrict() throws IOException {
        if (path == null) throw new IllegalStateException("ALERTS_ERASURE_LOG_REQUIRED");
        if (Files.notExists(path)) return List.of();
        byte[] bytes;
        synchronized (writers) {
            try (FileChannel channel = FileChannel.open(path, READ, WRITE); FileLock lock = channel.lock()) {
                ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(channel.size()));
                while (all.hasRemaining() && channel.read(all, all.position()) >= 0) { }
                bytes = all.array();
            }
        }
        if (bytes.length == 0) return List.of();
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

    /** First creation: make the new directory entry durable too (POSIX). Windows cannot open a directory for this. */
    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (FileChannel dir = FileChannel.open(directory, READ)) { dir.force(true); }
    }
}
