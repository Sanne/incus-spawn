package dev.incusspawn.tool;

import dev.incusspawn.util.MemorySize;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much memory a template's tools are expected to need (#2355, design/templates.md "Tools
 * declare the memory they need"): the guest's own {@link #GUEST_BASELINE}, every tool's
 * {@code resident} memory, and the largest {@code peak} of any one tool, since tools hold their
 * resident memory together but rarely burst at the same time. An estimate, and said to be one.
 *
 * <p>The build works it out from the definitions of the whole chain and stamps it on the template
 * ({@link dev.incusspawn.incus.Metadata#MEMORY_NEED}); a branch reads the stamp from the source it
 * already read, so it loads no definition and makes no request for it.
 */
public record MemoryNeed(long baseline, List<Part> parts) {

    /**
     * What an idle guest holds before any tool runs: 133 MiB measured in a container of the base
     * image, rounded up to 160 MiB (docs/dev/PERFORMANCE-NOTES.md "Memory a template's tools need").
     */
    public static final long GUEST_BASELINE = 160 * MemorySize.MIB;

    /** One tool's declared need, with its parameters applied. */
    public record Part(String tool, long resident, long peak) {
        long most() {
            return resident + peak;
        }
    }

    /** A tool of the template, with the parameters it is built with. */
    public record Declared(String tool, ToolDef.Resources resources, Map<String, String> parameters) {}

    public MemoryNeed {
        parts = List.copyOf(parts);
    }

    /**
     * The need of {@code tools}, each named once (the caller has already let the nearest layer's
     * parameters win), or null when none declares any memory. An amount whose parameter does not
     * hold a size counts as nothing, and {@code skipped} hears which tool it was.
     */
    public static MemoryNeed of(Iterable<Declared> tools, java.util.function.Consumer<String> skipped) {
        var parts = new ArrayList<Part>();
        for (var tool : tools) {
            var resources = tool.resources();
            if (resources == null || !resources.declaresMemory()) continue;
            long resident = amount(tool, resources.resident(), skipped);
            long peak = amount(tool, resources.peak(), skipped);
            if (resident > 0 || peak > 0) parts.add(new Part(tool.tool(), resident, peak));
        }
        return parts.isEmpty() ? null : new MemoryNeed(GUEST_BASELINE, parts);
    }

    private static long amount(Declared tool, ToolDef.MemoryAmount amount, java.util.function.Consumer<String> skipped) {
        if (amount == null) return 0;
        var bytes = amount.resolve(tool.parameters());
        if (bytes < 0) {
            skipped.accept("Tool '" + tool.tool() + "' declares its memory from its parameter '" + amount.parameter()
                    + "', which does not hold a size, so its memory is not counted.");
            return 0;
        }
        return bytes;
    }

    /** The figure: the baseline, every resident amount and the largest peak. */
    public long total() {
        long resident = parts.stream().mapToLong(Part::resident).sum();
        long peak = parts.stream().mapToLong(Part::peak).max().orElse(0);
        return baseline + resident + peak;
    }

    /** The {@code n} tools that ask for the most, most first. */
    public List<Part> largest(int n) {
        return parts.stream().sorted(Comparator.comparingLong(Part::most).reversed()).limit(n).toList();
    }

    /** The stamp's value: JSON, so a branch can name the tools without their definitions. */
    public String stamp() {
        var tools = new ArrayList<Object>();
        for (var part : parts) {
            var tool = new LinkedHashMap<String, Object>();
            tool.put("name", part.tool());
            tool.put("resident", part.resident());
            tool.put("peak", part.peak());
            tools.add(tool);
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("baseline", baseline);
        root.put("tools", tools);
        return JqValues.serialize(JqValues.fromJavaObject(root));
    }

    /** The need a stamp holds, or null for none, or for a value isx did not write. */
    public static MemoryNeed fromStamp(String stamp) {
        if (stamp == null || stamp.isBlank()) return null;
        try {
            if (!(JqValues.parseStrict(stamp) instanceof JqObject root)) return null;
            var baseline = root.path("baseline").asLong(-1);
            if (baseline < 0 || !root.path("tools").isArray()) return null;
            var parts = new ArrayList<Part>();
            for (var tool : root.path("tools").asList()) {
                var name = tool.path("name").asText("");
                long resident = tool.path("resident").asLong(-1);
                long peak = tool.path("peak").asLong(-1);
                if (name.isEmpty() || resident < 0 || peak < 0) return null;
                parts.add(new Part(name, resident, peak));
            }
            return parts.isEmpty() ? null : new MemoryNeed(baseline, parts);
        } catch (RuntimeException notIsxs) {
            return null;
        }
    }

    /**
     * Warns on {@code out} when {@code given} bytes of memory are less than this need: names
     * {@code what} gets {@code givenShown}, the tools that ask for the most, the size to pass,
     * and that the figure is an estimate. {@code why}, when not null, says why isx gave no more.
     */
    public void warnIfShort(Output out, String what, long given, String givenShown, String why) {
        warnIfShort(out, what, given, givenShown, why, true);
    }

    /**
     * {@link #warnIfShort(Output, String, long, String, String)}, naming the {@code --memory} to
     * pass only when {@code memoryFlag}: {@code isx build} has no such option.
     */
    public void warnIfShort(Output out, String what, long given, String givenShown, String why, boolean memoryFlag) {
        var total = total();
        if (given < 0 || given >= total) return;
        var details = new ArrayList<String>();
        details.add("Asking the most: " + String.join(", ", largest(3).stream()
                .map(p -> OutputFormat.oneLine(p.tool()) + " " + MemorySize.shown(p.most())).toList()) + ".");
        if (why != null) details.add(why);
        if (memoryFlag) details.add("Pass --memory " + MemorySize.wholeGib(total) + " to give it what they are expected to need.");
        details.add("The figure is an estimate from what each tool declares, measured with its defaults.");
        out.warn(what + " gets " + givenShown + " of memory, but its tools are expected to need about "
                + MemorySize.shown(total) + ".", details.toArray(String[]::new));
    }
}
