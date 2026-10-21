package io.assay.workspace;

import static org.junit.jupiter.api.Assertions.*;

import io.assay.model.TextCandidate;
import io.assay.support.AtomicFiles;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRecoveryTest {

    private static TextCandidate candidate(Map<String, String> files) {
        return new TextCandidate("proposal", "2", "", "improve", "skill", Map.of(), files, List.of());
    }

    @Test
    void rebuildsUncommittedLegacyDirectoryAndStaleStaging(@TempDir Path root) {
        Path current = root.resolve("baseline");
        AtomicFiles.writeText(current.resolve("a.txt"), "baseline");
        Path workspaces = root.resolve("workspaces");
        Path round = workspaces.resolve("loop/candidates/round-1");
        AtomicFiles.writeText(round.resolve("proposal/artifact/partial.txt"), "unfinished");
        AtomicFiles.writeText(round.resolve(".assay-staging/proposal/artifact/stale.txt"), "unfinished");

        Path staged = new TextArtifactWorkspace(workspaces).stage("loop", 1,
                candidate(Map.of("a.txt", "improved", "nested/b.txt", "new")), "artifact", current);

        assertEquals("improved", AtomicFiles.readText(staged.resolve("a.txt")));
        assertEquals("new", AtomicFiles.readText(staged.resolve("nested/b.txt")));
        assertEquals("baseline", AtomicFiles.readText(current.resolve("a.txt")));
        assertFalse(Files.exists(staged.resolve("partial.txt")));
        assertFalse(Files.exists(staged.resolve("stale.txt")));
        assertFalse(Files.exists(round.resolve(".assay-staging/proposal")));
        assertTrue(Files.isRegularFile(staged.getParent().resolve("artifact-manifest.json")));
    }

    @Test
    void reusesACommittedCandidateAndRejectsTampering(@TempDir Path root) {
        Path current = root.resolve("baseline");
        AtomicFiles.writeText(current.resolve("a.txt"), "baseline");
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root.resolve("workspaces"));
        TextCandidate proposal = candidate(Map.of("a.txt", "improved"));
        Path staged = workspace.stage("loop", 1, proposal, "artifact", current);
        assertEquals(staged, workspace.stage("loop", 1, proposal, "artifact", current));

        AtomicFiles.writeText(staged.resolve("a.txt"), "tampered");
        assertThrows(IllegalArgumentException.class, () -> workspace.stage("loop", 1, proposal, "artifact", current));
        assertEquals("tampered", AtomicFiles.readText(staged.resolve("a.txt")));
    }

    @Test
    void failedFileWriteLeavesNoCommittedDirectoryAndCanBeRetried(@TempDir Path root) throws Exception {
        Path current = Files.createDirectories(root.resolve("baseline"));
        Path workspaces = root.resolve("workspaces");
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(workspaces);

        assertThrows(UncheckedIOException.class,
                () -> workspace.stage("loop", 1, candidate(Map.of("x".repeat(300), "exceeds filesystem name limit")), "artifact", current));
        assertFalse(Files.exists(workspaces.resolve("loop/candidates/round-1/proposal")));

        Path staged = workspace.stage("loop", 1, candidate(Map.of("a.txt", "retry")), "artifact", current);
        assertEquals("retry", AtomicFiles.readText(staged.resolve("a.txt")));
        assertFalse(Files.exists(workspaces.resolve("loop/candidates/round-1/.assay-staging/proposal")));
    }

    @Test
    void stagingCannotDeleteAnotherCommittedCandidateWithATemporaryLookingId(@TempDir Path root) {
        Path current = root.resolve("baseline");
        AtomicFiles.writeText(current.resolve("a.txt"), "baseline");
        TextArtifactWorkspace workspace = new TextArtifactWorkspace(root.resolve("workspaces"));
        TextCandidate other = new TextCandidate(".proposal.staging", "2", "", "other", "skill",
                Map.of(), Map.of("a.txt", "other candidate"), List.of());
        Path otherPath = workspace.stage("loop", 1, other, "artifact", current);

        workspace.stage("loop", 1, candidate(Map.of("a.txt", "improved")), "artifact", current);
        assertEquals("other candidate", AtomicFiles.readText(otherPath.resolve("a.txt")));
        assertTrue(Files.isRegularFile(otherPath.getParent().resolve("artifact-manifest.json")));

        TextCandidate reserved = new TextCandidate(".ASSAY-STAGING", "2", "", "reserved", "skill",
                Map.of(), Map.of("a.txt", "invalid"), List.of());
        assertThrows(IllegalArgumentException.class, () -> workspace.stage("loop", 1, reserved, "artifact", current));
    }
}
