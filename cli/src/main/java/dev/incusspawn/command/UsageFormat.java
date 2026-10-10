package dev.incusspawn.command;

import java.util.ArrayList;

/** Sizes, bars and counts as the TUI prints them: compact, fixed-width, and honest about "unknown". */
final class UsageFormat {

    private UsageFormat() {}

    // Eighth-block glyphs for sub-cell bar fill (index 0 = empty ... 8 = full block).
    private static final char[] BAR_EIGHTHS = {' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉', '█'};

    /** Compact "N running" badge split by instance type; empty when nothing is running. */
    static String runningSummary(int containers, int vms) {
        if (containers <= 0 && vms <= 0) return "";
        var parts = new ArrayList<String>();
        if (containers > 0) parts.add(containers + (containers == 1 ? " container" : " containers"));
        if (vms > 0) parts.add(vms + (vms == 1 ? " VM" : " VMs"));
        return String.join(", ", parts) + " running";
    }

    /** Compact GiB readout for the header, e.g. "8.7G" (&lt;10) or "14G" (&ge;10). */
    static String gibShort(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        return g < 10 ? String.format("%.1fG", g) : String.format("%.0fG", g);
    }

    /** Build a fractional-eighths bar string of {@code width} cells at {@code percent}. */
    static String bar(int percent, int width) {
        if (width <= 0) return "";
        int eighths = (int) Math.round(percent / 100.0 * width * 8);
        eighths = Math.max(0, Math.min(width * 8, eighths));
        int full = eighths / 8;
        int rem = eighths % 8;
        var sb = new StringBuilder();
        for (int i = 0; i < full && i < width; i++) sb.append('█');
        if (full < width && rem > 0) sb.append(BAR_EIGHTHS[rem]);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /** Format bytes as GiB with one decimal, e.g. "54.2 GiB". */
    static String gib(long bytes) {
        return String.format("%.1f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    static String diskCell(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return "~" + bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("~%.0fK", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("~%.0fM", mb);
        double gb = mb / 1024.0;
        return String.format("~%.1fG", gb);
    }
}
