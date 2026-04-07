package dev.incusspawn.incus;

import java.time.LocalDate;
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

    public static final String TYPE_BASE = "base";
    public static final String TYPE_PROJECT = "project";
    public static final String TYPE_CLONE = "clone";

    private Metadata() {}

    public static String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String ageDescription(String createdDate) {
        try {
            var created = LocalDate.parse(createdDate, DateTimeFormatter.ISO_LOCAL_DATE);
            var days = ChronoUnit.DAYS.between(created, LocalDate.now());
            if (days == 0) return "today";
            if (days == 1) return "1 day ago";
            if (days < 7) return days + " days ago";
            if (days < 30) return (days / 7) + " weeks ago";
            return (days / 30) + " months ago";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
