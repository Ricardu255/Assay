package io.assay.diff;

import io.assay.model.TestImpactAssessment;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Git change and classifies its test impact with deterministic rules.
 *
 * <p>Every git call is a plain {@link ProcessBuilder} invocation. The argument list is fixed, so the
 * same change always yields the same diff text.
 */
public final class DiffAnalysis {

    private DiffAnalysis() {
    }

    /** Runs git in {@code repository} and returns stdout, raising with stderr on a non-zero exit. */
    public static String git(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 3);
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        Collections.addAll(command, arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IllegalStateException("git command failed", error);
        }
        byte[][] stderrHolder = new byte[1][];
        Thread stderrReader = new Thread(() -> {
            try {
                stderrHolder[0] = process.getErrorStream().readAllBytes();
            } catch (IOException ignored) {
                stderrHolder[0] = new byte[0];
            }
        });
        stderrReader.start();
        byte[] stdout;
        int exitCode;
        try {
            // Drain stdout on this thread while stderr is consumed concurrently, so a chatty command
            // cannot fill one pipe and deadlock against the other.
            stdout = process.getInputStream().readAllBytes();
            exitCode = process.waitFor();
            stderrReader.join();
        } catch (IOException error) {
            throw new IllegalStateException("git command failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git command interrupted", error);
        }
        String stderrText = decode(stderrHolder[0]);
        if (exitCode != 0) {
            String message = stderrText.strip();
            throw new IllegalArgumentException(message.isEmpty() ? "git command failed" : message);
        }
        return decode(stdout);
    }

    /**
     * Snapshots the change between two revisions (or the working tree against {@code base}).
     *
     * <p>When {@code target} is null the change is {@code base}..worktree and untracked files are folded
     * in as synthetic additions.
     */
    public static DiffSnapshot readGitDiff(Path repository, String base, String target) {
        Path resolved = repository.toAbsolutePath().normalize();
        String baseCommit;
        String targetCommit;
        List<String> revisions;
        List<String> diffOptions;
        List<String> untracked;
        if (target != null) {
            if (base.startsWith("-") || target.startsWith("-")) {
                throw new IllegalArgumentException("commit references must not be command options");
            }
            baseCommit = git(resolved, "rev-parse", "--verify", base + "^{commit}").strip();
            targetCommit = git(resolved, "rev-parse", "--verify", target + "^{commit}").strip();
            revisions = List.of(baseCommit, targetCommit);
            diffOptions = List.of("--no-renames");
            untracked = List.of();
        } else {
            baseCommit = null;
            targetCommit = null;
            revisions = List.of(base);
            diffOptions = List.of();
            untracked = nonEmptyLines(git(resolved, "ls-files", "--others", "--exclude-standard"));
        }

        List<String> diffArguments = new ArrayList<>();
        diffArguments.add("-c");
        diffArguments.add("core.quotepath=false");
        diffArguments.add("diff");
        diffArguments.add("--no-ext-diff");
        diffArguments.add("--unified=1");
        diffArguments.addAll(diffOptions);
        diffArguments.addAll(revisions);
        diffArguments.add("--");
        String rawDiff = git(resolved, diffArguments.toArray(new String[0]));

        List<String> numstatArguments = new ArrayList<>();
        numstatArguments.add("-c");
        numstatArguments.add("core.quotepath=false");
        numstatArguments.add("diff");
        numstatArguments.add("--numstat");
        numstatArguments.addAll(diffOptions);
        numstatArguments.addAll(revisions);
        numstatArguments.add("--");
        String numstat = git(resolved, numstatArguments.toArray(new String[0]));

        List<String> files = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        for (String line : splitlines(numstat)) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                throw new IllegalArgumentException("unexpected numstat line");
            }
            String added = parts[0];
            String removed = parts[1];
            String path = parts[2];
            files.add(path);
            additions += isDigits(added) ? Integer.parseInt(added) : 0;
            deletions += isDigits(removed) ? Integer.parseInt(removed) : 0;
        }

        List<String> untrackedDiff = new ArrayList<>();
        for (String relative : untracked) {
            files.add(relative);
            Path path = resolved.resolve(relative);
            String content = readUtf8Universal(path);
            if (!content.isEmpty() && utf8Length(content) <= 256_000) {
                List<String> lines = splitlines(content);
                additions += lines.size();
                StringBuilder builder = new StringBuilder();
                builder.append("diff --git a/").append(relative).append(" b/").append(relative)
                        .append("\nnew file mode 100644\n");
                builder.append("--- /dev/null\n+++ b/").append(relative).append("\n");
                for (int index = 0; index < lines.size(); index++) {
                    if (index > 0) {
                        builder.append('\n');
                    }
                    builder.append('+').append(lines.get(index));
                }
                untrackedDiff.add(builder.toString());
            }
        }

        List<String> uniqueFiles = new ArrayList<>(new LinkedHashSet<>(files));
        Map<String, Integer> categories = new LinkedHashMap<>();
        for (String path : uniqueFiles) {
            categories.merge(category(path), 1, Integer::sum);
        }
        Map<String, Integer> extensions = new LinkedHashMap<>();
        for (String path : uniqueFiles) {
            String suffix = posixSuffix(path).toLowerCase(Locale.ROOT);
            extensions.merge(suffix.isEmpty() ? "[none]" : suffix, 1, Integer::sum);
        }
        List<String> signals = new ArrayList<>(categories.keySet());
        Collections.sort(signals);

        String combined = rawDiff;
        if (!untrackedDiff.isEmpty()) {
            combined += (combined.isEmpty() ? "" : "\n") + String.join("\n", untrackedDiff);
        }
        return new DiffSnapshot(
                combined,
                uniqueFiles,
                additions,
                deletions,
                categories,
                extensions,
                signals,
                baseCommit,
                targetCommit);
    }

    /** Snapshots the working tree against {@code base}. */
    public static DiffSnapshot readGitDiff(Path repository, String base) {
        return readGitDiff(repository, base, null);
    }

    /** Classifies the change into the test mode its rules demand, with confidence 1.0. */
    public static TestImpactAssessment assessRules(DiffSnapshot snapshot) {
        Set<String> categories = new HashSet<>(snapshot.categories().keySet());
        boolean lowOnly = Set.of("documentation", "ui", "reporting", "tests").containsAll(categories);
        boolean highRisk = categories.contains("control_logic");
        boolean fullImpact = categories.contains("agent_core");

        String mode;
        String reason;
        if (fullImpact) {
            mode = "full";
            reason = "Agent generation, retrieval, model, or result-structure code changed.";
        } else if (highRisk) {
            mode = "regression";
            reason = "Planner, prompt, safety, or guardrail behavior changed.";
        } else if (lowOnly) {
            mode = "smoke";
            reason = "Only documentation, UI, reporting, or test assets changed.";
        } else {
            mode = "regression";
            reason = "General source or configuration changes need protected-case coverage.";
        }

        String risk = highRisk ? "high" : (mode.equals("smoke") ? "low" : "medium");
        return new TestImpactAssessment(mode, 1.0, risk, List.of(reason), "rules", "metadata");
    }

    /** The coarse bucket a changed path belongs to. */
    public static String category(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.startsWith("docs/") || value.startsWith("readme")
                || value.startsWith("changelog") || value.startsWith("license")) {
            return "documentation";
        }
        for (String part : List.of("ui/", "frontend/", "web/", "templates/", "styles/")) {
            if (value.contains(part)) {
                return "ui";
            }
        }
        for (String part : List.of("report", "render", "markdown", "html")) {
            if (value.contains(part)) {
                return "reporting";
            }
        }
        if (value.startsWith("tests/") || value.startsWith("test/") || value.startsWith("fixtures/")) {
            return "tests";
        }
        for (String part : List.of("prompt", "planner", "safety", "security", "guardrail")) {
            if (value.contains(part)) {
                return "control_logic";
            }
        }
        for (String part : List.of("rag", "retriev", "generat", "schema", "model", "agent")) {
            if (value.contains(part)) {
                return "agent_core";
            }
        }
        if (value.endsWith(".yml") || value.endsWith(".yaml")
                || value.endsWith(".toml") || value.endsWith(".json")) {
            return "configuration";
        }
        return "source";
    }

    private static List<String> nonEmptyLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : splitlines(text)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /** The last path component's extension, or "" — {@code PurePosixPath(path).suffix}. */
    private static String posixSuffix(String path) {
        String name = posixName(path);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot);
        }
        return "";
    }

    private static String posixName(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        int slash = path.lastIndexOf('/', end - 1);
        return path.substring(slash + 1, end);
    }

    /**
     * Decodes UTF-8 with the universal-newline translation a text-mode read applies, so a CRLF diff
     * becomes LF — which every later step assumes.
     */
    private static String decode(byte[] bytes) {
        return universalNewlines(new String(bytes, StandardCharsets.UTF_8));
    }

    private static String readUtf8Universal(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return universalNewlines(text);
        } catch (CharacterCodingException error) {
            // A file that is not valid UTF-8 contributes no synthetic diff.
            return "";
        } catch (IOException error) {
            return "";
        }
    }

    private static String universalNewlines(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Splits on every line boundary the Unicode standard defines, not just LF and CRLF. */
    private static List<String> splitlines(String text) {
        List<String> lines = new ArrayList<>();
        int length = text.length();
        int start = 0;
        int index = 0;
        while (index < length) {
            int boundary = boundaryLength(text, index);
            if (boundary > 0) {
                lines.add(text.substring(start, index));
                index += boundary;
                start = index;
            } else {
                index++;
            }
        }
        if (start < length) {
            lines.add(text.substring(start, length));
        }
        return lines;
    }

    private static int boundaryLength(String text, int index) {
        char character = text.charAt(index);
        switch (character) {
            case '\n':
            case '\r':
            case '\u000b':
            case '\u000c':
            case '\u001c':
            case '\u001d':
            case '\u001e':
            case '\u0085':
            case '\u2028':
            case '\u2029':
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    return 2;
                }
                return 1;
            default:
                return 0;
        }
    }
}
