package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code upstream/manifest.yaml} (#2036) is what the upstream watch may bump without a person: an
 * item it lists as {@code mechanical} gets a PR whose every value is the publisher's, verified by
 * the method its {@code verify.type} names, and merged by the bot once {@code upstream-verify}
 * regenerates it; a {@code review} item (#2210) gets the same proven PR, which only Sanne merges.
 * So the manifest is a policy file, and these tests hold its shape statically: an item the engine
 * bumps is fully specified, its verification keys name committed files and the pinned
 * fingerprint, a third-party source cools for a day, and no key anywhere switches a check off.
 * {@code upstream.sh validate} holds the same rules at run time, with the source types loaded, and
 * the last test runs it so the two cannot drift apart unnoticed.
 */
class UpstreamManifestTest {

    private static final Path ROOT = Path.of("..");
    private static final Path MANIFEST = ROOT.resolve("upstream/manifest.yaml");
    private static final Path ENGINE = ROOT.resolve("upstream/engine");
    private static final Path PIN_SCRIPT = ROOT.resolve("appliance/kernel/update-kernel-pin.sh");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("repo", "app", "items");
    private static final Set<String> ITEM_KEYS = Set.of(
            "class", "within", "cooling", "judgement", "source", "verify", "files", "fields", "gates");
    private static final Pattern OWNER_NAME = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
    private static final Pattern APP_LOGIN = Pattern.compile("[A-Za-z0-9-]+\\[bot\\]");
    /** What {@code lib.sh}'s {@code duration_seconds} accepts: {@code 0}, or a count of h, d or m. */
    private static final Pattern DURATION = Pattern.compile("0|[0-9]+[hdm]");
    private static final Pattern CHECK_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern FINGERPRINT_PIN = Pattern.compile("(?m)^AUTOSIGNER_FPR=\"([0-9A-F]{40})\"$");
    /** A key that would read as "do not check this", whatever its value. */
    private static final Pattern PROTECTION_SWITCH = Pattern.compile("(?i)skip|disable|auto-?merge");
    /**
     * The one source type whose publisher may be first party, so the one a cooling under the 24h floor
     * may go with (see {@link #coolingUnderADayIsOnlyForAFirstPartySource}).
     */
    private static final String FIRST_PARTY_SOURCE_TYPE = "github-release";

    private static JqValue manifest;

    @BeforeAll
    static void parse() throws IOException {
        manifest = JqYaml.parse(Files.readString(MANIFEST));
        assertTrue(manifest.isObject(), MANIFEST + " is not a mapping");
    }

    private static Map<String, JqValue> items() {
        var items = manifest.path("items");
        assertTrue(items.isObject() && items.size() > 0, "manifest has no items");
        return items.asMap();
    }

    /** The classes whose bump the engine writes as a PR: {@code lib.sh}'s {@code item_proposed}. */
    private static final Set<String> PROPOSED_CLASSES = Set.of("mechanical", "review");

    private static Map<String, JqValue> proposedItems() {
        var proposed = new java.util.LinkedHashMap<String, JqValue>();
        items().forEach((name, item) -> {
            if (PROPOSED_CLASSES.contains(item.pathText("class", "judgement"))) proposed.put(name, item);
        });
        assertFalse(proposed.isEmpty(), "manifest has no item the engine bumps");
        return proposed;
    }

    private static List<String> strings(JqValue list) {
        return list.asList().stream().map(JqValue::asText).toList();
    }

    /** A scalar as written, "" when absent: {@code cooling: 0} is a number to the parser and a duration here. */
    private static String text(JqValue node, String key) {
        var value = node.path(key);
        return value.isNull() ? "" : value.asText();
    }

    private static long seconds(String duration) {
        if (duration.equals("0")) return 0;
        var n = Long.parseLong(duration.substring(0, duration.length() - 1));
        return switch (duration.charAt(duration.length() - 1)) {
            case 'h' -> n * 3600;
            case 'd' -> n * 86400;
            default -> n * 60;
        };
    }

    @Test
    void theManifestNamesTheRepositoryTheAppAndTheItemsAndNothingElse() {
        assertEquals(TOP_LEVEL_KEYS, manifest.asMap().keySet(), "top-level keys");
        var repo = manifest.pathText("repo", "");
        assertTrue(OWNER_NAME.matcher(repo).matches(), "repo is not owner/name: '" + repo + "'");
        var login = manifest.path("app").pathText("login", "");
        assertTrue(APP_LOGIN.matcher(login).matches(), "app.login is not a GitHub App login: '" + login + "'");
    }

    /** A key the engine does not read is either a typo or an attempt at a switch; both fail. */
    @Test
    void everyItemHasOnlyTheKeysTheEngineReads() {
        items().forEach((name, item) -> {
            assertTrue(item.isObject(), name + " is not a mapping");
            for (var key : item.asMap().keySet()) {
                assertTrue(ITEM_KEYS.contains(key), name + " has an unknown key '" + key + "'");
            }
            var itemClass = item.pathText("class", "judgement");
            assertTrue(Set.of("mechanical", "review", "judgement").contains(itemClass),
                    name + ": class must be mechanical, review or judgement, not '" + itemClass + "'");
        });
    }

    /**
     * A mechanical or review item is one the watch bumps on its own, so everything the engine needs
     * to do that safely is written down: where to look, how to verify, which files to rewrite, and
     * how long to wait. A {@code source.type} without a script would fail at run time only, after
     * the watch had been green for weeks on the other items.
     */
    @Test
    void anItemTheEngineBumpsIsFullySpecified() {
        proposedItems().forEach((name, item) -> {
            var within = item.pathText("within", "");
            assertTrue(Set.of("series", "any").contains(within), name + ": within must be series or any, not '" + within + "'");
            var type = item.path("source").pathText("type", "");
            assertFalse(type.isEmpty(), name + " has no source.type");
            assertTrue(Files.isRegularFile(ENGINE.resolve("source-" + type + ".sh")),
                    name + ": no upstream/engine/source-" + type + ".sh for source.type " + type);
            assertFalse(item.path("verify").pathText("type", "").isEmpty(), name + " has no verify.type");
            var files = strings(item.path("files"));
            assertFalse(files.isEmpty(), name + " lists no files");
            for (var file : files) {
                assertTrue(Files.isRegularFile(ROOT.resolve(file)), name + ": no such file " + file);
            }
            var cooling = text(item, "cooling");
            assertTrue(DURATION.matcher(cooling).matches(), name + ": cooling is not a duration: '" + cooling + "'");
        });
    }

    /**
     * Cooling is the time between a publisher's release and the watch proposing it, so a release the
     * publisher pulls within a day (a bad tarball, a compromised account) never reaches a PR. That
     * floor is waived for a first-party source only, since there is no one else to wait for. The
     * engine decides first party at run time, from the release's owner against the manifest's own
     * ({@code src_first_party} in {@code source-github-release.sh}); the only source type with such a
     * check is {@code github-release}, so statically that is the only type a cooling under 24h may go
     * with. A kernel.org or JetBrains item at {@code 0} fails here before the engine ever runs.
     */
    @Test
    void coolingUnderADayIsOnlyForAFirstPartySource() {
        proposedItems().forEach((name, item) -> {
            var cooling = text(item, "cooling");
            if (seconds(cooling) >= 86400) return;
            assertEquals(FIRST_PARTY_SOURCE_TYPE, item.path("source").pathText("type", ""),
                    name + ": cooling " + cooling + " is under the 24h floor, which only a first-party source may be");
        });
    }

    /**
     * {@code signed-sums} trusts one committed key, whose fingerprint {@code update-kernel-pin.sh}
     * pins as well; the manifest naming another key or fingerprint would let the watch verify a bump
     * against something the pin check does not.
     */
    @Test
    void signedSumsNamesTheCommittedKeyAndThePinnedFingerprint() throws IOException {
        var pinned = FINGERPRINT_PIN.matcher(Files.readString(PIN_SCRIPT));
        assertTrue(pinned.find(), "AUTOSIGNER_FPR is not set in " + PIN_SCRIPT);
        var seen = new ArrayList<String>();
        proposedItems().forEach((name, item) -> {
            var verify = item.path("verify");
            if (!verify.pathText("type", "").equals("signed-sums")) return;
            seen.add(name);
            var key = verify.pathText("key", "");
            assertTrue(!key.isEmpty() && Files.isRegularFile(ROOT.resolve(key)),
                    name + ": verify.key is not a committed file: '" + key + "'");
            assertEquals(pinned.group(1), verify.pathText("fingerprint", ""),
                    name + ": verify.fingerprint is not AUTOSIGNER_FPR of " + PIN_SCRIPT);
        });
        assertTrue(seen.contains("kernel"), "the kernel item verifies against signed sums: " + seen);
    }

    /**
     * {@code signed-file} trusts a signer by primary fingerprint, from a committed key file: a key
     * file may hold many keys (Apache's KEYS does), so an item that listed none, or one that was
     * no fingerprint, would leave the engine nothing to hold a signature against.
     * {@code upstream.sh validate} also checks each is a primary key of the file.
     */
    @Test
    void signedFileNamesACommittedKeyAndItsSigners() {
        var seen = new ArrayList<String>();
        proposedItems().forEach((name, item) -> {
            var verify = item.path("verify");
            if (!verify.pathText("type", "").equals("signed-file")) return;
            seen.add(name);
            var key = verify.pathText("key", "");
            assertTrue(!key.isEmpty() && Files.isRegularFile(ROOT.resolve(key)),
                    name + ": verify.key is not a committed file: '" + key + "'");
            var fingerprints = strings(verify.path("fingerprints"));
            assertFalse(fingerprints.isEmpty(), name + ": verify.fingerprints names no signer");
            fingerprints.forEach(f -> assertTrue(f.matches("[0-9A-F]{40}"), name + ": '" + f + "' is not a 40 digit fingerprint"));
        });
        assertFalse(seen.isEmpty(), "no item verifies by signed-file");
    }

    @Test
    void gatesAreCheckNames() {
        proposedItems().forEach((name, item) -> {
            for (var gate : strings(item.path("gates"))) {
                assertTrue(CHECK_NAME.matcher(gate).matches(), name + ": gate '" + gate + "' is not a check name");
            }
        });
    }

    /**
     * The manifest has no option that turns a protection off: not a cooling skip, not a disabled
     * verification, not an auto-merge flag. {@code everyItemHasOnlyTheKeysTheEngineReads} catches one
     * on an item; this one catches it anywhere, under {@code source} or {@code verify} included, where
     * a source type's own keys live.
     */
    @Test
    void noKeyAnywhereTurnsAProtectionOff() {
        var offenders = new ArrayList<String>();
        collectSwitches(manifest, "", offenders);
        assertEquals(List.of(), offenders, "keys that read as switching a check off");
    }

    private static void collectSwitches(JqValue node, String at, List<String> offenders) {
        if (node.isObject()) {
            node.asMap().forEach((key, value) -> {
                var path = at.isEmpty() ? key : at + "." + key;
                if (PROTECTION_SWITCH.matcher(key).find()) offenders.add(path);
                collectSwitches(value, path, offenders);
            });
        } else if (node.isArray()) {
            for (var value : node.asList()) collectSwitches(value, at + "[]", offenders);
        }
    }

    /** The engine's own validation, as {@code upstream-watch.yml}'s first step runs it. */
    @Test
    void theEngineValidatesTheManifest() throws Exception {
        for (var tool : List.of("bash", "yq", "jq")) assumeTrue(onPath(tool), tool + " is not installed");
        // The engine needs bash 4.4 (inherit_errexit) and says so; macOS's /bin/bash is 3.2, so the
        // bash on PATH is the one run, and an old one skips rather than fails this test.
        assumeTrue(bashIsAtLeast44(), "the bash on PATH is older than 4.4");
        var log = Files.createTempFile("upstream-validate", ".log");
        try {
            var process = new ProcessBuilder("bash", ENGINE.resolve("upstream.sh").toAbsolutePath().toString(), "validate")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            var finished = process.waitFor(60, TimeUnit.SECONDS);
            process.destroyForcibly();
            var output = Files.readString(log);
            assertTrue(finished, "upstream.sh validate hung:\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.startsWith("manifest ok"), output);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        }
        return false;
    }

    private static boolean bashIsAtLeast44() {
        try {
            var process = new ProcessBuilder("bash", "-c", "(( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) ))")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
