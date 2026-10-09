package io.assay.testing;

import io.assay.engine.ProjectAdapterFactory;
import io.assay.model.NormalizedTrace;
import io.assay.model.ProjectAdapter;
import io.assay.model.Rule;
import io.assay.model.TraceEvent;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An adapter whose target is the source tree of the repository under audit.
 *
 * <p>Each case names the repository in its metadata and expects the {@code VALUE} constant it declares.
 * That makes the whole {@code audit-scope} slice testable end to end: the change being audited is the
 * same change the regression suite evaluates.
 */
public final class FileValueAdapter implements ProjectAdapterFactory {

    private static final Pattern VALUE = Pattern.compile("VALUE\\s*=\\s*(\\d+)");

    @Override
    public ProjectAdapter create() {
        return new ProjectAdapter(
                "file-value",
                (evalCase, context) -> {
                    Path repository = Path.of(Fixtures.str(evalCase.metadata().get("repository")));
                    Map<String, Object> handle = new LinkedHashMap<>();
                    handle.put("trace_id", context.runId() + "-" + evalCase.caseId());
                    handle.put("value", readValue(repository));
                    return handle;
                },
                (handle, evalCase) -> {
                    Object value = Fixtures.asMap(handle).get("value");
                    return new NormalizedTrace(
                            Fixtures.str(Fixtures.asMap(handle).get("trace_id")),
                            Map.of("value", value),
                            List.of(new TraceEvent("ValueSource", "read")),
                            Map.of("value", value),
                            Map.of(),
                            "skill",
                            "value-source",
                            "audited",
                            Map.of());
                },
                List.of(Rule.of("value", "fields.value", "value")),
                List.of());
    }

    private static int readValue(Path repository) {
        String text = AtomicFiles.readText(repository.resolve("src/main/java/example/Value.java"));
        Matcher matcher = VALUE.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("the audited revision declares no VALUE constant");
        }
        return Integer.parseInt(matcher.group(1));
    }
}
