# Builds the CLI distribution (`app/target/assay.jar`). The Spring Boot server is a separate
# artifact and is intentionally not part of this image.
# Build with Maven, run with a minimal JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY assay-core/pom.xml ./assay-core/pom.xml
COPY assay-store-file/pom.xml ./assay-store-file/pom.xml
COPY assay-store-jdbc/pom.xml ./assay-store-jdbc/pom.xml
COPY assay-bootstrap/pom.xml ./assay-bootstrap/pom.xml
COPY assay-dashboard/pom.xml ./assay-dashboard/pom.xml
COPY assay-synth/pom.xml ./assay-synth/pom.xml
COPY assay-cli/pom.xml ./assay-cli/pom.xml
COPY assay-integration-tests/pom.xml ./assay-integration-tests/pom.xml
# POM-only modules: copied so the reactor resolves; app does not depend on them.
COPY extensions/assay-spring-boot-starter/pom.xml ./extensions/assay-spring-boot-starter/pom.xml
COPY extensions/assay-metrics-micrometer/pom.xml ./extensions/assay-metrics-micrometer/pom.xml
COPY extensions/assay-tracing-otel/pom.xml ./extensions/assay-tracing-otel/pom.xml
COPY assay-runtime/pom.xml ./assay-runtime/pom.xml
COPY assay-examples/pom.xml ./assay-examples/pom.xml
COPY assay-server/pom.xml ./assay-server/pom.xml
COPY assay-agentscope/pom.xml ./assay-agentscope/pom.xml
COPY app/pom.xml ./app/pom.xml
# Warm the Maven cache with the full descriptor set before source is copied, so normal
# source edits do not re-download every dependency. The goal may not resolve the reactor's
# own artifacts yet; external downloads are still cached for the packaging step.
RUN mvn -B -ntp -DskipTests dependency:go-offline || true
COPY assay-core/src ./assay-core/src
COPY assay-store-file/src ./assay-store-file/src
COPY assay-store-jdbc/src ./assay-store-jdbc/src
COPY assay-bootstrap/src ./assay-bootstrap/src
COPY assay-dashboard/src ./assay-dashboard/src
COPY assay-synth/src ./assay-synth/src
COPY assay-cli/src ./assay-cli/src
COPY assay-runtime/src ./assay-runtime/src
COPY assay-examples/src ./assay-examples/src
RUN mvn -q -DskipTests -pl :assay -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home assay

COPY --from=build /workspace/app/target/assay.jar ./assay.jar
COPY examples ./examples

USER 10001
ENTRYPOINT ["java", "-jar", "assay.jar"]
CMD ["--help"]
