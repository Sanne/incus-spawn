package dev.incusspawn;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The homepage cast kit in {@code site/demo/} (#2054): {@code record-in-vm.sh} records in a clean VM,
 * {@code record-all.sh} finds tpl-demo on the TUI board and lets the pool settle before each take,
 * and {@code record.py} repeats a key press as often as the board needs. Each script runs against
 * stand-ins for isx, incus and the rest that log what they are asked to do, so no Incus is needed.
 * Tagged {@code docs}: a change to {@code site/} alone runs only these gates.
 */
@Tag("docs")
class DemoRecordingScriptsTest {

    private static final Path DEMO = Path.of("..", "site", "demo");

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err, List<String> log) {}

    @BeforeEach
    void needsTheHostTools() {
        for (var tool : List.of("bash", "python3", "tar")) assumeTrue(onPath(tool), tool + " is not installed");
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        }
        return false;
    }

    /** An executable stand-in on the test's PATH, whose body may log to {@code $STATE/log}. */
    private void stub(String name, String body) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var file = bin.resolve(name);
        Files.writeString(file, "#!/usr/bin/env bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(Map<String, String> env, Path script, String... args) throws Exception {
        return run(env, List.of("bash"), script, args);
    }

    private Run run(Map<String, String> env, List<String> interpreter, Path script, String... args) throws Exception {
        var cmd = new ArrayList<>(interpreter);
        cmd.add(script.toString());
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .redirectOutput(dir.resolve("out").toFile()).redirectError(dir.resolve("err").toFile());
        pb.environment().keySet().removeIf(key -> key.startsWith("ISX_") || key.startsWith("TPL_"));
        pb.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("STATE", dir.toString());
        pb.environment().put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        pb.environment().putAll(env);
        var p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the script did not finish");
        var log = dir.resolve("log");
        return new Run(p.exitValue(), Files.readString(dir.resolve("out")), Files.readString(dir.resolve("err")),
                Files.exists(log) ? Files.readAllLines(log) : List.of());
    }

    /** A copy of the kit's files, so the stand-ins beside a script are the ones it runs. */
    private Path kit(String... files) throws Exception {
        var demo = Files.createDirectories(dir.resolve("kit/site/demo"));
        Files.createDirectories(dir.resolve("kit/scripts"));
        for (var f : files) Files.copy(DEMO.resolve(f), demo.resolve(f));
        Files.writeString(dir.resolve("kit/scripts/local-incus.sh"), "");
        return demo;
    }

    // ---- record.py ----

    @Test
    void recordPyRepeatsASendAsOftenAsTheVariableItNamesSays() throws Exception {
        var scenario = Files.writeString(dir.resolve("scenario.json"),
                "[{\"send\": \"x\\n\", \"repeat\": \"PRESSES\", \"gap\": 0.05}, {\"sleep\": 0.5}]");
        var typed = dir.resolve("typed");
        var run = run(Map.of("PRESSES", "3"), List.of("python3", "-I"), DEMO.resolve("record.py").toAbsolutePath(),
                scenario.toString(), dir.resolve("out.cast").toString(), "--cmd", "cat > '" + typed + "'");

        assertEquals(0, run.exit(), run.err());
        assertEquals(List.of("x", "x", "x"), Files.readAllLines(typed), "one press per count, each sent once");
    }

    @Test
    void recordPyRefusesAnUnsetCountBeforeTheTakeStarts() throws Exception {
        var scenario = Files.writeString(dir.resolve("scenario.json"),
                "[{\"send\": \"x\", \"repeat\": \"PRESSES\"}]");
        var cast = dir.resolve("out.cast");
        var run = run(Map.of(), List.of("python3", "-I"), DEMO.resolve("record.py").toAbsolutePath(),
                scenario.toString(), cast.toString(), "--cmd", "true");

        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("PRESSES"), run.err());
        assertFalse(Files.exists(cast), "no take is written for a scenario that cannot run");
    }

    @Test
    void scriptMdShowsTheTplDemoTheVmBuilds() throws Exception {
        var script = Files.readString(DEMO.resolve("SCRIPT.md"));
        var start = script.indexOf("# ~/.config/incus-spawn/images/tpl-demo.yaml\n");
        assertTrue(start >= 0, "SCRIPT.md, appendix A, shows tpl-demo.yaml");
        var shown = script.substring(script.indexOf('\n', start) + 1, script.indexOf("```", start));
        var file = Files.readString(DEMO.resolve("tpl-demo.yaml"));
        assertEquals(file.substring(file.indexOf("name: tpl-demo")), shown, "appendix A is tpl-demo.yaml, less its comment");
    }

    // ---- record-all.sh ----

    private static final String TEMPLATES = "[{\"name\":\"tpl-minimal\",\"built\":true},"
            + "{\"name\":\"tpl-firefox\",\"built\":false},{\"name\":\"tpl-demo\",\"built\":%s}]";

    /** Stand-ins for a take: record.py logs the board row it was given, compress.py copies. */
    private Path recordAllKit(String tplDemoBuilt) throws Exception {
        var demo = kit("record-all.sh");
        stub("isx", """
                echo "isx $*" >> "$STATE/log"
                case "$1" in
                  templates) echo '%s' ;;
                esac
                """.formatted(TEMPLATES.formatted(tplDemoBuilt)));
        Files.writeString(demo.resolve("record.py"), """
                import os, sys
                with open(os.environ["STATE"] + "/log", "a") as f:
                    f.write("record " + os.path.basename(sys.argv[1]) + " row=" + os.environ.get("TPL_DEMO_ROW", "unset") + "\\n")
                open(sys.argv[2], "w").write("{}\\n")
                """);
        Files.writeString(demo.resolve("compress.py"), """
                import shutil, sys
                shutil.copy(sys.argv[1], sys.argv[2])
                """);
        return demo;
    }

    @Test
    void recordAllFindsTplDemoOnTheBoardAndSettlesThePoolFirst() throws Exception {
        var demo = recordAllKit("true");
        var run = run(Map.of("ISX_DEMO_SETTLE", "echo settle >> \"$STATE/log\""), demo.resolve("record-all.sh"), "tui");

        assertEquals(0, run.exit(), run.err());
        var takes = run.log().stream().filter(l -> !l.startsWith("isx ")).toList();
        assertEquals(List.of("settle", "record scenario-tui.json row=2"), takes,
                "the pool settles before the take, and tpl-demo is the board's third row, built or not counted alike");
        assertTrue(Files.exists(demo.resolve("tui-web.cast")));
    }

    @Test
    void recordAllRefusesAnUnbuiltTplDemo() throws Exception {
        var demo = recordAllKit("false");
        var run = run(Map.of(), demo.resolve("record-all.sh"), "tui");

        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("tpl-demo is not built"), run.err());
        assertTrue(run.log().stream().noneMatch(l -> l.startsWith("record ")), "nothing is recorded");
    }

    @Test
    void theTuiScenarioTakesItsDownPressesFromTheBoard() throws Exception {
        var steps = JqValues.parseStrict(Files.readString(DEMO.resolve("scenario-tui.json"))).asList();
        var downs = steps.stream().filter(s -> "\u001b[B".equals(s.pathText("send", ""))).toList();
        assertEquals(1, downs.size(), "one Down step, repeated, never a count fixed for one board");
        assertEquals("TPL_DEMO_ROW", downs.getFirst().pathText("repeat", ""));
    }

    // ---- record-in-vm.sh, on the host ----

    /**
     * Stand-ins for the host: isx lists no VM and reports the recorder template's state, incus runs
     * {@code su -c} commands by logging them, answers the inside run with {@code insideExit} and the
     * cast pull with a tarball holding {@code hero.cast}.
     */
    private Path hostKit(String recorderState, int insideExit) throws Exception {
        var demo = kit("record-in-vm.sh");
        stub("uname", "echo Linux\n");
        stub("isx", """
                echo "isx $*" >> "$STATE/log"
                case "$1" in
                  list) [ -f "$STATE/branched" ] && echo demo-recorder; true ;;
                  templates) echo '[{"name":"tpl-demo-recorder",%s}]' ;;
                  branch) touch "$STATE/branched" ;;
                esac
                """.formatted(recorderState));
        stub("incus", """
                # The last argument, whole: macOS's bash 3.2 trims trailing blanks from cmd=${@: -1}
                for cmd; do :; done
                case "$cmd" in
                  *--inside*) echo "incus inside: $cmd" >> "$STATE/log"; exit %d ;;
                  *"tar -xf -"*) cat > "$STATE/pushed.tar"; echo "incus push" >> "$STATE/log" ;;
                  *"tar -cf -"*) echo "incus pull" >> "$STATE/log"
                                 [ -z "${PULL_FAILS:-}" ] || exit 1
                                 mkdir -p "$STATE/vm" && echo '{}' > "$STATE/vm/hero.cast" && tar -C "$STATE/vm" -cf - hero.cast ;;
                esac
                """.formatted(insideExit));
        return demo;
    }

    private static final String BUILT = "\"built\":true,\"version_outdated\":false,\"definition_changed\":false,\"parent_rebuilt\":false";

    @Test
    void recordInVmRecordsInAFreshVmAndBringsTheCastsBack() throws Exception {
        var demo = hostKit(BUILT, 0);
        var run = run(Map.of("ISX_RECORD_VERSION", "9.8.7"), demo.resolve("record-in-vm.sh"), "hero", "tui");

        assertEquals(0, run.exit(), run.err());
        var isx = run.log().stream().filter(l -> l.startsWith("isx ") && !l.startsWith("isx list")
                && !l.startsWith("isx templates")).toList();
        assertEquals(List.of("isx branch --from tpl-demo-recorder demo-recorder --memory=8GB --format=plain",
                "isx destroy demo-recorder --skip-confirmation"), isx, "an up-to-date recorder is not rebuilt");
        var vm = run.log().stream().filter(l -> l.startsWith("incus")).toList();
        assertEquals(3, vm.size(), vm.toString());
        assertEquals("incus push", vm.get(0));
        assertTrue(vm.get(1).contains("ISX_RECORD_VERSION=9.8.7") && vm.get(1).endsWith("--inside hero tui "), vm.get(1));
        assertEquals("incus pull", vm.get(2));
        assertTrue(Files.exists(demo.resolve("hero.cast")), "the recorded cast lands in site/demo");
        var pushed = new ProcessBuilder("tar", "-tf", dir.resolve("pushed.tar").toString()).start();
        var names = new String(pushed.getInputStream().readAllBytes());
        assertTrue(names.contains("site/demo/record-in-vm.sh"), names);
    }

    @Test
    void recordInVmRebuildsAStaleRecorderAndCleansUpAFailedRecording() throws Exception {
        var demo = hostKit(BUILT.replace("\"definition_changed\":false", "\"definition_changed\":true"), 1);
        var run = run(Map.of(), demo.resolve("record-in-vm.sh"));

        assertNotEquals(0, run.exit());
        var isx = run.log().stream().filter(l -> l.startsWith("isx build") || l.startsWith("isx destroy")).toList();
        assertEquals(List.of("isx build tpl-demo-recorder --yes", "isx destroy demo-recorder --skip-confirmation"), isx);
        assertTrue(run.log().contains("incus pull"), "what a failed run did record is still brought back");
        assertTrue(run.log().stream().anyMatch(l -> l.contains("ISX_RECORD_VERSION=0.5.0")), "0.5.0 by default");
    }

    @Test
    void recordInVmKeepsTheVmWhenTheCastsCannotBeCopiedBack() throws Exception {
        var demo = hostKit(BUILT, 0);
        var run = run(Map.of("PULL_FAILS", "1"), demo.resolve("record-in-vm.sh"), "hero");

        assertNotEquals(0, run.exit(), "a recording that never came back is not done");
        assertTrue(run.err().contains("could not copy the casts back"), run.err());
        assertTrue(run.log().stream().noneMatch(l -> l.startsWith("isx destroy")), "what the VM recorded is not destroyed");
    }

    @Test
    void recordInVmRefusesALeftoverVm() throws Exception {
        var demo = hostKit(BUILT, 0);
        Files.writeString(dir.resolve("branched"), "");
        var run = run(Map.of(), demo.resolve("record-in-vm.sh"));

        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("'demo-recorder' already exists"), run.err());
        assertTrue(run.log().stream().noneMatch(l -> l.startsWith("isx destroy") || l.startsWith("isx branch")),
                "an earlier run's VM is never destroyed or replaced");
    }

    // ---- record-in-vm.sh, inside the VM ----

    /** Stand-ins for the VM: sudo logs, isx reports {@code version}, local-incus.sh snapshots the config, sg logs. */
    private Path insideKit(String version) throws Exception {
        var demo = kit("record-in-vm.sh");
        stub("sudo", "echo \"sudo $*\" >> \"$STATE/log\"\n");
        stub("isx", "echo \"isx " + version + " (0123abc)\"\n");
        stub("sg", "echo \"sg $*\" >> \"$STATE/log\"\n");
        Files.writeString(dir.resolve("kit/scripts/local-incus.sh"), """
                echo local-incus >> "$STATE/log"
                cp "$HOME/.config/incus-spawn/config.yaml" "$STATE/config-at-init"
                """);
        return demo;
    }

    @Test
    void insideTheVmItInstallsThePinnedReleaseAndBorrowsTheAccountsBeforeInit() throws Exception {
        var demo = insideKit("9.8.7");
        var run = run(Map.of("ISX_RECORD_VERSION", "9.8.7"), demo.resolve("record-in-vm.sh"), "--inside", "creds");

        assertEquals(0, run.exit(), run.err());
        assertEquals("sudo dnf install -y -q incus-spawn-9.8.7", run.log().get(0));
        assertEquals("local-incus", run.log().get(1));
        // Read as isx reads it, so the file says what isx will do, not merely what it contains
        var config = Jjq.spawnConfig(dir.resolve("config-at-init"));
        assertEquals(AccountResolver.INHERIT, AccountResolver.effectiveAccount(config, "claude", null));
        assertEquals(AccountResolver.INHERIT, AccountResolver.effectiveAccount(config, "github", null));
        assertEquals(List.of("~/sources"), config.getHostPaths());
        assertEquals(List.of("shell-status-bar"), config.getFeatures());
        assertTrue(run.log().get(2).startsWith("sg incus-admin -c ") && run.log().get(2).endsWith("--inside-ready creds "),
                run.log().get(2));
    }

    @Test
    void insideTheVmAnotherReleaseThanThePinnedOneStopsTheRecording() throws Exception {
        var demo = insideKit("9.8.6");
        var run = run(Map.of("ISX_RECORD_VERSION", "9.8.7"), demo.resolve("record-in-vm.sh"), "--inside");

        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("asked for isx 9.8.7"), run.err());
        assertFalse(run.log().contains("local-incus"), "nothing is set up with the wrong isx");
    }

}
