package io.assay.cli;

import io.assay.bootstrap.Stores;
import io.assay.json.Json;
import io.assay.report.CalibrationReport;
import io.assay.store.Results;
import io.assay.support.AtomicFiles;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Implements the {@code calibrate} command. */
final class CalibrationCommand {

    private CalibrationCommand() {
    }

    static int run(CommandLine.Namespace args) {
        Map<String, Object> report;
        try (Results store = Stores.open(args.string("store"))) {
            List<Map<String, Object>> reviewed = store.listReviewedResults(null);
            report = CalibrationReport.compute(reviewed, args.string("metric"));
        }
        Path output = args.path("output");
        AtomicFiles.writeText(output, CalibrationReport.markdown(report));
        Map<String, Object> printed = new LinkedHashMap<>(report);
        printed.put("output", output.toString());
        System.out.println(Json.write(printed));
        return 0;
    }
}
