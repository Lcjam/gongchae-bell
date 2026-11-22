package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * File tool for the ADR-0013 erasure journal, run from the boot JAR before Spring starts (no context, no DB):
 * {@code java -jar gongchae-bell.jar erasure-journal version | check <file> | init <file> | mark <file> | verify <file>}.
 * Operator contract: docs/ops/alerts.md (해지 대장과 복원) and ADR-0013.
 *
 * <ul>
 * <li>{@code check}: strict read-only parse, no lock (a read-only copy or mount works). Says nothing about durability.
 * <li>{@code verify}: L under the writers' OS lock: strict parse, owner/group/mode contract, file and directory forced.
 * <li>{@code init}: publishes a new L holding one new marker, only where no file exists. An existing file that is exactly
 * one marker is ALREADY; an empty, other or corrupt file is refused.
 * <li>{@code mark}: appends one new marker after an unsubscribe line, keeping every existing byte. A marker tail is a
 * NOOP; a missing or empty L is refused (a first marker is init's decision).
 * </ul>
 *
 * <p>Writes go to a temporary file in the same directory: every byte written, read back and strictly parsed, mode set,
 * owner and group checked, forced, then published atomically ({@code link(2)} for init, never over an existing file;
 * {@code rename(2)} for mark), the directory forced and the result read again. NOOP, ALREADY and verify still force the
 * file and directory, so a rerun finishes an earlier publish whose directory sync failed. Every lock the tool takes,
 * also for the read after publishing, is tried once and never waited for.
 *
 * <p>Not covered here: that the apps and every other writer are stopped, the DB state, and which backups exist. A lock
 * held on the old file does not keep a waiting writer off the replaced one. The supervising command that owns those
 * checks does not exist yet, so init and mark must not be run on production. Linux POSIX only (uid, gid, mode).
 *
 * <p>Output is one line of fixed codes and counts: never an id, journal content, a path, the environment or an exception
 * message. Exit 0 done (also NOOP and ALREADY), 2 usage, 3 refused with the file unchanged by this tool, 4 I/O failure;
 * a code ending in {@code _UNCONFIRMED} means the new file is published but not yet confirmed durable: rerun the command.
 */
public final class ErasureJournalTool {
    public static final String COMMAND = "erasure-journal";
    private static final Random RANDOM = new SecureRandom();

    private final Function<Path, ErasureJournal> journals;
    private final PrintStream out;
    private final Supplier<String> tempSuffix;

    ErasureJournalTool(Function<Path, ErasureJournal> journals, PrintStream out, Supplier<String> tempSuffix) {
        this.journals = journals;
        this.out = out;
        this.tempSuffix = tempSuffix;
    }

    public static int run(String[] args, PrintStream out) {
        return new ErasureJournalTool(ErasureJournal::new, out, () -> Long.toUnsignedString(RANDOM.nextLong(), 36)).run(args);
    }

    /** A refusal or failure with a fixed code. */
    private static final class Stop extends Exception {
        final int exit;

        Stop(int exit, String code) {
            super(code, null, false, false);
            this.exit = exit;
        }
    }

    int run(String[] args) {
        try {
            out.println(dispatch(args));
            return 0;
        } catch (Stop stop) {
            out.println(stop.getMessage());
            return stop.exit;
        } catch (ErasureJournal.CorruptJournal corrupt) {
            out.println(corrupt.getMessage());  // a fixed code and at most a line number
            return 3;
        } catch (AccessDeniedException denied) {
            out.println("ERASURE_TOOL_ACCESS_DENIED");
            return 4;
        } catch (IOException | RuntimeException failed) {
            out.println("ERASURE_TOOL_IO_FAILED");  // the exception text can hold a path, so it is never printed
            return 4;
        }
    }

    private String dispatch(String[] args) throws IOException, Stop {
        if (args.length == 1 && args[0].equals("version")) return "ERASURE_TOOL v1";
        if (args.length != 2) throw new Stop(2, "ERASURE_TOOL_USAGE");
        Path file = Path.of(args[1]).toAbsolutePath();
        return switch (args[0]) {
            case "check" -> {
                stat(file);  // EACCES on the way is not "missing"
                yield "ERASURE_CHECK_OK " + summary(ErasureJournal.readBundle(file));
            }
            case "verify" -> verify(file, journals.apply(file));
            case "init" -> init(file, journals.apply(file));
            case "mark" -> mark(file, journals.apply(file));
            default -> throw new Stop(2, "ERASURE_TOOL_USAGE");
        };
    }

    private String verify(Path file, ErasureJournal journal) throws IOException, Stop {
        requireRegular(file);
        requireContract(attrs(file), attrs(file.getParent()));
        return underLock(file, (channel, current) -> {
            confirm(channel, file, journal);
            return "ERASURE_VERIFY_OK " + summary(current) + " temps=" + temps(file);
        });
    }

    private String init(Path file, ErasureJournal journal) throws IOException, Stop {
        Path dir = file.getParent();
        if (stat(file) != null) {
            requireRegular(file);
            return underLock(file, (channel, current) -> {
                if (current.lines().isEmpty()) throw new Stop(3, "ERASURE_INIT_EXISTING_EMPTY");
                if (current.lines().size() != 1 || !current.lines().getFirst().marker()) throw new Stop(3, "ERASURE_INIT_EXISTING_JOURNAL");
                confirm(channel, file, journal);  // an earlier init may have stopped after publishing
                return "ERASURE_INIT_ALREADY";
            });
        }
        BasicFileAttributes directory = stat(dir);
        if (directory == null || !directory.isDirectory()) throw new Stop(3, "ERASURE_TOOL_NO_DIRECTORY");
        Attrs expected = attrs(dir);
        byte[] bytes = line(ErasureJournal.newMarker(RANDOM));
        Path temp = writeTemp(file, bytes, 0640, expected);
        try {
            Files.createLink(file, temp);  // link(2) never replaces an existing file
        } catch (IOException | RuntimeException notPublished) {
            Files.deleteIfExists(temp);
            if (notPublished instanceof FileAlreadyExistsException) throw new Stop(3, "ERASURE_INIT_EXISTS");
            throw notPublished;
        }
        afterPublish("ERASURE_INIT_UNCONFIRMED", () -> {
            Files.delete(temp);
            journal.syncDirectory(dir);
            reread(file, bytes, new Attrs(expected.uid(), expected.gid(), 0640));
        });
        return "ERASURE_INIT_DONE";
    }

    private String mark(Path file, ErasureJournal journal) throws IOException, Stop {
        requireRegular(file);
        Path dir = file.getParent();
        requireContract(attrs(file), attrs(dir));
        return underLock(file, (channel, current) -> {
            if (current.lines().isEmpty()) throw new Stop(3, "ERASURE_MARK_EMPTY");
            if (current.lines().getLast().marker()) {
                confirm(channel, file, journal);  // same content, but an earlier publish may not be durable yet
                return "ERASURE_MARK_NOOP " + summary(current);
            }
            Attrs before = attrs(file);
            Set<UUID> taken = current.lines().stream().map(ErasureJournal.Line::id).collect(Collectors.toSet());
            UUID marker = ErasureJournal.newMarker(RANDOM);
            while (taken.contains(marker)) marker = ErasureJournal.newMarker(RANDOM);
            byte[] original = current.bytes(), appended = line(marker);
            byte[] next = Arrays.copyOf(original, original.length + appended.length);
            System.arraycopy(appended, 0, next, original.length, appended.length);
            Path temp = writeTemp(file, next, before.perm(), before);
            try {
                if (channel.size() != original.length) throw new Stop(4, "ERASURE_MARK_CHANGED");  // a writer ignored the lock
                Files.move(temp, file, ATOMIC_MOVE);  // rename(2) over L
            } catch (IOException | RuntimeException | Stop notPublished) {
                Files.deleteIfExists(temp);
                throw notPublished;
            }
            afterPublish("ERASURE_MARK_UNCONFIRMED", () -> {
                journal.syncDirectory(dir);
                reread(file, next, before);
            });
            return "ERASURE_MARK_DONE " + summary(ErasureJournal.parse(next, "ERASURE_LOG"));
        });
    }

    private interface Body { String apply(FileChannel channel, ErasureJournal.Snapshot current) throws IOException, Stop; }

    /** L opened without following a link, under the writers' OS lock (never waited for), strictly parsed. */
    private static String underLock(Path file, Body body) throws IOException, Stop {
        try (FileChannel channel = FileChannel.open(file, READ, WRITE, NOFOLLOW_LINKS); FileLock lock = tryLock(channel)) {
            return body.apply(channel, ErasureJournal.parse(ErasureJournal.readAll(channel), "ERASURE_LOG"));
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException, Stop {
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException sameProcess) {
            lock = null;
        }
        if (lock == null) throw new Stop(3, "ERASURE_TOOL_LOCK_BUSY");  // another writer is present
        return lock;
    }

    /** The current bytes and the directory entry are on disk, and owner, group and mode follow the contract. */
    private static void confirm(FileChannel channel, Path file, ErasureJournal journal) throws IOException, Stop {
        requireContract(attrs(file), attrs(file.getParent()));
        channel.force(true);
        journal.syncDirectory(file.getParent());
    }

    /** Temporary copy next to L: all bytes written, read back, strictly parsed, mode set, owner and group checked, forced. */
    private Path writeTemp(Path file, byte[] bytes, int perm, Attrs expected) throws IOException, Stop {
        Path temp = file.resolveSibling("." + file.getFileName() + ".tool-" + tempSuffix.get());
        FileChannel created;
        try {
            created = FileChannel.open(temp, Set.of(CREATE_NEW, READ, WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (FileAlreadyExistsException someoneElses) {
            throw new Stop(4, "ERASURE_TOOL_TEMP_EXISTS");  // not ours: left exactly as it is
        }
        boolean kept = false;
        try (FileChannel channel = created) {  // from here on the file is ours, so ours to remove on failure
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            byte[] back = ErasureJournal.readAll(channel);
            ErasureJournal.parse(back, "ERASURE_TOOL_TEMP");
            if (!Arrays.equals(back, bytes)) throw new Stop(4, "ERASURE_TOOL_TEMP_MISMATCH");
            Files.setAttribute(temp, "unix:mode", perm, NOFOLLOW_LINKS);
            Attrs written = attrs(temp);
            if (written.uid() != expected.uid()) throw new Stop(3, "ERASURE_TOOL_OWNER_MISMATCH");  // e.g. root publishing
            if (written.gid() != expected.gid()) throw new Stop(3, "ERASURE_TOOL_GROUP_MISMATCH");
            if (written.perm() != perm) throw new Stop(3, "ERASURE_TOOL_MODE_UNSUPPORTED");
            channel.force(true);
            kept = true;
            return temp;
        } finally {
            if (!kept) Files.deleteIfExists(temp);
        }
    }

    private interface Step { void run() throws Exception; }

    /** The new file is already visible: any failure now leaves it published but not confirmed durable. */
    private static void afterPublish(String code, Step step) throws Stop {
        try {
            step.run();
        } catch (Exception failed) {
            throw new Stop(4, code);
        }
    }

    /** Opened again by path under the same never-waiting lock: exactly the bytes written, the same owner, group and mode. */
    private static void reread(Path file, byte[] bytes, Attrs expected) throws IOException, Stop {
        underLock(file, (channel, current) -> {
            if (!Arrays.equals(current.bytes(), bytes) || !attrs(file).equals(expected)) throw new Stop(4, "ERASURE_TOOL_REREAD_MISMATCH");
            return "";
        });
    }

    private static byte[] line(UUID marker) { return (marker + "\t" + Instant.now() + "\n").getBytes(UTF_8); }

    private record Attrs(int uid, int gid, int perm) {}

    /** Numeric owner, group and permission bits, never following a link; Linux POSIX only. */
    private static Attrs attrs(Path path) throws IOException, Stop {
        Map<String, Object> unix;
        try {
            unix = Files.readAttributes(path, "unix:uid,gid,mode", NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException notPosix) {
            throw new Stop(3, "ERASURE_TOOL_POSIX_REQUIRED");
        }
        return new Attrs(((Number) unix.get("uid")).intValue(), ((Number) unix.get("gid")).intValue(), ((Number) unix.get("mode")).intValue() & 07777);
    }

    /** Same owner and group as the directory (the app UID; the group a setgid directory hands down), mode 0600 or 0640. */
    private static void requireContract(Attrs file, Attrs dir) throws Stop {
        if (file.uid() != dir.uid()) throw new Stop(3, "ERASURE_TOOL_OWNER_MISMATCH");
        if (file.gid() != dir.gid()) throw new Stop(3, "ERASURE_TOOL_GROUP_MISMATCH");
        if (file.perm() != 0600 && file.perm() != 0640) throw new Stop(3, "ERASURE_TOOL_MODE_UNSUPPORTED");
    }

    /** Attributes without following a link; null only when the path does not exist, never when access is denied. */
    private static BasicFileAttributes stat(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static void requireRegular(Path file) throws IOException, Stop {
        BasicFileAttributes attributes = stat(file);
        if (attributes == null) throw new Stop(3, "ERASURE_LOG_MISSING");
        if (!attributes.isRegularFile()) throw new Stop(3, "ERASURE_TOOL_NOT_REGULAR_FILE");  // a directory or a link
    }

    /** Temporary files this tool left next to L, e.g. after a crash: they hold journal lines. */
    private static int temps(Path file) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> found = Files.newDirectoryStream(file.getParent(), "." + file.getFileName() + ".tool-*")) {
            for (Path ignored : found) count++;
        }
        return count;
    }

    private static String summary(ErasureJournal.Snapshot snapshot) {
        List<ErasureJournal.Line> lines = snapshot.lines();
        return "lines=" + lines.size() + " markers=" + lines.stream().filter(ErasureJournal.Line::marker).count()
                + " head=" + kind(lines.isEmpty() ? null : lines.getFirst()) + " tail=" + kind(lines.isEmpty() ? null : lines.getLast());
    }

    private static String kind(ErasureJournal.Line line) { return line == null ? "empty" : line.marker() ? "marker" : "line"; }
}
