package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.command.BuildProgress.StepProgress;
import dev.incusspawn.command.BuildProgress.StepState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.UnaryOperator;

import static dev.incusspawn.git.GitTestUtils.commitBinary;
import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs the guest's clone commands with the real git against a local fixture whose commits each
 * replace a binary file, as a release repository does (#1344).
 */
@org.junit.jupiter.api.extension.ExtendWith(IsolatedHome.class)
class RepoClonerShallowTest {

    @TempDir Path tmp;
    private Path upstream;
    private IncusClient incus;
    private Container container;
    private final List<String> stderr = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        upstream = releasesFixture(tmp);

        incus = mock(IncusClient.class);
        container = new Container(incus, "test");
        // The guest is this machine; a reference is a plain directory here, so it needs no mount wait.
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(2);
            var result = sh(rewrite.apply(cmd.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", "")));
            stderr.add(result.stderr());
            return result;
        });
    }

    /** Applied to every guest command before it runs. */
    private UnaryOperator<String> rewrite = UnaryOperator.identity();

    @Test
    void aShallowRepoClonesFromTheNetworkWithItsDepthAndStaysOnItsBranch() throws Exception {
        var guest = prepare(repo(2, null), null);

        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(guest, "rev-list", "--count", "HEAD"));
        assertEquals("+refs/heads/main:refs/remotes/origin/main", git(guest, "config", "--get-all", "remote.origin.fetch"),
                "a later fetch must not pull every other branch's history");

        commitBinary(upstream, "release 6");
        git(guest, "pull", "-q", "--ff-only");
        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("release 6", git(guest, "log", "-1", "--format=%s"));
    }

    @Test
    void aShallowRepoChecksOutItsConfiguredBranch() throws Exception {
        var guest = prepare(repo(1, "stable"), null);

        assertEquals("stable", git(guest, "branch", "--show-current"));
        assertEquals("1", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoClonedFromAFullHostCheckoutIsShallowAndLeavesTheHostAlone() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        commitBinary(upstream, "release 6"); // the host refresh has not seen it yet

        var guest = prepare(repo(2, null), host);

        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(guest, "rev-list", "--count", "HEAD"));
        assertEquals("release 6", git(guest, "log", "-1", "--format=%s"));
        assertEquals(upstream.toUri().toString(), git(guest, "remote", "get-url", "origin"));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoClonedFromAShallowHostCheckoutGetsItsConfiguredBranch() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--depth", "1", "--", upstream.toUri().toString(), host.toString());

        var guest = prepare(repo(1, "stable"), host);

        assertEquals("stable", git(guest, "branch", "--show-current"));
        assertEquals("stable release", git(guest, "log", "-1", "--format=%s"));
        assertEquals("1", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void withoutADepthEveryCloneKeepsTheFullHistory() throws Exception {
        var fromNetwork = prepare(repo(null, null), null);
        assertEquals("false", git(fromNetwork, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(fromNetwork, "rev-list", "--count", "HEAD"));

        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        var fromHost = prepare(repo(null, null), host);
        assertEquals("false", git(fromHost, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(fromHost, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoLeavesNothingOfTheHostCheckoutsOwnBranch() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "wip");
        commitBinary(host, "unpushed work");
        var unpushed = git(host, "rev-parse", "HEAD");
        git(host, "tag", "private-wip");
        git(host, "tag", "it's-wip"); // a quote, which git allows in a tag name

        var guest = prepare(repo(2, null), host);

        var p = new ProcessBuilder("git", "-C", guest.toString(), "cat-file", "-e", unpushed).start();
        assertNotEquals(0, p.waitFor(), "the host's unpushed commit is still in the guest's objects");
        assertEquals("", git(guest, "tag", "-l"));
        assertFalse(git(guest, "reflog", "--all").contains(unpushed.substring(0, 7)));

        assertEquals("refs/heads/main\nrefs/remotes/origin/HEAD refs/remotes/origin/main\nrefs/remotes/origin/main",
                git(guest, "for-each-ref", "--format=%(refname) %(symref)", "refs/heads", "refs/remotes")
                        .replaceAll(" +\n", "\n"));
        assertEquals("branch.main.remote origin\nbranch.main.merge refs/heads/main",
                git(guest, "config", "--get-regexp", "^branch\\."), "no host branch name stays in the config");
        assertEquals("release 5", git(guest, "log", "-1", "--format=%s"));
    }

    @Test
    void noObjectOfTheHostCheckoutsOwnWorkReachesTheClone() throws Exception {
        git(upstream, "tag", "v1", "HEAD~4");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        commitBinary(host, "unpushed on main"); // the clone takes the host's main as origin/main
        var aheadOfOrigin = git(host, "rev-parse", "HEAD");
        git(host, "checkout", "-q", "--detach");
        commitBinary(host, "unpushed, tagged only");
        var taggedOnly = git(host, "rev-parse", "HEAD");
        git(host, "tag", "-f", "v1"); // origin's v1 is elsewhere: the fetch's --force shadows this one
        git(host, "checkout", "-q", "main");
        git(host, "checkout", "-q", "-b", "wip");
        commitBinary(host, "unpushed work");
        var unpushed = git(host, "rev-parse", "HEAD");
        Files.writeString(host.resolve("stashed.txt"), "stashed secret\n");
        git(host, "add", "stashed.txt");
        var stashedBlob = git(host, "rev-parse", ":stashed.txt");
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "stash", "-q");
        var stash = git(host, "rev-parse", "refs/stash");
        Files.writeString(host.resolve("staged.txt"), "staged, then unstaged\n");
        git(host, "add", "staged.txt");
        var stagedBlob = git(host, "rev-parse", ":staged.txt");
        git(host, "reset", "-q");
        git(host, "commit-graph", "write", "--reachable"); // lists the unpushed commit's id
        // Stray copies of a pack holding it, as an interrupted fetch leaves: git loads neither.
        git(host, "repack", "-a", "-d", "-q");
        try (var packs = Files.list(host.resolve(".git/objects/pack"))) {
            for (var file : packs.toList()) {
                var name = file.getFileName().toString();
                if (name.endsWith(".pack") || name.endsWith(".idx")) {
                    Files.copy(file, file.resolveSibling("tmp_" + name.substring(name.lastIndexOf('.') + 1) + "_garbage"));
                }
            }
        }

        // The ref store keeps the values a fetch shadows: in packed-refs, or in a reftable's older tables.
        for (var refFormat : List.of("files", "reftable")) {
            rewrite = cmd -> "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=init.defaultRefFormat GIT_CONFIG_VALUE_0="
                    + refFormat + "; " + cmd;
            for (var depth : new Integer[] {null, 2}) {
                var guest = prepare(repo(depth, null), host);
                var what = refFormat + ", depth " + depth;
                assertEquals(refFormat, git(guest, "rev-parse", "--show-ref-format"), what);

                for (var id : List.of(aheadOfOrigin, taggedOnly, unpushed, stash, stashedBlob, stagedBlob)) {
                    var p = new ProcessBuilder("git", "-C", guest.toString(), "cat-file", "-e", id).start();
                    assertNotEquals(0, p.waitFor(), id + " is still in the guest's objects (" + what + ")");
                    assertEquals(List.of(), filesContaining(guest.resolve(".git"), id),
                            id + " is still named in the guest's .git (" + what + ")");
                }
                assertEquals("", git(guest, "fsck", "--lost-found", "--no-progress"), what);
                assertEquals(depth == null ? "5" : "2", git(guest, "rev-list", "--count", "HEAD"), what);
            }
        }
    }

    @Test
    void aStripThatFailsOrCannotBeCheckedGivesUpTheHostReference() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "wip");
        commitBinary(host, "unpushed work");

        rewrite = cmd -> cmd.replace(" pack-objects --all", " pack-objects --no-such-option --all");
        for (var depth : new Integer[] {2}) {
            assertGivesUpTheReference(run(repo(depth, null), host), "a failing pack-objects, depth " + depth);
        }

        // The check is what catches a strip that left the copied store in place.
        rewrite = cmd -> cmd.replace(" && rm -rf \"$o\" && mv \"$n\" \"$o\"", " && rm -rf \"$n\"");
        for (var depth : new Integer[] {2}) {
            assertGivesUpTheReference(run(repo(depth, null), host), "the store not swapped, depth " + depth);
        }

        // A host checkout borrowing objects through alternates: the clone takes them over.
        rewrite = UnaryOperator.identity();
        var borrowing = tmp.resolve("host-borrowing");
        git(tmp, "clone", "-q", "--reference", upstream.toString(), "--", upstream.toUri().toString(), borrowing.toString());
        assertEquals(StepState.DONE, run(repo(null, null), borrowing).state(), "full preparation can use the remote instead of an alternate-backed source");
    }

    private static void assertGivesUpTheReference(StepProgress state, String what) {
        assertEquals(StepState.DONE, state.state(), state.detail());
        assertTrue(state.note().startsWith("could not use the host reference"), what + ": " + state.note());
    }

    /** The files under {@code dir} holding {@code id}, in hex or as the raw bytes packs and indexes use. */
    private static List<Path> filesContaining(Path dir, String id) throws IOException {
        var raw = new String(HexFormat.of().parseHex(id), StandardCharsets.ISO_8859_1);
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(f -> {
                try {
                    var text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                    return text.contains(id) || text.contains(raw);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        }
    }

    @Test
    void aFullRepoKeepsNoRefOfTheHostCheckoutsOwnWork() throws Exception {
        git(upstream, "tag", "v1", "HEAD~4");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "wip");
        commitBinary(host, "unpushed work");
        var unpushed = git(host, "rev-parse", "HEAD");
        git(host, "tag", "private-wip");
        git(host, "tag", "-f", "v1"); // moved on the host, so a fetch of origin's would clobber it
        git(host, "branch", "older-wip", unpushed); // not checked out: the clone takes it as origin/older-wip

        var guest = prepare(repo(null, null), host);

        assertEquals("v1", git(guest, "tag", "-l"), "the remote's tags stay, the host's own go");
        assertEquals(git(upstream, "rev-parse", "v1"), git(guest, "rev-parse", "v1"));
        assertEquals("refs/heads/main\nrefs/remotes/origin/HEAD\nrefs/remotes/origin/main\nrefs/remotes/origin/stable",
                git(guest, "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes"));
        assertEquals("main", git(guest, "branch", "--show-current"));
        assertEquals("branch.main.remote origin\nbranch.main.merge refs/heads/main",
                git(guest, "config", "--get-regexp", "^branch\\."), "no host branch name stays in the config");
        assertEquals("5", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aRefListingThatFailsGivesUpTheHostReference() throws Exception {
        // A for-each-ref listing refs to delete fails (say a corrupt packed-refs): the deletion must
        // not go ahead with nothing, leaving the host's refs in the image.
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "wip");

        var branches = " for-each-ref --format='%(if)";
        var tags = " for-each-ref --format='delete %(refname)' refs/tags";
        var remotes = " for-each-ref --format='delete %(refname)' refs/remotes";
        for (var broken : new Object[][] {{2, branches}, {2, tags}, {2, remotes}}) {
            var listing = (String) broken[1];
            // A listing the commands lack is no rewrite, and the reference would be used.
            rewrite = cmd -> cmd.replace(listing, " for-each-ref --no-such-option" + listing.substring(" for-each-ref".length()));

            var state = run(repo((Integer) broken[0], null), host);

            assertEquals(StepState.DONE, state.state(), state.detail());
            assertTrue(state.note().startsWith("could not use the host reference"), listing + ": " + state.note());
        }
    }

    @Test
    void aRepoWithoutADepthDoesNotInheritAShallowHostCheckout() throws Exception {
        // Shallow because another template gave this URL a depth when the host cloned it.
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--depth", "1", "--", upstream.toUri().toString(), host.toString());

        var repo = repo(null, null);
        var state = run(repo, host);
        var guest = Path.of(repo.getPath());
        assertEquals(StepState.DONE, state.state(), state.detail());
        assertEquals("false", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aRepoPinnedToATagClonesFromItsHostReferenceDetachedAtTheTag() throws Exception {
        // A tag is a valid "branch:" (#1350); the host checkout has no branch of that name.
        var tagged = git(upstream, "rev-parse", "HEAD~1");
        git(upstream, "tag", "v4", "HEAD~1");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());

        var guest = prepare(repo(null, "v4"), host);

        assertEquals(tagged, git(guest, "rev-parse", "HEAD"));
        assertEquals("", git(guest, "branch", "--show-current"), "a tag checks out detached, as a network clone does");
        assertEquals("4", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoPinnedToAnAnnotatedTagClonesFromItsHostReference() throws Exception {
        var tagged = git(upstream, "rev-parse", "HEAD~1");
        git(upstream, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "-m", "v4", "v4", "HEAD~1");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());

        var guest = prepare(repo(1, "v4"), host);

        assertEquals(tagged, git(guest, "rev-parse", "HEAD"));
        assertEquals("", git(guest, "branch", "--show-current"));
        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("1", git(guest, "rev-list", "--count", "HEAD"));
        assertEquals("+refs/tags/v4:refs/tags/v4", git(guest, "config", "--get-all", "remote.origin.fetch"),
                "the refspec a shallow network clone of a tag has");
        assertEquals("", git(guest, "for-each-ref", "refs/heads", "refs/remotes"),
                "nothing of the host checkout's branches is left");
    }

    @Test
    void aTagIsNotMistakenForTheHostCheckoutsBranchOfTheSameName() throws Exception {
        // The host has a branch named like the tag, at another commit, holding unpushed work.
        var tagged = git(upstream, "rev-parse", "HEAD~1");
        git(upstream, "tag", "v4", "HEAD~1");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "v4");
        commitBinary(host, "unpushed work");
        git(host, "tag", "host-only");
        var unpushed = git(host, "rev-parse", "--short", "HEAD");

        var full = prepare(repo(null, "v4"), host);
        assertEquals(tagged, git(full, "rev-parse", "HEAD"));
        assertEquals("", git(full, "for-each-ref", "refs/heads", "refs/remotes/origin/v4"),
                "neither the host's branch nor a host-only branch as origin's");
        assertEquals("v4", git(full, "tag", "-l"), "only the remote's tag, as a network clone of it has");
        assertFalse(git(full, "reflog", "--all").contains(unpushed), "the clone's reflog entry reaches the host's work");
        assertFalse(hasObject(full, unpushed), "the host's unpushed commit is still in the guest's objects");

        var shallow = prepare(repo(1, "v4"), host);
        assertEquals(tagged, git(shallow, "rev-parse", "HEAD"));
        assertEquals("", git(shallow, "for-each-ref", "refs/heads", "refs/remotes"));
        assertEquals("v4", git(shallow, "tag", "-l"));
        assertFalse(hasObject(shallow, unpushed), "the host's unpushed commit is still in the guest's objects");
    }

    @Test
    void aNameOriginHasAsBothBranchAndTagChecksOutTheBranch() throws Exception {
        // As git clone --branch does: the branch wins over a tag of the same name.
        git(upstream, "tag", "stable", "HEAD~2");
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());

        for (var depth : new Integer[] {null, 1}) {
            var guest = prepare(repo(depth, "stable"), host);
            assertEquals("stable", git(guest, "branch", "--show-current"), "depth " + depth);
            assertEquals("stable release", git(guest, "log", "-1", "--format=%s"), "depth " + depth);
        }
    }

    @Test
    void aNameOriginHasAsNeitherBranchNorTagSaysSo() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());

        for (var depth : new Integer[] {null, 1}) {
            assertEquals(StepState.FAILED, run(repo(depth, "stabel"), host).state(), "the network clone fails too");
            assertTrue(stderr.stream().anyMatch(e -> e.contains(depth == null ? "Remote branch stabel not found" : "fatal: origin has no branch or tag 'stabel'")),
                    "depth " + depth + ": " + stderr);
            stderr.clear();
        }
    }

    @Test
    void aShallowBranchPinWhoseRemoteCannotBeListedReportsTheRemotesError() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        var repo = repo(1, "main");
        repo.setUrl(tmp.resolve("gone").toUri().toString());

        assertEquals(StepState.FAILED, run(repo, host).state(), "the network clone fails too");
        assertTrue(stderr.stream().anyMatch(e -> e.contains("does not appear to be a git repository")), stderr.toString());
        assertFalse(stderr.stream().anyMatch(e -> e.contains("no branch or tag") || e.contains("refs/tags/")), stderr.toString());
    }

    private static boolean hasObject(Path repo, String sha) throws Exception {
        return new ProcessBuilder("git", "-C", repo.toString(), "cat-file", "-e", sha).start().waitFor() == 0;
    }

    private int guests;

    private ImageDef.RepoEntry repo(Integer depth, String branch) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(upstream.toUri().toString());
        repo.setPath(tmp.resolve("guest-" + ++guests).toString());
        repo.setBranch(branch);
        repo.setDepth(depth);
        return repo;
    }

    private StepProgress run(ImageDef.RepoEntry repo, Path reference) {
        var ref = reference == null ? null
                : new RepoCloner.RepoReference("ref", reference.toString(), reference.toString(), null);
        var states = new AtomicReferenceArray<StepProgress>(1);
        if (reference != null && repo.getDepth() == null) {
            ref = new RepoCloner.RepoReference("ref", reference.toString(), reference.toString(), null,
                    new dev.incusspawn.git.GitPreparation.Request(repo.getUrl(), repo.getBranch(), reference), null);
        }
        new RepoCloner(incus) {
            @Override RepoReference prepareReference(ImageDef.RepoEntry entry, RepoReference planned,
                                                       AtomicReferenceArray<StepProgress> progress, int index) {
                var prepared = super.prepareReference(entry, planned, progress, index);
                if (!prepared.hasDevice()) return prepared;
                return new RepoReference(prepared.deviceName(), prepared.source(), prepared.source(),
                        null, null, prepared.prepared());
            }
        }.prepareOne(container, repo, ref, states, 0, new AtomicBoolean());
        return states.get(0);
    }

    private Path prepare(ImageDef.RepoEntry repo, Path reference) {
        var state = run(repo, reference);
        assertEquals(StepState.DONE, state.state(), state.detail() + "\n" + state.log());
        if (reference != null) assertTrue(state.note().startsWith("via "), state.note() + "\n" + stderr);
        return Path.of(repo.getPath());
    }


    private static IncusClient.ExecResult sh(String command) throws Exception {
        var p = new ProcessBuilder("sh", "-c", command).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new IncusClient.ExecResult(p.waitFor(), out, err);
    }
}
