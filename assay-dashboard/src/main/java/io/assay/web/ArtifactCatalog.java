package io.assay.web;

import io.assay.json.Json;
import io.assay.model.SafePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Read-only access to the report artifacts; the store and evaluation engine are never invoked. */
public final class ArtifactCatalog {
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final Map<String, String> KINDS = Map.of(
            "results.json", "run", "evolution.json", "evolution",
            "auto_evolution.json", "auto", "scope_audit.json", "audit", "release.json", "release");
    private static final int MAX_CACHED_SUMMARIES = 512;
    private final Path root;
    private final Map<Path, Summary> summaries = new ConcurrentHashMap<>();

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
            try {
                Summary summary = summary(file);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", summary.id());
                item.put("kind", summary.kind());
                item.put("path", summary.relative());
                item.put("modified_at", summary.modified().toInstant().toString());
                item.putAll(summary.fields());
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
        return Json.parseObject(readText(reportPath(id), MAX_FILE_BYTES));
    }

    /** Returns the latest points for one rule, optionally filtered to a suite. */
    public Map<String, Object> ruleHistory(String rule, String suite, int limit) throws IOException {
        String ruleName = query(rule, "rule", 128);
        String suiteFilter = suite == null || suite.isBlank() ? "" : query(suite, "suite", 128);
        int max = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> points = new ArrayList<>();
        if (Files.isDirectory(root)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root, 16)) {
                walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && "run".equals(KINDS.get(path.getFileName().toString()))).forEach(files::add);
            }
            for (Path file : files) {
                Summary summary;
                try {
                    summary = summary(file);
                } catch (IOException | RuntimeException error) {
                    continue;
                }
                String payloadSuite = String.valueOf(summary.fields().getOrDefault("suite", ""));
                if (!suiteFilter.isEmpty() && !suiteFilter.equals(payloadSuite)) {
                    continue;
                }
                Map<String, Object> row = asMap(summary.ruleStats().get(ruleName));
                if (row.isEmpty()) {
                    continue;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", summary.id());
                point.put("run_id", summary.fields().getOrDefault("run_id", summary.relative()));
                point.put("suite", payloadSuite);
                point.put("source", summary.fields().getOrDefault("source", ""));
                point.put("modified_at", summary.modified().toInstant().toString());
                point.put("case_count", summary.fields().getOrDefault("case_count", 0));
                point.put("level", row.get("level"));
                point.put("evaluated", row.get("evaluated"));
                point.put("passed", row.get("passed"));
                point.put("pass_rate", row.get("pass_rate"));
                point.put("missing", row.get("missing"));
                points.add(point);
            }
        }
        points.sort(Comparator.comparing((Map<String, Object> point) -> String.valueOf(point.get("modified_at"))));
        int matched = points.size();
        if (points.size() > max) {
            points = new ArrayList<>(points.subList(points.size() - max, points.size()));
        }
        return Map.of("rule", ruleName, "suite", suiteFilter, "matched", matched, "points", points);
    }

    private Summary summary(Path file) throws IOException {
        FileTime modified = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        Summary cached = summaries.get(file);
        if (cached != null && cached.modified().equals(modified) && cached.size() == size) {
            return cached;
        }
        String relative = root.relativize(file).toString().replace('\\', '/');
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
        String kind = KINDS.get(file.getFileName().toString());
        Map<String, Object> payload = Json.parseObject(readText(file, MAX_FILE_BYTES));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", switch (kind) {
            case "run" -> payload.getOrDefault("run_id", relative);
            case "evolution" -> payload.getOrDefault("experiment_id", relative);
            case "auto" -> payload.getOrDefault("loop_id", relative);
            case "release" -> payload.getOrDefault("release_id", relative);
            default -> payload.getOrDefault("requirement", relative);
        });
        fields.put(
                "status",
                payload.getOrDefault(
                        "decision", payload.getOrDefault("conclusion", payload.getOrDefault("status", "unknown"))));
        fields.put("run_id", payload.get("run_id"));
        for (String field : List.of("suite", "source", "case_count", "hard_failures", "soft_warnings", "current_version")) {
            fields.put(field, payload.get(field));
        }
        Summary summary = new Summary(
                id, relative, kind, modified, size, fields, asMap(payload.get("rule_stats")));
        if (summaries.size() >= MAX_CACHED_SUMMARIES) {
            summaries.clear();
        }
        summaries.put(file, summary);
        return summary;
    }

    private record Summary(
            String id,
            String relative,
            String kind,
            FileTime modified,
            long size,
            Map<String, Object> fields,
            Map<String, Object> ruleStats) {
    }

    private static String query(String value, String field, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be a non-empty, bounded value");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** Only the fixed patch next to an audit report is exposed, never arbitrary workspace files. */
    public Map<String, Object> readAuditPatch(String id) throws IOException {
        Path report = reportPath(id);
        if (!report.getFileName().toString().equals("scope_audit.json")) {
            throw new IllegalArgumentException("patches are only available for scope audits");
        }
        Map<String, Object> audit = read(id);
        Path patch = report.resolveSibling("diff.patch");
        validateContainedFile(patch);
        String text = readText(patch, 8L * 1024 * 1024);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        Object expected = audit.get("diff_sha256");
        if (expected != null && !digest.equalsIgnoreCase(String.valueOf(expected))) {
            throw new IllegalArgumentException("diff.patch SHA-256 does not match the audit report");
        }
        return Map.of("text", text, "sha256", digest, "verified", expected != null);
    }

    private Path reportPath(String id) throws IOException {
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
        validateContainedFile(path);
        return path;
    }

    private void validateContainedFile(Path path) throws IOException {
        for (Path part = path; part != null && part.startsWith(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IllegalArgumentException("symbolic link artifacts are not supported");
            }
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root)) {
            throw new java.nio.file.NoSuchFileException(path.getFileName().toString());
        }
    }

    private String readText(Path path, long maxBytes) throws IOException {
        if (Files.size(path) > maxBytes) {
            throw new IllegalArgumentException("artifact exceeds the " + (maxBytes / 1024 / 1024) + " MiB viewer limit");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
