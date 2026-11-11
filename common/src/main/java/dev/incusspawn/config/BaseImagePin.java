package dev.incusspawn.config;

import dev.incusspawn.OwnDirectory;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ~/.config/incus-spawn/base-image-pin.yaml}, written by {@code isx update-base <tag>}: the
 * base-image release {@code tpl-minimal} downloads, and nothing else (#1563).
 *
 * <pre>
 * image: fedora-44-base
 * image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}.tar.xz
 * vm_image_url: ...      # absent when the release has no VM image for every arch
 * image_tag: fedora-44-20261020
 * image_sha256: {x86_64: ..., aarch64: ...}
 * vm_image_sha256: {x86_64: ..., aarch64: ...}
 * </pre>
 *
 * It is applied to the {@code tpl-minimal} of the trusted layers, so every other field of the
 * template comes from whichever definition is in effect: a built-in change reaches a pinned user,
 * and a pin keeps a user's own edits. It carries the image's name and URLs as well as its tag, so a
 * definition moved to another release's file names never pairs them with the pinned checksums.
 */
@JqInclude(JqInclude.Include.NON_NULL)
@JqMapped
public class BaseImagePin extends MappedDefinition {

    public static final String FILE_NAME = "base-image-pin.yaml";
    /** The template a pin applies to: the root every built-in template starts from. */
    public static final String TEMPLATE = "tpl-minimal";
    /** The architectures base images are published for: a pin holds a checksum for each. */
    public static final java.util.List<String> ARCHES = java.util.List.of("x86_64", "aarch64");

    /**
     * The keys an {@code isx update-base} before #1563 wrote into a user {@code minimal.yaml}. Such
     * a file with {@code pinned: true} and no other key is that command's, not the user's.
     */
    private static final Set<String> LEGACY_PIN_KEYS = Set.of("name", "description", "remove_packages",
            "mask_services", "image", "image_url", "vm_image_url", "image_tag", "pinned", "image_sha256",
            "vm_image_sha256");

    private String image;
    @JqName("image_url")
    private String imageUrl;
    @JqName("vm_image_url")
    private String vmImageUrl;
    @JqName("image_tag")
    private String imageTag;
    @JqName("image_sha256")
    private Map<String, String> imageSha256;
    @JqName("vm_image_sha256")
    private Map<String, String> vmImageSha256;

    public BaseImagePin() {}

    public BaseImagePin(String image, String imageUrl, String vmImageUrl, String imageTag,
                        Map<String, String> imageSha256, Map<String, String> vmImageSha256) {
        this.image = image;
        this.imageUrl = imageUrl;
        this.vmImageUrl = vmImageUrl;
        this.imageTag = imageTag;
        this.imageSha256 = imageSha256;
        this.vmImageSha256 = vmImageSha256;
    }

    public static Path file() { return SpawnConfig.configDir().resolve(FILE_NAME); }

    /** Where an {@code isx update-base} before #1563 wrote its pin. */
    public static Path legacyFile() { return ImageDef.userImagesDir().resolve("minimal.yaml"); }

    /** Whether {@code def} was loaded from a pin an earlier {@code isx update-base} wrote. */
    public static boolean isLegacyPin(ImageDef def) {
        var legacy = legacyFile();
        return legacy.toAbsolutePath().normalize().toString().equals(def.getSource()) && isLegacyPin(legacy);
    }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getVmImageUrl() { return vmImageUrl; }
    public void setVmImageUrl(String vmImageUrl) { this.vmImageUrl = vmImageUrl; }
    public String getImageTag() { return imageTag; }
    public void setImageTag(String imageTag) { this.imageTag = imageTag; }
    public Map<String, String> getImageSha256() { return imageSha256; }
    public void setImageSha256(Map<String, String> imageSha256) { this.imageSha256 = imageSha256; }
    public Map<String, String> getVmImageSha256() { return vmImageSha256; }
    public void setVmImageSha256(Map<String, String> vmImageSha256) { this.vmImageSha256 = vmImageSha256; }

    /** One warning per key isx ignores in this file, as for a definition (#1546). */
    public java.util.List<String> unknownKeyWarnings() {
        return new Walk().part("", this).warnings();
    }

    @Override
    protected java.util.Collection<String> knownKeys() {
        // Every key, not only those set: the mapping writes non-null ones alone
        return Jjq.mapper().toJqValue(new BaseImagePin("", "", "", "", Map.of(), Map.of())).asMap().keySet();
    }

    /** Why this pin cannot be applied, or null. */
    String problem() {
        if (image == null || image.isBlank()) return "no 'image'";
        if (imageUrl == null || imageUrl.isBlank()) return "no 'image_url'";
        if (imageTag == null || imageTag.isBlank()) return "no 'image_tag'";
        // A pin is used wherever it is copied: an arch it has no checksum for would download unverified
        if (imageSha256 == null || !imageSha256.keySet().containsAll(ARCHES)) {
            return "'image_sha256' does not list " + String.join(" and ", ARCHES);
        }
        if (vmImageUrl != null && (vmImageSha256 == null || !vmImageSha256.keySet().containsAll(ARCHES))) {
            return "'vm_image_url' without 'vm_image_sha256' for " + String.join(" and ", ARCHES);
        }
        return null;
    }

    /**
     * Whether this pin may replace {@code def}'s base image: only when {@code def} still downloads
     * from the repository the pin's release was taken from, so its checksums are never paired with
     * another publisher's files.
     */
    boolean appliesTo(ImageDef def) {
        return Objects.equals(releaseRepository(imageUrl), releaseRepository(def.getImageUrl()));
    }

    /** Puts the pinned release into {@code def}, which is then {@code pinned}. */
    void applyTo(ImageDef def) {
        def.setImage(image);
        def.setImageUrl(imageUrl);
        def.setVmImageUrl(vmImageUrl);
        def.setImageTag(imageTag);
        def.setImageSha256(imageSha256);
        def.setVmImageSha256(vmImageUrl != null ? vmImageSha256 : null);
        def.setPinned(true);
    }

    /** What a release URL is published under: everything before its tag, or the URL itself. */
    private static String releaseRepository(String url) {
        if (url == null) return null;
        int at = url.indexOf("/releases/download/");
        return at >= 0 ? url.substring(0, at) : url;
    }

    public static BaseImagePin read(Path file) throws IOException {
        var pin = Jjq.baseImagePin(file);
        return pin != null ? pin : new BaseImagePin(); // a document of just `~`
    }

    public void write(Path file) throws IOException {
        OwnDirectory.create(file.getParent());
        Jjq.writeYaml(file, this);
    }

    /**
     * Whether {@code file} is a pin an {@code isx update-base} before #1563 wrote: a definition that
     * replaces the built-in {@code tpl-minimal} wholesale, so built-in changes never reach it. False
     * for a definition with any key that command never wrote, which is the user's own.
     */
    public static boolean isLegacyPin(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            JqValue root = JqYaml.parse(Files.readString(file));
            if (!root.isObject()) return false;
            var map = root.asMap();
            if (!(LEGACY_PIN_KEYS.containsAll(map.keySet())
                    && map.get("pinned") != null && "true".equals(Jjq.text(map.get("pinned")))
                    && map.get("name") != null && TEMPLATE.equals(Jjq.text(map.get("name"))))) {
                return false;
            }
            // From #1561 that command copied these lists from the definition in effect, which could be
            // the user's own: lists other than the built-in's are a choice the user made, so the file is theirs
            var def = Jjq.imageDef(file);
            var builtin = ImageDef.loadBuiltinByName(TEMPLATE);
            return builtin != null && (sameOrAbsent(map, "mask_services", def.getMaskServices(), builtin.getMaskServices())
                    && sameOrAbsent(map, "remove_packages", def.getRemovePackages(), builtin.getRemovePackages()));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean sameOrAbsent(Map<String, JqValue> map, String key, java.util.List<String> pinned,
                                        java.util.List<String> builtin) {
        return !map.containsKey(key) || Set.copyOf(pinned).equals(Set.copyOf(builtin));
    }
}
