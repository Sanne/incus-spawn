package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
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
     * A release tag: a distribution, its release (an int), then either the build date and
     * {@code -2}, {@code -3} ... for a later release the same day, as incus-spawn-images'
     * {@code release-tag.sh} names it ({@code fedora-44-20261021}), or anything else, as the
     * explicit tag its release workflow accepts ({@code fedora-45-v1}, #1988), which has no date.
     * The distribution's words start with a letter, so the release is the first number after them.
     */
    private static final Pattern RELEASE_TAG =
            Pattern.compile("([A-Za-z][^-]*(?:-[A-Za-z][^-]*)*)-(\\d{1,9})-(?:(\\d{8})(?:-(\\d{1,9}))?|.+)");

    private BaseImageAge() {}

    public enum Standing { CURRENT, OLDER, NEWER, UNKNOWN }

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
     * bring. It lacks one only on the fix's own release line ({@code fedora-44}) and before it: a
     * date on another release says nothing about when that image was built from the fixed scripts.
     */
    public static List<KnownFix> missingFixes(Assessment age) {
        return missingFixes(age, KNOWN_FIXES);
    }

    static List<KnownFix> missingFixes(Assessment age, List<KnownFix> fixes) {
        if (age == null || age.standing() != Standing.OLDER) return List.of();
        return fixes.stream().filter(fix -> {
            var lacks = compareTags(age.tag(), fix.release());
            var brings = compareTags(age.expectedTag(), fix.release());
            return lacks != null && lacks < 0 && sameLine(age.tag(), fix.release()) && brings != null && brings >= 0;
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
        var standing = order == null ? Standing.UNKNOWN
                : order < 0 ? Standing.OLDER
                : order > 0 ? Standing.NEWER
                : Standing.CURRENT;
        return new Assessment(standing, tag, expected);
    }

    /** Whether two release tags name the same distribution and release, as {@code fedora-44}. */
    static boolean sameLine(String a, String b) {
        var ma = RELEASE_TAG.matcher(a);
        var mb = RELEASE_TAG.matcher(b);
        return ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1)) && ma.group(2).equals(mb.group(2));
    }

    /**
     * The order of two release tags of one distribution: release, build date, then the same day's
     * number, where a tag without one is that day's first; null
     * when either does not parse or they name different distributions.
     * A tag without a date ({@code fedora-45-v1}) orders only against another release, or itself.
     */
    static Integer compareTags(String a, String b) {
        var ma = RELEASE_TAG.matcher(a);
        var mb = RELEASE_TAG.matcher(b);
        if (!ma.matches() || !mb.matches() || !ma.group(1).equals(mb.group(1))) return null;
        int byRelease = Integer.compare(Integer.parseInt(ma.group(2)), Integer.parseInt(mb.group(2)));
        if (byRelease != 0) return byRelease;
        if (a.equals(b)) return 0;
        if (ma.group(3) == null || mb.group(3) == null) return null;
        int byDate = ma.group(3).compareTo(mb.group(3));
        return byDate != 0 ? byDate : Integer.compare(sameDay(ma.group(4)), sameDay(mb.group(4)));
    }

    /** A release's number among those of its day: {@code -2} is the second, no suffix the first. */
    private static int sameDay(String suffix) {
        return suffix == null ? 1 : Integer.parseInt(suffix);
    }
}
