package dev.incusspawn.command;

import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.UfwCheck;
import dev.incusspawn.proxy.ProxyConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The root lines of {@code isx init --no-sudo}'s script for a host whose firewall is UFW (#2074).
 * <p>
 * {@code /etc/ufw/before.rules} is root's to read, so isx cannot compute its new text ahead, as it
 * does for the other files the script writes. The script edits it as it runs instead, by the same
 * rules as {@link UfwCheck#insertNatBlock} and {@link UfwCheck#insertFilterRules}: isx's marked
 * blocks are replaced where they stand, or put before the first {@code *filter} line and before
 * the {@code COMMIT} of the {@code *filter} section. {@code UfwScriptTest} runs these lines over
 * sample files and compares them with the Java's output, so the two cannot drift. A copy of the
 * file is kept, and put back if {@code ufw reload} refuses the new one.
 */
final class UfwScript {

    private UfwScript() {}

    /** The file the lines edit; tests point them at a copy. */
    static final String RULES = UfwCheck.BEFORE_RULES.toString();

    /**
     * Sets {@code gw} and {@code subnet} from the bridge, as the redirect's firewalld lines do: the
     * bridge {@code incus admin init} made, or one made as {@code createBridgeIfMissing} makes it.
     * The subnet is the gateway's /24, as {@code CidrUtils.deriveSubnet} takes it.
     */
    static List<String> gatewayLines() {
        var lines = new ArrayList<>(HostSetup.bridgeGatewayLines());
        lines.add("subnet=${gw%.*}.0/24");
        return lines;
    }

    /**
     * {@code lines} under a comment naming what the plan read, such as the gateway: the script's
     * record keys a change by its lines, so lines written for another reading are another change.
     */
    static List<String> forReading(String reading, List<String> lines) {
        var out = new ArrayList<String>();
        out.add("# " + reading.replace('\n', ' '));
        out.addAll(lines);
        return out;
    }

    /**
     * The firewall step's part: traffic in on the bridge allowed, then isx's NAT block (masquerading,
     * no redirect yet) and its FORWARD rules in {@code before.rules}, and UFW reloaded. A NAT block
     * that already masquerades the bridge's subnet is left as it stands, its redirect with it, as
     * {@code configureUfw} leaves it.
     */
    static List<String> firewallLines() {
        var lines = new ArrayList<>(gatewayLines());
        lines.add("ufw allow in on " + BridgeAddress.BRIDGE);
        lines.addAll(edit(UfwCheck.generateNatBlockWithoutRedirect("${subnet}"), true, UfwCheck.generateFilterInsert()));
        return lines;
    }

    /** The MITM step's part: isx's NAT block with the redirect to the proxy on the bridge's gateway, and UFW reloaded. */
    static List<String> redirectLines() {
        var lines = new ArrayList<>(gatewayLines());
        lines.addAll(edit(UfwCheck.generateNatBlock("${gw}", "${subnet}",
                ProxyConfig.CONTAINER_FACING_PORT, ProxyConfig.DEFAULT_MITM_PORT), false, null));
        return lines;
    }

    /**
     * Replaces or inserts {@code natBlock} and, when not null, {@code forwardBlock} in
     * {@link #RULES}, keeps a copy, and reloads UFW, putting the copy back if the reload fails.
     * With {@code keepMasquerading}, an existing NAT block that masquerades {@code $subnet} stays,
     * as {@link UfwCheck#hasMasquerade} reads it.
     * The blocks are here-documents the shell expands ({@code ${gw}}, {@code ${subnet}}), and
     * hold nothing else it would.
     */
    static List<String> edit(String natBlock, boolean keepMasquerading, String forwardBlock) {
        var lines = new ArrayList<String>();
        lines.add("rules=" + RULES);
        // A forward block that an older isx wrote into a line of its own (#1932) is the run's to move
        lines.add("if grep -qF '" + UfwCheck.MARKER_FWD_BEGIN + "' \"$rules\" && ! grep -q '^" + UfwCheck.MARKER_FWD_BEGIN + "' \"$rules\"; then"
                + " echo \"isx's FORWARD rules sit inside another line of $rules: run 'sudo isx init' once to move them.\" >&2; exit 1; fi");
        lines.add("nat=$(mktemp); fwd=$(mktemp); edited=$(mktemp)");
        lines.addAll(hereDocument("$nat", natBlock));
        if (forwardBlock != null) lines.addAll(hereDocument("$fwd", forwardBlock));
        else lines.add(": > \"$fwd\"");
        // A block counts only with its END after its BEGIN; anything else is not isx's to edit
        lines.add("hasnat=$(awk -v b='" + UfwCheck.MARKER_NAT_BEGIN + "' -v e='" + UfwCheck.MARKER_NAT_END + "' '" + PAIR + "' \"$rules\")");
        lines.add("hasfwd=$(awk -v b='" + UfwCheck.MARKER_FWD_BEGIN + "' -v e='" + UfwCheck.MARKER_FWD_END + "' '" + PAIR + "' \"$rules\")");
        // Only the blocks this edit replaces: the redirect's leaves the FORWARD block alone
        lines.add("if [ \"$hasnat\" = bad ]" + (forwardBlock != null ? " || [ \"$hasfwd\" = bad ]" : "")
                + "; then rm -f \"$nat\" \"$fwd\" \"$edited\";"
                + " echo \"isx's marked blocks in $rules are not whole (an END without its BEGIN, or the reverse): fix them by hand, then run the script again.\" >&2; exit 1; fi");
        // An empty nat file leaves isx's NAT block as it stands
        if (keepMasquerading) lines.add("if [ \"$hasnat\" = 1 ] && awk -v subnet=\"$subnet\" '" + MASQUERADING + "' \"$rules\"; then : > \"$nat\"; fi");
        lines.add("awk -v nat=\"$nat\" -v fwd=\"$fwd\" -v hasnat=\"$hasnat\" -v hasfwd=\"$hasfwd\" '" + AWK + "' \"$rules\" > \"$edited\"");
        lines.add("cp -p \"$rules\" \"$rules.isx-bak\"");
        // Written in place: the file keeps its owner and mode
        lines.add("cat \"$edited\" > \"$rules\"");
        lines.add("rm -f \"$nat\" \"$fwd\" \"$edited\"");
        // The old file back; and if UFW refuses that too, its rules may be out of the kernel: say so
        lines.add("if ! ufw reload; then cp -p \"$rules.isx-bak\" \"$rules\";"
                + " if ufw reload; then echo \"UFW refused the new $rules; the old one is back.\" >&2;"
                + " else echo \"UFW refused the new $rules, and reloading the old one failed too: UFW's rules may not be loaded. Check: sudo ufw status verbose\" >&2; fi;"
                + " exit 1; fi");
        return lines;
    }

    private static List<String> hereDocument(String target, String content) {
        var delimiter = InitPlan.Change.delimiter(content);
        var lines = new ArrayList<String>();
        lines.add("cat > \"" + target + "\" <<" + delimiter);
        lines.addAll(content.lines().toList());
        lines.add(delimiter);
        return lines;
    }

    /**
     * Whether isx's NAT block, from its first begin marker to the end marker after it, names both
     * {@code MASQUERADE} and {@code subnet}: {@link UfwCheck#hasMasquerade}, as an exit status.
     */
    static final String MASQUERADING = String.join("\n",
            "!seen && index($0, \"" + UfwCheck.MARKER_NAT_BEGIN + "\") { seen = 1; inblock = 1 }",
            "inblock { block = block $0 \"\\n\"; if (index($0, \"" + UfwCheck.MARKER_NAT_END + "\")) inblock = 0 }",
            "END { exit !(index(block, \"MASQUERADE\") && index(block, subnet)) }");

    /**
     * Whether {@code b} starts a line of the file with {@code e} on a later line: 1, 0 when there
     * is no {@code b} nor {@code e}, and {@code bad} for an {@code e} first or a {@code b} never
     * closed, as an edit by hand can leave them.
     */
    static final String PAIR = "index($0, b) == 1 && !begin { begin = NR; next } "
            + "index($0, e) { if (!begin) bad = 1; else if (!end) end = NR } "
            + "END { if (bad || (begin && !end)) print \"bad\"; else print (begin ? 1 : 0) }";

    /**
     * The edit, line by line, as {@link UfwCheck#insertNatBlock} then {@link UfwCheck#insertFilterRules}
     * do it on the whole text: only the first of each block is replaced, as their {@code indexOf}
     * finds it. An empty {@code nat} file leaves isx's NAT block as it stands, and an empty
     * {@code fwd} file means no FORWARD block to place. Written without single quotes, since the
     * shell line quotes it in them.
     */
    static final String AWK = String.join("\n",
            "function emit(f,  l) { while ((getline l < f) > 0) print l; close(f) }",
            "function size(f,  l, n) { n = 0; while ((getline l < f) > 0) n++; close(f); return n }",
            "BEGIN { donat = size(nat) > 0; dofwd = size(fwd) > 0; skipnat = 0; skipfwd = 0; natdone = 0; fwddone = 0; infilter = 0 }",
            "donat && hasnat && !natdone && index($0, \"" + UfwCheck.MARKER_NAT_BEGIN + "\") == 1 { emit(nat); natdone = 1; skipnat = 1 }",
            "skipnat { if (index($0, \"" + UfwCheck.MARKER_NAT_END + "\")) skipnat = 0; next }",
            "dofwd && hasfwd && !fwddone && index($0, \"" + UfwCheck.MARKER_FWD_BEGIN + "\") == 1 { emit(fwd); fwddone = 1; skipfwd = 1 }",
            "skipfwd { if (index($0, \"" + UfwCheck.MARKER_FWD_END + "\")) skipfwd = 0; next }",
            "/^\\*filter[ \\t\\r]*$/ { if (donat && !hasnat && !natdone) { emit(nat); print \"\"; natdone = 1 } infilter = 1 }",
            "/^COMMIT[ \\t\\r]*$/ { if (infilter && dofwd && !hasfwd && !fwddone) { emit(fwd); fwddone = 1 } }",
            "{ print }",
            "END { if (donat && !natdone) { print \"\"; emit(nat) } if (dofwd && !fwddone) { print \"\"; emit(fwd) } }");
}
