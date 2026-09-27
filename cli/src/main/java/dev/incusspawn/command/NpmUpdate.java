package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.NpmGlobalInstall;
import dev.incusspawn.util.BuildOutput;

/** The {@code npm update -g} step shared by {@code isx update-all} and {@code isx project update}. */
final class NpmUpdate {

    private NpmUpdate() {
    }

    /**
     * Updates the template's global npm packages, if it has npm. Returns false when the update
     * failed or left a CLI without its platform binary: npm exits 0 when it drops an optional
     * dependency, so that is checked (and repaired once) separately (#808).
     */
    static boolean run(IncusClient incus, String name) {
        if (!incus.shellExec(name, "which", "npm").success()) return true;
        BuildOutput.stepStart("Updating npm packages...");
        var update = incus.shellExec(name, "npm", "update", "-g");
        if (!update.success()) {
            BuildOutput.stepFail("npm update -g failed (exit code " + update.exitCode() + "): "
                    + update.stderr().strip());
            return false;
        }
        var problems = NpmGlobalInstall.repairAfterUpdate(new Container(incus, name));
        if (!problems.isEmpty()) {
            BuildOutput.stepBreak();
            problems.forEach(System.err::println);
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }
}
