package dev.incusspawn.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * What {@link TaskScripts#diff} printed, as {@code get_diff}'s structure and the text the model
 * reads, which is rendered from that structure: per repository, each file's lines added and
 * removed and a total, then the patch.
 */
record Diff(String text, ObjectNode structured) {

    private static final String TOO_LARGE = "(too large: ";

    static Diff parse(String out, boolean statOnly) {
        var node = JsonRpc.JSON.createObjectNode();
        var repos = node.putArray("repos");
        if (!out.startsWith("## ") && !out.startsWith("---\n")) {
            // No repository recorded: the script says so in a sentence.
            return new Diff(out.strip(), node);
        }
        var text = new StringBuilder(out.length());
        int pos = 0;
        while (out.startsWith("## ", pos)) {
            var eol = out.indexOf('\n', pos);
            if (eol < 0) eol = out.length();
            var repo = repos.addObject();
            var name = out.substring(pos + 3, eol);
            repo.put("repo", name);
            var files = repo.putArray("files");
            text.append("## ").append(name).append('\n');
            pos = eol + 1;
            long added = 0, deleted = 0;
            int count = 0;
            while (pos < out.length() && out.charAt(pos) != '\n') {
                var end = out.indexOf('\0', pos);
                if (end < 0) end = out.length();
                var fields = out.substring(pos, end).split("\t", 3);
                pos = end + 1;
                if (fields.length < 3) continue;
                var file = files.addObject();
                file.put("path", fields[2]);
                var a = count(fields[0]);
                var d = count(fields[1]);
                if (a == null) file.putNull("added"); else file.put("added", a);
                if (d == null) file.putNull("deleted"); else file.put("deleted", d);
                added += a == null ? 0 : a;
                deleted += d == null ? 0 : d;
                count++;
                text.append(fields[0]).append('\t').append(fields[1]).append('\t').append(fields[2]).append('\n');
            }
            pos++; // the empty line that ends the repository
            if (count > 0) text.append(shortstat(count, added, deleted)).append('\n');
        }
        text.append("---\n");
        if (out.startsWith("---\n", pos)) pos += 4;
        if (!statOnly) {
            var rest = pos < out.length() ? out.substring(pos) : "";
            if (rest.startsWith(TOO_LARGE)) {
                node.put("too_large", true);
                var close = rest.indexOf(' ', TOO_LARGE.length());
                try {
                    node.put("patch_bytes", Long.parseLong(rest.substring(TOO_LARGE.length(), close)));
                } catch (RuntimeException e) {
                    // the size is for the reader; without it the patch is still too large
                }
            } else {
                node.put("too_large", false);
                node.put("patch", rest);
                node.put("patch_bytes", rest.getBytes(StandardCharsets.UTF_8).length);
            }
            text.append(rest);
        }
        return new Diff(text.toString(), node);
    }

    /** What {@code git diff --shortstat} says: a count that is zero is left out, unless both are. */
    static String shortstat(int files, long added, long deleted) {
        var sb = new StringBuilder(" ").append(files).append(files == 1 ? " file changed" : " files changed");
        if (added > 0 || deleted == 0) sb.append(", ").append(added).append(added == 1 ? " insertion(+)" : " insertions(+)");
        if (deleted > 0 || added == 0) sb.append(", ").append(deleted).append(deleted == 1 ? " deletion(-)" : " deletions(-)");
        return sb.toString();
    }

    /** A line count, or null for {@code -}, which git prints for a binary file. */
    private static Integer count(String field) {
        try {
            return Integer.valueOf(field);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
