package dev.incusspawn.incus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Constants and helpers for incus-spawn metadata stored on containers.
 */
public final class Metadata {

    public static final String PREFIX = "user.incus-spawn.";
    public static final String TYPE = PREFIX + "type";
    public static final String PROJECT = PREFIX + "project";
    public static final String CREATED = PREFIX + "created";
    public static final String PARENT = PREFIX + "parent";
    public static final String PROFILE = PREFIX + "profile";
    public static final String NETWORK_MODE = PREFIX + "network-mode";
    public static final String PROXY_GATEWAY = PREFIX + "proxy-gateway";
    public static final String BUILD_VERSION = PREFIX + "build-version";
    public static final String BUILD_SHA = PREFIX + "build-sha";
    public static final String CA_FINGERPRINT = PREFIX + "ca-fingerprint";
    public static final String HOST_RESOURCES = PREFIX + "host-resources";
    public static final String DEFINITION_SHA = PREFIX + "definition-sha";
    public static final String BUILD_SOURCE = PREFIX + "build-source";
    public static final String GUI_ENABLED = PREFIX + "gui-enabled";

    public static final String TYPE_BASE = "base";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_CLONE = "clone";
    public static final String TYPE_FAILED_BUILD = "failed-build";

    private Metadata() {}

    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** @deprecated Use {@link #now()} instead. */
    @Deprecated
    public static String today() {
        return now();
    }

    public static String ageDescription(String created) {
        try {
            // Try datetime first (new format), fall back to date-only (legacy)
            LocalDateTime createdTime;
            if (created.contains("T")) {
                createdTime = LocalDateTime.parse(created, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                createdTime = LocalDate.parse(created, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            }
            var now = LocalDateTime.now();
            var days = ChronoUnit.DAYS.between(createdTime.toLocalDate(), now.toLocalDate());
            var time = createdTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            if (days == 0) return "today " + time;
            if (days == 1) return "yesterday " + time;
            if (days < 7) return days + " days ago";
            if (days < 30) return (days / 7) + " weeks ago";
            return (days / 30) + " months ago";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
