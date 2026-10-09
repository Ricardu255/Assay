package io.assay.model;

import io.assay.json.Jsonable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.assay.support.Immutable;

/** The suites the framework recommends for the current change. */
public record TestSelection(
        String mode,
        List<String> suites,
        TestImpactAssessment ruleAssessment,
        TestImpactAssessment aiAssessment,
        boolean humanReviewRequired,
        List<String> reviewReasons,
        int changedFiles,
        int additions,
        int deletions)
        implements Jsonable {

    public TestSelection {
        suites = suites == null ? List.of() : Immutable.list(suites);
        reviewReasons = reviewReasons == null ? List.of() : Immutable.list(reviewReasons);
    }

    @Override
    public Object toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("suites", suites);
        result.put("rule_assessment", ruleAssessment);
        result.put("ai_assessment", aiAssessment);
        result.put("human_review_required", humanReviewRequired);
        result.put("review_reasons", reviewReasons);
        result.put("changed_files", changedFiles);
        result.put("additions", additions);
        result.put("deletions", deletions);
        return result;
    }
}
