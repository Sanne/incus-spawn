package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Skills read at a pinned commit, each as its whole directory (#2354). */
class PinnedSkillsTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final PinnedSkills.Pack PACK = new PinnedSkills.Pack("o/r", "v1", FakeGitHub.COMMIT, "skills");

    private static FakeGitHub rgctlLike() throws IOException {
        return new FakeGitHub("o/r")
                .file("README.md", "not a skill")
                .file("skills/rgctl/SKILL.md", "---\nname: rgctl\n---\nSee references/workflows.md\n")
                .file("skills/rgctl/references/workflows.md", "workflows")
                .file("skills/rgctl/scripts/run.sh", "100755", "#!/bin/sh\necho hi\n")
                .file("skills/rgctl-review/SKILL.md", "---\nname: rgctl-review\n---\n");
    }

    @Test
    void aSkillUnderASubdirectoryInstallsWithItsSupportingFiles(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike()) {
            var dirs = github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP);

            var dir = dirs.get("rgctl");
            assertEquals(Set.of("rgctl"), dirs.keySet());
            assertTrue(Files.readString(dir.resolve("SKILL.md")).contains("references/workflows.md"));
            assertEquals("workflows", Files.readString(dir.resolve("references/workflows.md")));
            assertTrue(Files.isExecutable(dir.resolve("scripts/run.sh")), "the tree's exec bit is kept");
            assertFalse(Files.isExecutable(dir.resolve("references/workflows.md")));
        }
    }

    @Test
    void anEmptyListTakesEverySkillUnderThePathAndACachedPackMakesNoRequest(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike()) {
            var first = github.skills(cache).fetch(PACK, List.of(), HTTP);
            assertEquals(List.of("rgctl", "rgctl-review"), List.copyOf(first.keySet()));
            var requests = github.requests().size();

            var again = github.skills(cache).fetch(PACK, List.of(), HTTP);
            assertEquals(first, again);
            github.skills(cache).fetch(PACK, List.of("rgctl-review"), HTTP);
            assertEquals(requests, github.requests().size(), "a commit never changes, so its cache is never asked about");
        }
    }

    @Test
    void filesAreReadAtTheCommitNeverAtTheTag(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike()) {
            github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP);
            var raw = github.requests().stream().filter(r -> r.startsWith("/raw/")).toList();
            assertFalse(raw.isEmpty());
            assertTrue(raw.stream().allMatch(r -> r.startsWith("/raw/o/r/" + FakeGitHub.COMMIT + "/skills/rgctl/")), raw.toString());
        }
    }

    @Test
    void aTagThatMovedToAnotherCommitFails(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().tagNames("d".repeat(40))) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("not the pinned " + FakeGitHub.COMMIT), e.getMessage());
            assertTrue(e.getMessage().contains("git ls-remote https://github.com/o/r 'refs/tags/v1^{}'"), e.getMessage());
            assertFalse(Files.exists(cache.resolve("o/r").resolve(FakeGitHub.COMMIT).resolve("skills/rgctl")));
        }
    }

    @Test
    void aFileThatDoesNotMatchTheCommitsTreeFails(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().tamper("skills/rgctl/references/workflows.md", "altered")) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("does not match the commit's tree"), e.getMessage());
            assertFalse(Files.exists(cache.resolve("o/r").resolve(FakeGitHub.COMMIT).resolve("skills/rgctl")),
                    "nothing half-fetched is published to the cache");
        }
    }

    @Test
    void aFileServedLargerThanTheTreeSaysIsNotReadOn(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().tamper("skills/rgctl/references/workflows.md", "workflows" + "x".repeat(100_000))) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("larger than the commit's tree says"), e.getMessage());
        }
    }

    @Test
    void aSkillNamedLikeTheCachesMarkerIsRefused(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().file("skills/.all/SKILL.md", "x")) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of(), HTTP));
            assertTrue(e.getMessage().contains("unsafe name"), e.getMessage());
        }
    }

    @Test
    void aSymlinkInASkillIsRefused(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().file("skills/rgctl/link", "120000", "/home/agentuser/.ssh/id_ed25519")) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("a symlink"), e.getMessage());
        }
    }

    @Test
    void aSubmoduleInASkillIsRefused(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().entry("skills/rgctl/vendor", "160000", "commit")) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("a commit"), e.getMessage());
        }
    }

    @Test
    void aPathLeavingTheSkillIsRefused(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike().file("skills/rgctl/../../escape", "x")) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("rgctl"), HTTP));
            assertTrue(e.getMessage().contains("unsafe path"), e.getMessage());
        }
    }

    @Test
    void aSkillTheReleaseDoesNotHoldIsNamed(@TempDir Path cache) throws Exception {
        try (var github = rgctlLike()) {
            var e = assertThrows(IOException.class, () -> github.skills(cache).fetch(PACK, List.of("nope"), HTTP));
            assertTrue(e.getMessage().contains("no skills/nope/SKILL.md"), e.getMessage());
        }
    }

    @Test
    void theBlobIdIsGitsOwn() {
        // printf 'hello\n' | git hash-object --stdin
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                PinnedSkills.gitBlobId("hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
