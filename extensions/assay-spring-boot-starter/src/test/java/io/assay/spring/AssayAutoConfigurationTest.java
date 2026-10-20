package io.assay.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.assay.engine.EvaluationEngine;
import io.assay.model.ProjectAdapter;
import io.assay.store.ResultStore;
import io.assay.store.Results;
import io.assay.store.jdbc.JdbcResultStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The starter wires existing Assay types without taking over the agent adapter. */
class AssayAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AssayAutoConfiguration.class))
            .withBean(ProjectAdapter.class, () -> new ProjectAdapter(
                    "test",
                    (evalCase, context) -> Map.of(),
                    (handle, evalCase) -> null,
                    List.of(),
                    List.of()));

    @Test
    void configuresTheFileStoreAndEngine(@TempDir Path root) {
        runner.withPropertyValues("assay.store.path=" + root.toString().replace('\\', '/'))
                .run(context -> {
                    assertThat(context).hasSingleBean(Results.class);
                    assertThat(context.getBean(Results.class)).isInstanceOf(ResultStore.class);
                    assertThat(context).hasSingleBean(EvaluationEngine.class);
                });
    }

    @Test
    void configuresTheJdbcStoreWhenADataSourceIsPresent() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("assay.store.type=jdbc")
                .run(context -> {
                    assertThat(context).hasSingleBean(Results.class);
                    assertThat(context.getBean(Results.class)).isInstanceOf(JdbcResultStore.class);
                });
    }

    @Test
    void doesNotCreateAnEngineWithoutAnAdapter(@TempDir Path root) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AssayAutoConfiguration.class))
                .withPropertyValues("assay.store.path=" + root.toString().replace('\\', '/'))
                .run(context -> {
                    assertThat(context).hasSingleBean(Results.class);
                    assertThat(context).doesNotHaveBean(EvaluationEngine.class);
                });
    }
}
