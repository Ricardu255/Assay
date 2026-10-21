package io.assay.engine;

import io.assay.json.Json;
import io.assay.model.EvalCase;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads externally supplied JSONL case files. */
public final class CaseLoader {

    private CaseLoader() {
    }

    /** Loads every non-blank line as one case, tagging each with the suite it is being run as. */
    public static List<EvalCase> loadCases(Path path, String suite) {
        List<EvalCase> cases = new ArrayList<>();
        String content = AtomicFiles.readText(path);
        String[] lines = content.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            try {
                cases.add(EvalCase.fromDict(Json.parseObject(line), suite));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "invalid case at " + path + ":" + (index + 1) + ": " + error.getMessage(), error);
            }
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("case file is empty: " + path);
        }
        return cases;
    }
}
