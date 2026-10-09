package io.assay.workspace;

import io.assay.json.Json;
import io.assay.model.TextCandidate;
import io.assay.model.TextFileOperation;
import io.assay.support.AtomicFiles;
import io.assay.support.ExclusiveFileLock;
import io.assay.support.Names;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The sandbox that holds a baseline artifact and each staged candidate.
 *
 * <p>The domain project's own files are never touched: the workspace copies the baseline on the first
 * attempt and stages every candidate into its own directory. An accepted candidate stays inside the
 * workspace — promoting it to production is outside this loop.
 */
public final class TextArtifactWorkspace {

    private final Path root;

    public TextArtifactWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public ExclusiveFileLock lockLoop(String loopId) {
        Path lockPath = root.resolve(".locks").resolve(Names.safeName(loopId, "loop_id") + ".lock");
        return ExclusiveFileLock.acquire(lockPath, "auto evolution loop " + loopId);
    }

    /**
     * Reads a text artifact: a single UTF-8 string for a file, or a path-to-content map for a directory.
     * Symbolic links are rejected rather than followed, because the artifact is untrusted input.
     */
    public static Object readArtifact(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("symbolic links are not supported: " + path);
        }
        if (Files.isRegularFile(path)) {
            return AtomicFiles.readText(path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("artifact does not exist: " + path);
        }
        Map<String, String> files = new LinkedHashMap<>();
        List<Path> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(item -> !item.equals(path)).forEach(items::add);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        items.sort(Comparator.comparing(item -> item.toString()));
        for (Path item : items) {
            if (Files.isSymbolicLink(item)) {
                throw new IllegalArgumentException("symbolic links are not supported: " + item);
            }
            if (Files.isRegularFile(item)) {
                String relative = path.relativize(item).toString().replace('\\', '/');
                files.put(relative, AtomicFiles.readText(item));
            }
        }
        return files;
    }

    /** Copies the baseline into the loop's own directory so the source artifact stays pristine. */
    public Path snapshot(String loopId, Path source) {
        Path loopRoot = root.resolve(Names.safeName(loopId, "loop_id"));
        if (Files.exists(loopRoot)) {
            throw new IllegalArgumentException("auto evolution workspace already exists: " + loopRoot);
        }
        Path target = loopRoot.resolve("baseline").resolve(source.getFileName().toString());
        AtomicFiles.createDirectories(target.getParent());
        readArtifact(source);
        try {
            if (Files.isDirectory(source)) {
                copyTree(source, target);
            } else {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return target;
    }

    /**
     * Writes one candidate into the workspace and returns its path.
     *
     * <p>Staging is idempotent: re-staging the same candidate after an interrupted run returns the
     * existing directory once its manifest proves the content is identical, and refuses otherwise.
     */
    public Path stage(
            String loopId,
            int roundNumber,
            TextCandidate candidate,
            String name,
            Path currentArtifact) {
        String candidateId = Names.safeName(candidate.candidateId(), "candidate_id");
        Path target =
                root.resolve(Names.safeName(loopId, "loop_id"))
                        .resolve("candidates")
                        .resolve("round-" + roundNumber)
                        .resolve(candidateId)
                        .resolve(name);

        if (!candidate.operations().isEmpty() || !candidate.files().isEmpty()) {
            if (!candidate.operations().isEmpty() && !candidate.files().isEmpty()) {
                throw new IllegalArgumentException("candidate must use files or operations, not both");
            }
            String directoryError =
                    !candidate.operations().isEmpty()
                            ? "file operations require a directory artifact"
                            : "multi-file candidate requires a directory artifact";
            if (currentArtifact == null || !Files.isDirectory(currentArtifact)) {
                throw new IllegalArgumentException(directoryError);
            }
            Object current = readArtifact(currentArtifact);
            if (!(current instanceof Map)) {
                throw new IllegalArgumentException(directoryError);
            }

            Map<String, Object> manifestContent = new LinkedHashMap<>();
            List<TextFileOperation> operations;
            if (!candidate.operations().isEmpty()) {
                List<Object> rows = new ArrayList<>(candidate.operations().size());
                for (TextFileOperation operation : candidate.operations()) {
                    rows.add(operation.toJson());
                }
                manifestContent.put("operations", rows);
                operations = candidate.operations();
            } else {
                manifestContent.put("files", candidate.files());
                operations = new ArrayList<>();
                for (Map.Entry<String, String> entry : candidate.files().entrySet()) {
                    operations.add(new TextFileOperation("write", entry.getKey(), entry.getValue(), null));
                }
            }
            List<TextFileOperations.Validated> validated = TextFileOperations.validate(asStringMap(current), operations);

            Path manifestPath = target.getParent().resolve("artifact-manifest.json");
            if (Files.exists(target)) {
                if (!Files.isRegularFile(manifestPath)) {
                    throw new IllegalArgumentException("candidate directory exists without manifest: " + target);
                }
                Map<String, Object> manifest = Json.parseObject(AtomicFiles.readText(manifestPath));
                for (Map.Entry<String, Object> entry : manifestContent.entrySet()) {
                    if (!java.util.Objects.equals(
                            Json.normalize(manifest.get(entry.getKey())), Json.normalize(entry.getValue()))) {
                        throw new IllegalArgumentException(
                                "candidate artifact already exists with different content: " + target);
                    }
                }
                if (!java.util.Objects.equals(manifest.get("tree_sha256"), treeSha256(target))) {
                    throw new IllegalArgumentException(
                            "candidate artifact already exists with different content: " + target);
                }
                return target;
            }
            AtomicFiles.createDirectories(target.getParent());
            try {
                copyTree(currentArtifact, target);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            TextFileOperations.apply(target, validated);
            manifestContent.put("tree_sha256", treeSha256(target));
            AtomicFiles.writeText(manifestPath, Json.write(manifestContent, 2));
            return target;
        }

        if (Files.exists(target)) {
            if (AtomicFiles.readText(target).equals(candidate.content())) {
                return target;
            }
            throw new IllegalArgumentException("candidate artifact already exists with different content: " + target);
        }
        AtomicFiles.createDirectories(target.getParent());
        AtomicFiles.writeText(target, candidate.content());
        return target;
    }

    public Path saveCheckpoint(String loopId, Map<String, Object> state) {
        Path path = root.resolve(Names.safeName(loopId, "loop_id")).resolve("checkpoint.json");
        AtomicFiles.writeText(path, Json.write(state, 2));
        return path;
    }

    public Map<String, Object> loadCheckpoint(String loopId) {
        Path path = root.resolve(Names.safeName(loopId, "loop_id")).resolve("checkpoint.json");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("auto evolution checkpoint does not exist: " + path);
        }
        return Json.parseObject(AtomicFiles.readText(path));
    }

    private static Map<String, String> asStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /** Hashes the whole tree so a partially applied candidate set can be detected on resume. */
    static String treeSha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required but unavailable", error);
        }
        Map<String, String> files = asStringMap(readArtifact(path));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        StringBuilder builder = new StringBuilder();
        for (byte item : digest.digest()) {
            builder.append(Character.forDigit((item >> 4) & 0xF, 16));
            builder.append(Character.forDigit(item & 0xF, 16));
        }
        return builder.toString();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        for (Path item : listRecursively(source)) {
            Path destination = target.resolve(source.relativize(item).toString());
            if (Files.isDirectory(item)) {
                Files.createDirectories(destination);
            } else if (Files.isSymbolicLink(item)) {
                throw new IllegalArgumentException("symbolic links are not supported: " + item);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(item, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static List<Path> listRecursively(Path root) throws IOException {
        List<Path> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(item -> !item.equals(root)).forEach(items::add);
        }
        items.sort(Comparator.comparing(Path::toString));
        return items;
    }
}
