package io.assay.workspace;

import io.assay.model.SafePath;
import io.assay.model.TextFileOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and applies the bounded file operations a text candidate may request.
 *
 * <p>Validation happens against the current file set before anything is copied, so a candidate that
 * would escape the sandbox, collide with an earlier operation, or depend on a file that does not exist
 * is rejected while the baseline tree is still untouched.
 */
public final class TextFileOperations {

    private TextFileOperations() {
    }

    /** One operation paired with its normalised relative paths. */
    public record Validated(TextFileOperation operation, String relativePath, String destination) {
    }

    public static String safeRelativeFile(String value) {
        if (value == null) {
            throw new IllegalArgumentException("candidate file must be a safe relative path");
        }
        try {
            SafePath.require("candidate file", value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("candidate file must be a safe relative path: " + value);
        }
        return SafePath.normalize(value);
    }

    public static List<Validated> validate(Map<String, String> currentFiles, List<TextFileOperation> operations) {
        Set<String> files = new LinkedHashSet<>(currentFiles.keySet());
        Set<String> touched = new LinkedHashSet<>();
        List<Validated> validated = new ArrayList<>();
        for (TextFileOperation operation : operations) {
            String relative = safeRelativeFile(operation.path());
            String destination =
                    operation.destination() != null ? safeRelativeFile(operation.destination()) : null;
            Set<String> affected = new LinkedHashSet<>();
            affected.add(relative);
            if (destination != null) {
                affected.add(destination);
            }
            for (String item : affected) {
                if (touched.contains(item)) {
                    throw new IllegalArgumentException(
                            "candidate file operations conflict at: " + sorted(affected));
                }
            }
            touched.addAll(affected);

            switch (operation.operation()) {
                case "write" -> files.add(relative);
                case "delete" -> {
                    if (!files.contains(relative)) {
                        throw new IllegalArgumentException("delete source does not exist: " + relative);
                    }
                    files.remove(relative);
                }
                default -> {
                    if (!files.contains(relative)) {
                        throw new IllegalArgumentException("move source does not exist: " + relative);
                    }
                    if (files.contains(destination)) {
                        throw new IllegalArgumentException("move destination already exists: " + destination);
                    }
                    files.remove(relative);
                    files.add(destination);
                }
            }
            validated.add(new Validated(operation, relative, destination));
        }

        for (String relative : files) {
            for (String parent : parents(relative)) {
                if (files.contains(parent)) {
                    throw new IllegalArgumentException(
                            "candidate file conflicts with a directory path: " + relative);
                }
            }
        }
        return validated;
    }

    public static void apply(Path root, List<Validated> operations) {
        // Validate public Validated records too; callers can construct them without validate().
        for (Validated validated : operations) {
            resolveFile(root, validated.relativePath());
            if (validated.destination() != null) {
                resolveFile(root, validated.destination());
            }
        }
        for (Validated validated : operations) {
            Path source = resolveFile(root, validated.relativePath());
            switch (validated.operation().operation()) {
                case "write" -> {
                    createParents(source);
                    io.assay.support.AtomicFiles.writeText(source, validated.operation().content());
                }
                case "delete" -> {
                    try {
                        Files.deleteIfExists(source);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }
                default -> {
                    Path moved = resolveFile(root, validated.destination());
                    createParents(moved);
                    try {
                        Files.move(
                                source,
                                moved,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }
            }
        }
    }

    private static Path resolveFile(Path root, String value) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(safeRelativeFile(value)).normalize();
        if (target.equals(base) || !target.startsWith(base)) {
            throw new IllegalArgumentException("candidate file escapes its workspace: " + value);
        }
        for (Path item = target; item != null && item.startsWith(base); item = item.getParent()) {
            if (Files.isSymbolicLink(item)) {
                throw new IllegalArgumentException("symbolic links are not supported: " + item);
            }
        }
        return target;
    }

    private static void createParents(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Every ancestor path of {@code relative}, longest first, excluding the root marker. */
    private static List<String> parents(String relative) {
        List<String> result = new ArrayList<>();
        int index = relative.length();
        while (true) {
            int slash = relative.lastIndexOf('/', index - 1);
            if (slash <= 0) {
                break;
            }
            result.add(relative.substring(0, slash));
            index = slash;
        }
        return result;
    }

    private static String sorted(Set<String> values) {
        List<String> items = new ArrayList<>(values);
        Collections.sort(items);
        return items.toString();
    }
}
