package io.assay.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the module/package layering honest as the framework grows.
 *
 * <p>The Maven reactor already prevents module cycles; these rules cover package-level drift that
 * the compiler cannot see: core must stay independent, runtime services must depend on core
 * interfaces rather than concrete stores, and optional integrations must not leak inward.
 */
@AnalyzeClasses(packages = "io.assay", importOptions = {
    ImportOption.DoNotIncludeTests.class, LayeringRulesTest.ExcludeTestArtifacts.class
})
class LayeringRulesTest {

    /**
     * Test-jar dependencies are ordinary jars on the classpath; ArchUnit cannot tell them apart
     * from production classes by package alone, so exclude them by location.
     */
    public static final class ExcludeTestArtifacts implements ImportOption {
        @Override
        public boolean includes(Location location) {
            String uri = location.asURI().toString();
            return !uri.contains("-tests.jar") && !uri.contains("test-classes");
        }
    }

    private static final String[] CORE = {
        "io.assay.model", "io.assay.engine", "io.assay.metrics", "io.assay.evolve",
        "io.assay.report", "io.assay.rules", "io.assay.runner", "io.assay.compare",
        "io.assay.release", "io.assay.json", "io.assay.support", "io.assay.store"
    };
    private static final String[] RUNTIME = {
        "io.assay.execution..", "io.assay.process..", "io.assay.workspace..",
        "io.assay.llm..", "io.assay.diff..", "io.assay.audit..", "io.assay.trace..",
        "io.assay.selection..", "io.assay.review..", "io.assay.opentrace..",
        "io.assay.http..", "io.assay.autoevolve..", "io.assay.adapters.."
    };
    private static final String[] ADAPTERS = {
        "io.assay.store.file..", "io.assay.store.jdbc..", "io.assay.synth..",
        "io.assay.agentscope..", "io.assay.examples..", "io.assay.bootstrap.."
    };
    private static final String[] ENTRY_POINTS = {"io.assay.cli..", "io.assay.server.."};
    private static final String[] EXTENSIONS = {
        "io.assay.spring..", "io.assay.metrics.micrometer..", "io.assay.tracing.otel.."
    };
    private static final String[] DASHBOARD = {"io.assay.web.."};
    private static final String[] NON_CORE = join(RUNTIME, ADAPTERS, ENTRY_POINTS, EXTENSIONS, DASHBOARD);

    @ArchTest
    static final ArchRule core_is_independent =
        noClasses().that().resideInAnyPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(NON_CORE)
            .because("core must not depend on runtime services, adapters, entry points, or optional extensions");

    @ArchTest
    static final ArchRule runtime_does_not_depend_on_adapters_or_entry_points =
        noClasses().that().resideInAnyPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAnyPackage(join(ADAPTERS, ENTRY_POINTS, EXTENSIONS, DASHBOARD))
            .because("runtime services depend on core interfaces; CLI/server wire concrete stores");

    @ArchTest
    static final ArchRule store_adapters_only_depend_on_core =
        noClasses().that().resideInAnyPackage("io.assay.store.file..", "io.assay.store.jdbc..")
            .should().dependOnClassesThat().resideInAnyPackage(join(RUNTIME, ENTRY_POINTS, EXTENSIONS,
                DASHBOARD, new String[]{"io.assay.synth..", "io.assay.agentscope..",
                    "io.assay.examples..", "io.assay.bootstrap.."}))
            .because("stores implement core interfaces and must not depend on services or entry points");

    @ArchTest
    static final ArchRule bootstrap_does_not_depend_on_services_or_entry_points =
        noClasses().that().resideInAnyPackage("io.assay.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage(join(RUNTIME, ENTRY_POINTS, EXTENSIONS,
                DASHBOARD, new String[]{"io.assay.synth..", "io.assay.agentscope..", "io.assay.examples.."}))
            .because("bootstrap only opens concrete stores for composition roots");

    @ArchTest
    static final ArchRule dashboard_does_not_depend_on_services_or_stores =
        noClasses().that().resideInAnyPackage(DASHBOARD)
            .should().dependOnClassesThat().resideInAnyPackage(join(RUNTIME, ENTRY_POINTS, EXTENSIONS,
                ADAPTERS, new String[]{"io.assay.store.file..", "io.assay.store.jdbc.."}))
            .because("the read-only dashboard reads core report artifacts; it must not pull in runtime or stores");

    @ArchTest
    static final ArchRule optional_adapters_stay_on_the_edge =
        noClasses().that().resideInAnyPackage("io.assay.synth..", "io.assay.agentscope..", "io.assay.examples..")
            .should().dependOnClassesThat().resideInAnyPackage(join(RUNTIME, ENTRY_POINTS, EXTENSIONS,
                DASHBOARD, new String[]{"io.assay.store.file..", "io.assay.store.jdbc..", "io.assay.bootstrap.."}))
            .because("optional adapters depend on core contracts, not on runtime or composition modules");

    @ArchTest
    static final ArchRule extensions_are_optional =
        noClasses().that().resideInAnyPackage(join(CORE, RUNTIME, ADAPTERS, ENTRY_POINTS, DASHBOARD))
            .should().dependOnClassesThat().resideInAnyPackage(EXTENSIONS)
            .because("Micrometer, OpenTelemetry and Spring Boot integrations must stay opt-in");

    @ArchTest
    static final ArchRule model_stays_data_only =
        noClasses().that().resideInAPackage("io.assay.model")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.assay.engine", "io.assay.metrics", "io.assay.evolve", "io.assay.report",
                "io.assay.rules", "io.assay.runner", "io.assay.compare", "io.assay.release",
                "io.assay.store")
            .because("model records describe data and must not depend on evaluation services");

    @ArchTest
    static final ArchRule report_does_not_depend_on_engine_or_metrics =
        noClasses().that().resideInAPackage("io.assay.report")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.assay.engine", "io.assay.metrics", "io.assay.evolve", "io.assay.rules",
                "io.assay.runner", "io.assay.store", "io.assay.compare", "io.assay.release")
            .because("reporting renders results; it must not reach back into the evaluation engine");

    private static String[] join(String[]... groups) {
        int size = 0;
        for (String[] group : groups) {
            size += group.length;
        }
        String[] result = new String[size];
        int at = 0;
        for (String[] group : groups) {
            System.arraycopy(group, 0, result, at, group.length);
            at += group.length;
        }
        return result;
    }
}
