package dev.incusspawn.config;

/**
 * Who chose an instance's pinned account for one credential namespace, as recorded beside the
 * pin ({@link dev.incusspawn.incus.Metadata#accountOriginKey}).
 *
 * <p>A pin is always stored on the instance, whoever chose it: {@code isx branch} copies the
 * template's {@code accounts:} onto the branch in the same way as an {@code --account}. Without
 * this record the two are indistinguishable, and {@code isx account show} could only guess from
 * whether the pin happens to match the template -- a guess that is wrong as soon as someone
 * explicitly picks the template's account, or the template changes.
 *
 * @param kind   what chose it
 * @param source the template or instance named by {@link Kind#TEMPLATE} and {@link Kind#COPIED},
 *               otherwise {@code ""}
 */
public record AccountOrigin(Kind kind, String source) {

    public enum Kind {
        /** A template's {@code accounts:}: stamped by its build, copied onto its branches. */
        TEMPLATE,
        /** An explicit choice for this instance: {@code --account}, {@code isx account set}, the TUI. */
        EXPLICIT,
        /** An explicit choice made on another instance, copied when this one was branched from it. */
        COPIED,
        /** Pinned before isx recorded origins. */
        UNKNOWN
    }

    public static final AccountOrigin EXPLICIT = new AccountOrigin(Kind.EXPLICIT, "");
    public static final AccountOrigin UNKNOWN = new AccountOrigin(Kind.UNKNOWN, "");

    public static AccountOrigin template(String template) {
        return new AccountOrigin(Kind.TEMPLATE, template);
    }

    public static AccountOrigin copiedFrom(String instance) {
        return new AccountOrigin(Kind.COPIED, instance);
    }

    /** The stored form: {@code template:<name>}, {@code explicit}, {@code copied:<instance>}. */
    public String encode() {
        return switch (kind) {
            case TEMPLATE -> "template:" + source;
            case EXPLICIT -> "explicit";
            case COPIED -> "copied:" + source;
            case UNKNOWN -> "";
        };
    }

    /** Anything unrecognised -- absent, from a newer isx, hand-edited -- reads as {@link #UNKNOWN}. */
    public static AccountOrigin decode(String value) {
        if (value == null) return UNKNOWN;
        var v = value.strip();
        if (v.equals("explicit")) return EXPLICIT;
        if (v.startsWith("template:") && v.length() > "template:".length()) {
            return template(v.substring("template:".length()));
        }
        if (v.startsWith("copied:") && v.length() > "copied:".length()) {
            return copiedFrom(v.substring("copied:".length()));
        }
        return UNKNOWN;
    }

    /**
     * What a branch records for a pin it copies from its source: a template's choice stays the
     * template's, and anything chosen explicitly (or unrecorded) becomes "copied from" the source,
     * since that is the instance where someone chose it.
     */
    public AccountOrigin copiedOnto(String sourceInstance) {
        return switch (kind) {
            case TEMPLATE, COPIED -> this;
            case EXPLICIT, UNKNOWN -> copiedFrom(sourceInstance);
        };
    }
}
