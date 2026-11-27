package dev.incusspawn.command;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The archive {@code isx reset} and {@code isx clean config|all} write of what they delete from
 * the config directory, before deleting anything (#2666).
 *
 * <p>It is a gzipped POSIX tar, so {@code tar xzf} in {@code ~/.config} restores it, with every
 * name under the config directory's own name. It holds the directories, regular files and symlinks
 * that the sweep deletes, never a kept path, never anything under one. It is created only when no
 * file has its name, readable by its owner alone, and read back whole before it counts as written:
 * every entry's name, kind, link and SHA-256 must be the ones written. Any failure removes it and
 * throws, so the caller deletes nothing.
 *
 * <p>Written here rather than by {@code tar}, which is not the same program on Linux and macOS and
 * cannot be told exactly which entries to leave out and then checked against them.
 */
final class ConfigBackup {

    private ConfigBackup() {}

    private static final int BLOCK = 512;
    private static final long MAX_USTAR_SIZE = 077777777777L;

    /** One entry as written, and as it must read back. */
    record Entry(String name, char type, long size, String sha256, String link) {}

    /** Writes the archive of {@code dir} less {@code keep} to {@code archive}, and reads it back. */
    static List<Entry> write(Path dir, Set<Path> keep, Path archive) throws IOException {
        List<Entry> written;
        try (var channel = FileChannel.open(archive, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                ownerOnly());
             var gzip = new GZIPOutputStream(Channels.newOutputStream(channel), 64 * 1024)) {
            written = writeTar(dir, keep, gzip);
            gzip.finish();
            gzip.flush();
            channel.force(true);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IOException("the archive " + archive + " already exists, so nothing was deleted;"
                    + " move it away and try again", e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(archive);
            throw new IOException("could not write the archive " + archive + ", so nothing was deleted: "
                    + reason(e), e);
        }
        try {
            if (isPosix()) Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rw-------"));
            var read = read(archive);
            if (!read.equals(written)) {
                throw new IOException("it does not read back as it was written");
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(archive);
            throw new IOException("the archive " + archive + " did not read back whole, so nothing was deleted: "
                    + reason(e), e);
        }
        return written;
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<?>[] ownerOnly() {
        return isPosix()
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
    }

    private static List<Entry> writeTar(Path dir, Set<Path> keep, OutputStream out) throws IOException {
        var root = dir.getFileName().toString();
        var entries = new ArrayList<Entry>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (keep.contains(d)) return FileVisitResult.SKIP_SUBTREE;
                // A directory holding a kept path stays, so it is not in the archive; what it holds is
                if (keep.stream().noneMatch(k -> k.startsWith(d))) {
                    var name = nameOf(root, dir, d) + "/";
                    writeHeader(out, name, '5', 0, mode(d, 0755), attrs, "");
                    entries.add(new Entry(name, '5', 0, "", ""));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (keep.contains(file)) return FileVisitResult.CONTINUE;
                var name = nameOf(root, dir, file);
                if (attrs.isSymbolicLink()) {
                    var link = Files.readSymbolicLink(file).toString();
                    writeHeader(out, name, '2', 0, 0777, attrs, link);
                    entries.add(new Entry(name, '2', 0, "", link));
                } else if (attrs.isRegularFile()) {
                    entries.add(writeFile(out, name, file, attrs));
                }
                // A socket or a pipe holds nothing to keep
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
        out.write(new byte[2 * BLOCK]);
        return entries;
    }

    private static String nameOf(String root, Path dir, Path path) {
        var rel = dir.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        return rel.isEmpty() ? root : root + "/" + rel;
    }

    private static int mode(Path path, int fallback) {
        if (!isPosix()) return fallback;
        try {
            int mode = 0;
            for (var p : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) {
                mode |= switch (p) {
                    case OWNER_READ -> 0400;
                    case OWNER_WRITE -> 0200;
                    case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 040;
                    case GROUP_WRITE -> 020;
                    case GROUP_EXECUTE -> 010;
                    case OTHERS_READ -> 04;
                    case OTHERS_WRITE -> 02;
                    case OTHERS_EXECUTE -> 01;
                };
            }
            return mode;
        } catch (IOException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    private static Entry writeFile(OutputStream out, String name, Path file, BasicFileAttributes attrs)
            throws IOException {
        // Read whole below, so a file no array holds is refused rather than failing past the cleanup
        if (attrs.size() > Integer.MAX_VALUE - 8) throw new IOException(name + " is too large to archive");
        var digest = sha256();
        // Read once, whole, so the header's size is the size of what is written
        var content = Files.readAllBytes(file);
        long size = content.length;
        writeHeader(out, name, '0', size, mode(file, 0644), attrs, "");
        out.write(content);
        digest.update(content);
        pad(out, size);
        return new Entry(name, '0', size, HexFormat.of().formatHex(digest.digest()), "");
    }

    private static void pad(OutputStream out, long size) throws IOException {
        int rest = (int) (size % BLOCK);
        if (rest != 0) out.write(new byte[BLOCK - rest]);
    }

    private static void writeHeader(OutputStream out, String name, char type, long size, int mode,
                                    BasicFileAttributes attrs, String link) throws IOException {
        if (size > MAX_USTAR_SIZE) throw new IOException(name + " is too large to archive");
        var nameBytes = name.getBytes(StandardCharsets.UTF_8);
        var linkBytes = link.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100 || linkBytes.length > 100 || !plain(nameBytes) || !plain(linkBytes)) {
            var pax = new ByteArrayOutputStream();
            pax.write(paxRecord("path", name));
            if (!link.isEmpty()) pax.write(paxRecord("linkpath", link));
            var body = pax.toByteArray();
            out.write(header(shortName("PaxHeaders/" + name), 'x', body.length, 0644, 0, ""));
            out.write(body);
            pad(out, body.length);
        }
        out.write(header(shortName(name), type, size, mode, attrs.lastModifiedTime().toMillis() / 1000,
                shortName(link)));
    }

    /** Printable ASCII alone, which a ustar header holds as it is; any other byte needs a PAX record. */
    private static boolean plain(byte[] bytes) {
        for (var b : bytes) if (b < 0x20 || b == 0x7f) return false;
        return true;
    }

    /** At most 100 bytes of ASCII, for a header whose PAX record holds the whole name. */
    private static String shortName(String name) {
        var sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 100; i++) {
            char c = name.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '_');
        }
        return sb.toString();
    }

    private static byte[] paxRecord(String key, String value) {
        var payload = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
        int length = payload.length;
        // The length counts its own digits
        int total = length + String.valueOf(length).length();
        if (String.valueOf(total).length() != String.valueOf(length).length()) total++;
        var record = new ByteArrayOutputStream();
        record.writeBytes(String.valueOf(total).getBytes(StandardCharsets.US_ASCII));
        record.writeBytes(payload);
        return record.toByteArray();
    }

    private static byte[] header(String name, char type, long size, int mode, long mtime, String link) {
        var h = new byte[BLOCK];
        put(h, 0, 100, name);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, Math.max(0, mtime));
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        long sum = 0;
        for (var b : h) sum += b & 0xff;
        var chk = String.format("%06o", sum);
        put(h, 148, 6, chk);
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int at, int len, String value) {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, h, at, Math.min(len, bytes.length));
    }

    private static void octal(byte[] h, int at, int len, long value) {
        var digits = Long.toOctalString(value);
        var padded = "0".repeat(Math.max(0, len - 1 - digits.length())) + digits;
        put(h, at, len - 1, padded);
        h[at + len - 1] = 0;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The entries of {@code archive}, each file's content hashed, the gzip trailer checked. */
    static List<Entry> read(Path archive) throws IOException {
        try (var in = new GZIPInputStream(Files.newInputStream(archive), 64 * 1024)) {
            var entries = new ArrayList<Entry>();
            String paxPath = null;
            String paxLink = null;
            var h = new byte[BLOCK];
            while (true) {
                readFully(in, h);
                if (allZero(h)) {
                    readFully(in, h);
                    if (!allZero(h)) throw new IOException("a lone end block");
                    break;
                }
                checkSum(h);
                char type = (char) h[156];
                long size = parseOctal(h, 124, 12);
                if (type == 'x') {
                    var body = new byte[Math.toIntExact(size)];
                    readFully(in, body);
                    skip(in, padding(size));
                    for (var rec : parsePax(body)) {
                        if (rec[0].equals("path")) paxPath = rec[1];
                        else if (rec[0].equals("linkpath")) paxLink = rec[1];
                    }
                    continue;
                }
                var name = paxPath != null ? paxPath : text(h, 0, 100);
                var link = paxLink != null ? paxLink : text(h, 157, 100);
                paxPath = null;
                paxLink = null;
                switch (type) {
                    case '0' -> {
                        var digest = sha256();
                        var buf = new byte[8192];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new EOFException("the archive ends inside " + name);
                            digest.update(buf, 0, n);
                            left -= n;
                        }
                        skip(in, padding(size));
                        entries.add(new Entry(name, '0', size, HexFormat.of().formatHex(digest.digest()), ""));
                    }
                    case '2' -> entries.add(new Entry(name, '2', 0, "", link));
                    case '5' -> entries.add(new Entry(name, '5', 0, "", ""));
                    default -> throw new IOException("an entry of unknown type " + type);
                }
            }
            // To the end, so the gzip trailer's CRC and length are checked
            var rest = new byte[8192];
            while (in.read(rest) >= 0) {
                for (var b : rest) if (b != 0) throw new IOException("data after the end of the archive");
            }
            return entries;
        }
    }

    private static List<String[]> parsePax(byte[] body) throws IOException {
        var records = new ArrayList<String[]>();
        int at = 0;
        while (at < body.length) {
            int space = at;
            while (space < body.length && body[space] != ' ') space++;
            if (space >= body.length) throw new IOException("a broken PAX record");
            int length = Integer.parseInt(new String(body, at, space - at, StandardCharsets.US_ASCII));
            var record = new String(body, space + 1, length - (space - at) - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq < 0) throw new IOException("a broken PAX record");
            records.add(new String[]{record.substring(0, eq), record.substring(eq + 1)});
            at += length;
        }
        return records;
    }

    private static void checkSum(byte[] h) throws IOException {
        long expected = parseOctal(h, 148, 8);
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        if (sum != expected) throw new IOException("a header whose checksum does not match");
    }

    private static long parseOctal(byte[] h, int at, int len) throws IOException {
        int i = at;
        while (i < at + len && h[i] == ' ') i++;
        long value = 0;
        for (; i < at + len && h[i] != 0 && h[i] != ' '; i++) {
            if (h[i] < '0' || h[i] > '7') throw new IOException("a header that is not octal");
            value = value * 8 + (h[i] - '0');
        }
        return value;
    }

    private static String text(byte[] h, int at, int len) {
        int end = at;
        while (end < at + len && h[end] != 0) end++;
        return new String(h, at, end - at, StandardCharsets.US_ASCII);
    }

    private static boolean allZero(byte[] h) {
        for (var b : h) if (b != 0) return false;
        return true;
    }

    private static long padding(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int at = 0;
        while (at < buf.length) {
            int n = in.read(buf, at, buf.length - at);
            if (n < 0) throw new EOFException("the archive ends early");
            at += n;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        var buf = new byte[(int) n];
        readFully(in, buf);
    }
}
