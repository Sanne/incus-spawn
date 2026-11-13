package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.function.Function;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which base image release an instance runs, and how it compares with the one a rebuild would
 * use now (#1657). A fix in a base image reaches only instances created after it, so an old
 * template and every branch of it keep the old image's bugs.
 * Nothing is stamped for this: Incus copies an image's properties onto an instance created from
 * it as {@code image.*}, and the build stamps {@code incus-spawn.tag} on every base image it
 * imports, so the tag is already on each template, and a copy keeps it. So is the image's
 * fingerprint ({@code volatile.base_image}), which for a base image is its tarball's sha256.
 * It reads only the listing's entry and the definitions it is handed.
 */
public final class BaseImageAge {

    /** The release tag of the base image, as Incus copied it from the image's properties. */
    public static final String TAG = "image." + BuildCommand.IMAGE_TAG_PROPERTY;
    /** The image the instance was created from: for a base image, its tarball's sha256. */
    static final String FINGERPRINT = "volatile.base_image";

    /**
     * A release tag, as incus-spawn-images' {@code release-tag.sh} names it (#1994):
     * {@code <distribution>-<release>[-<channel>]-<yyyymmdd>[-<n>]}, as {@code fedora-45-20261111},
     * {@code fedora-45-20261111-2} for a second release that day, or {@code fedora-45-next-20261120}
     * on a channel. The distribution's words start with a letter, so the release is the first number
     * after them; a channel is lower-case letters only, so the line ends where the date starts.
     */
    private static final Pattern RELEASE_TAG = Pattern.compile(
            "([A-Za-z][^-]*(?:-[A-Za-z][^-]*)*)-(\\d{1,9})(?:-([a-z]+))?-(\\d{8})(?:-(\\d{1,9}))?");

    /**
     * The one release published under a typed tag before every tag was generated: the first
     * Fedora 45 release, built on 2026-11-04, on the stable line. It orders before that day's
     * first dated release; no other such tag is ever published again.
     */
    static final String LEGACY_TAG = "fedora-45-v1";
    private static final ReleaseTag LEGACY = new ReleaseTag("fedora", 45, "", "20261104", 0);

    /**
     * A parsed release tag. Its line is the distribution, release and channel ({@code fedora-45},
     * {@code fedora-45-next}): tags order by build date and that day's number only within a line,
     * and a line is never newer or older than another, since a later branch is not always better.
     *
     * @param channel empty on the stable line
     * @param serial  the release's number among those of its day: 1 without a suffix
     */
    public record ReleaseTag(String distribution, int release, String channel, String date, int serial) {
        /** The tag parsed, or null when it is not of the scheme and not the legacy tag. */
        public static ReleaseTag parse(String tag) {
            if (tag == null) return null;
            if (LEGACY_TAG.equals(tag)) return LEGACY;
            var m = RELEASE_TAG.matcher(tag);
            if (!m.matches()) return null;
            return new ReleaseTag(m.group(1), Integer.parseInt(m.group(2)), m.group(3) == null ? "" : m.group(3),
                    m.group(4), m.group(5) == null ? 1 : Integer.parseInt(m.group(5)));
        }

        /** The line, as {@code fedora-45} or {@code fedora-45-next}. */
        public String line() {
            return distribution + "-" + release + (channel.isEmpty() ? "" : "-" + channel);
        }

        /** The order of two tags of one line: build date, then that day's number; null across lines. */
        public Integer compareWithin(ReleaseTag other) {
            if (!line().equals(other.line())) return null;
            int byDate = date.compareTo(other.date);
            return byDate != 0 ? byDate : Integer.compare(serial, other.serial);
        }
    }

    private BaseImageAge() {}

    /**
     * {@code OTHER_LINE}: both tags parse but name different lines (another Fedora release or
     * another channel), which a rebuild would move the instance off, but which is neither older nor
     * newer. {@code UNKNOWN}: Incus recorded no tag, or one of the two does not parse.
     */
    public enum Standing { CURRENT, OLDER, NEWER, OTHER_LINE, UNKNOWN }

    /**
     * @param tag         the release the instance's base image came from, or null if Incus recorded none
     * @param expectedTag the release its root definition names now
     */
    public record Assessment(Standing standing, String tag, String expectedTag) {}

    /**
     * A base image release that fixed a problem every instance on an earlier one keeps. isx
     * doctor warns about an instance that lacks one a rebuild would bring; a routine newer
     * release is only a note, so it does not fail doctor every time a base image is published.
     * Add one when a base-image fix is worth a rebuild for everyone still on an older release.
     */
    public record KnownFix(String release, String problem) {}

    static final List<KnownFix> KNOWN_FIXES = List.of(
            new KnownFix("fedora-44-20261009", "its network watchdog restarts systemd-networkd every 30 seconds"));

    /**
     * The known fixes an instance lacks that a rebuild to the release it is assessed against would
     * bring. It lacks one only on the fix's own line ({@code fedora-44}) and before it: a date on
     * another line says nothing about which scripts that image was built from. A rebuild brings it
     * when the release assessed against is that line's fix or later, or on another line: a known
     * problem of the line a rebuild moves to would be listed for that line.
     */
    public static List<KnownFix> missingFixes(Assessment age) {
        return missingFixes(age, KNOWN_FIXES);
    }

    static List<KnownFix> missingFixes(Assessment age, List<KnownFix> fixes) {
        if (age == null || (age.standing() != Standing.OLDER && age.standing() != Standing.OTHER_LINE)) return List.of();
        return fixes.stream().filter(fix -> {
            var lacks = compareTags(age.tag(), fix.release());
            if (lacks == null || lacks >= 0) return false;
            var brings = compareTags(age.expectedTag(), fix.release());
            return brings == null ? sameLine(age.expectedTag(), fix.release()) == Boolean.FALSE : brings >= 0;
        }).toList();
    }

    /** The base image release an instance's entry records, or null. */
    public static String tag(JqValue instance) {
        return instance == null ? null : tagIn(instance.getField("config"));
    }

    private static String tagIn(JqValue config) {
        var tag = config.pathText(TAG, "");
        return tag.isEmpty() ? null : tag;
    }

    /**
     * How the base image of {@code instance} (an entry of an Incus listing) compares with its
     * root definition in {@code defs}, which has any base-image pin applied. Null when there is
     * nothing to compare with: a template not among {@code defs}, or a root that downloads no
     * base image release.
     */
    public static Assessment assess(JqValue instance, Map<String, ImageDef> defs) {
        if (instance == null || defs == null) return null;
        var config = instance.getField("config");
        var template = Metadata.templateOf(config);
        var def = template == null ? null : defs.get(template);
        if (def == null) return null;
        var chain = ImageDef.chain(def, defs);
        var root = chain.getFirst();
        if (!root.isRoot() || root.getImageUrl() == null || root.getImageTag() == null) return null;

        var tag = tagIn(config);
        var expected = root.getImageTag();
        var fingerprint = config.pathText(FINGERPRINT, "");
        var arch = instance.pathText("architecture", "");
        var sums = MachineType.fromIncus(instance) == MachineType.VM ? root.getVmImageSha256() : root.getImageSha256();
        // The file itself first: a release can re-publish an unchanged asset, and its image then
        // keeps the tag it was first imported under, which says nothing about its age.
        if (!fingerprint.isEmpty() && sums != null && fingerprint.equalsIgnoreCase(sums.get(arch))) {
            return new Assessment(Standing.CURRENT, tag, expected);
        }
        var order = tag == null ? null : compareTags(tag, expected);
        var standing = order == null ? (sameLine(tag, expected) == Boolean.FALSE ? Standing.OTHER_LINE : Standing.UNKNOWN)
                : order < 0 ? Standing.OLDER
                : order > 0 ? Standing.NEWER
                : Standing.CURRENT;
        return new Assessment(standing, tag, expected);
    }

    /** Whether two release tags are of one line, as {@code fedora-45}; null when either does not parse. */
    static Boolean sameLine(String a, String b) {
        var ta = ReleaseTag.parse(a);
        var tb = ReleaseTag.parse(b);
        return ta == null || tb == null ? null : ta.line().equals(tb.line());
    }

    /**
     * The order of two release tags of one line: build date, then that day's number, where a tag
     * without one is that day's first; null when either does not parse or they are of different
     * lines (#1994), which are neither older nor newer than each other.
     */
    static Integer compareTags(String a, String b) {
        var ta = ReleaseTag.parse(a);
        var tb = ReleaseTag.parse(b);
        return ta == null || tb == null ? null : ta.compareWithin(tb);
    }

    /**
     * The newest of {@code releases} on {@code builtinTag}'s line, which is what an unpinned build
     * and {@code isx update-base --latest} follow (#1994): a release of another Fedora or another
     * channel is never taken by itself. Null when none is on that line. When the built-in's tag
     * is not of the scheme, the first release listed, as GitHub lists the newest first.
     */
    static <R> R newestOnLine(List<R> releases, Function<R, String> tagOf, String builtinTag) {
        if (releases.isEmpty()) return null;
        var line = ReleaseTag.parse(builtinTag);
        if (line == null) return releases.getFirst();
        R newest = null;
        ReleaseTag newestTag = null;
        for (var release : releases) {
            var tag = ReleaseTag.parse(tagOf.apply(release));
            if (tag == null || !line.line().equals(tag.line())) continue;
            if (newestTag == null || tag.compareWithin(newestTag) > 0) {
                newest = release;
                newestTag = tag;
            }
        }
        return newest;
    }

    /**
     * The release tracking the latest takes over {@code builtinTag}: the newest on its line, as
     * {@link #newestOnLine}, or null when none is listed there or the built-in is newer than any
     * listed. An unpinned build and {@code isx update-base --latest} both read it.
     */
    static <R> R trackedRelease(List<R> releases, Function<R, String> tagOf, String builtinTag) {
        var newest = newestOnLine(releases, tagOf, builtinTag);
        if (newest == null) return null;
        var order = compareTags(tagOf.apply(newest), builtinTag);
        return order != null && order < 0 ? null : newest;
    }
}
