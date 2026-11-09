package io.assay.workspace;

import static org.junit.jupiter.api.Assertions.*;

import io.assay.model.SafePath;
import io.assay.model.TextFileOperation;
import io.assay.support.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {"D:/outside.txt", "D:outside.txt", "./D:/outside.txt", "file.txt:stream",
            "/outside.txt", "../outside.txt", "nested/../../outside.txt", "nested/file.", "nested/file "})
    void rejectsUnsafePathsOnEveryPlatform(String path) {
        assertThrows(IllegalArgumentException.class, () -> new TextFileOperation("write", path, "changed", null));
        assertThrows(IllegalArgumentException.class, () -> new TextFileOperation("move", "inside.txt", null, path));
    }

    @Test
    void preservesSafePathNormalization() {
        SafePath.require("path", "nested/./folder//file.txt");
        assertEquals("nested/folder/file.txt", SafePath.normalize("nested/./folder//file.txt"));
    }

    @Test
    void revalidatesAllOperationsBeforeChangingAnyFile(@TempDir Path root) {
        AtomicFiles.writeText(root.resolve("inside.txt"), "original");
        TextFileOperation operation = new TextFileOperation("write", "inside.txt", "changed", null);
        List<TextFileOperations.Validated> forged = List.of(
                new TextFileOperations.Validated(operation, "inside.txt", null),
                new TextFileOperations.Validated(operation, "../outside.txt", null));

        assertThrows(IllegalArgumentException.class, () -> TextFileOperations.apply(root, forged));
        assertEquals("original", AtomicFiles.readText(root.resolve("inside.txt")));
        assertFalse(Files.exists(root.getParent().resolve("outside.txt")));
    }

    @Test
    void refusesAnExistingSymbolicLinkParent(@TempDir Path root) throws IOException {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        try {
            Files.createSymbolicLink(candidate.resolve("linked"), outside);
        } catch (IOException | UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }
        List<TextFileOperations.Validated> operations = TextFileOperations.validate(Map.of(),
                List.of(new TextFileOperation("write", "linked/file.txt", "changed", null)));

        assertThrows(IllegalArgumentException.class, () -> TextFileOperations.apply(candidate, operations));
        assertFalse(Files.exists(outside.resolve("file.txt")));
    }
}
