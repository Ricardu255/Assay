package io.assay.web;

import io.assay.json.Json;
import io.assay.model.SafePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only access to the report artifacts; the store and evaluation engine are never invoked. */
public final class ArtifactCatalog {
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final Map<String, String> KINDS = Map.of(
            "results.json", "run", "evolution.json", "evolution",
            "auto_evolution.json", "auto", "scope_audit.json", "audit", "release.json", "release");
    private final Path root;

    public ArtifactCatalog(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("report output must be a directory");
        }
        this.root = Files.exists(absolute) ? absolute.toRealPath() : absolute;
    }

    public Path root() {
        return root;
    }

    public Map<String, Object> list() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root, 16)) {
                walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && KINDS.containsKey(path.getFileName().toString())).forEach(files::add);
            }
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
            try {
                Map<String, Object> payload = read(id);
                String kind = KINDS.get(file.getFileName().toString());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("kind", kind);
                item.put("path", relative);
                item.put("modified_at", Files.getLastModifiedTime(file).toInstant().toString());
                item.put("name", switch (kind) {
                    case "run" -> payload.getOrDefault("run_id", relative);
                    case "evolution" -> payload.getOrDefault("experiment_id", relative);
                    case "auto" -> payload.getOrDefault("loop_id", relative);
                    case "release" -> payload.getOrDefault("release_id", relative);
                    default -> payload.getOrDefault("requirement", relative);
                });
                item.put("status", payload.getOrDefault("decision", payload.getOrDefault("conclusion", payload.getOrDefault("status", "unknown"))));
                for (String field : List.of("suite", "source", "case_count", "hard_failures", "soft_warnings", "current_version")) {
                    item.put(field, payload.get(field));
                }
                items.add(item);
            } catch (IOException | RuntimeException error) {
                warnings.add(relative + ": " + error.getMessage());
            }
        }
        items.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("modified_at")))
                .reversed().thenComparing(item -> String.valueOf(item.get("path"))));
        return Map.of("root", root.toString(), "items", items, "warnings", warnings);
    }

    public Map<String, Object> read(String id) throws IOException {
        if (id == null || id.length() > 8192) {
            throw new IllegalArgumentException("invalid artifact id");
        }
        String relative;
        try {
            relative = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid artifact id", error);
        }
        SafePath.require("artifact", relative);
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !KINDS.containsKey(path.getFileName().toString())) {
            throw new IllegalArgumentException("unsupported artifact path");
        }
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IllegalArgumentException("symbolic link artifacts are not supported");
            }
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root)) {
            throw new java.nio.file.NoSuchFileException(relative);
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("artifact exceeds the 64 MiB viewer limit");
        }
        return Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
    }
}
