package io.assay.spring;

import io.assay.engine.EvaluationEngine;
import io.assay.engine.EvaluationListener;
import io.assay.model.ProjectAdapter;
import io.assay.store.file.ResultStore;
import io.assay.store.Results;
import io.assay.store.jdbc.JdbcResultStore;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Optional Spring Boot wiring for Assay.
 *
 * <p>The core module stays framework-free; this starter only turns the existing {@link Results} and
 * {@link EvaluationEngine} types into beans. A project-supplied {@link ProjectAdapter} is required
 * for the engine, so the starter never guesses how to call an agent.
 */
@AutoConfiguration
@EnableConfigurationProperties(AssayProperties.class)
@ConditionalOnClass(Results.class)
public class AssayAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "assay.store.type", havingValue = "file", matchIfMissing = true)
    Results fileResults(AssayProperties properties) {
        return new ResultStore(Path.of(properties.getStore().getPath()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "assay.store.type", havingValue = "jdbc")
    Results jdbcResults(ObjectProvider<DataSource> dataSource) {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException("assay.store.type=jdbc requires a DataSource bean");
        }
        return new JdbcResultStore(source);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProjectAdapter.class)
    EvaluationEngine assayEvaluationEngine(
            ProjectAdapter adapter,
            Results results,
            AssayProperties properties,
            ObjectProvider<EvaluationListener> listener) {
        return new EvaluationEngine(
                adapter,
                results,
                null,
                properties.getWorkers(),
                properties.getRetries(),
                properties.getTimeoutSeconds(),
                properties.isCollectFewShot(),
                properties.getRunIdentity(),
                listener.getIfAvailable(() -> EvaluationListener.NOOP));
    }
}
