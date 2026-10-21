# Build with Maven, run with a minimal JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY assay-core/pom.xml ./assay-core/pom.xml
COPY assay-core/src ./assay-core/src
COPY assay-store-file/pom.xml ./assay-store-file/pom.xml
COPY assay-store-file/src ./assay-store-file/src
COPY assay-store-jdbc/pom.xml ./assay-store-jdbc/pom.xml
COPY assay-store-jdbc/src ./assay-store-jdbc/src
COPY extensions/assay-spring-boot-starter/pom.xml ./extensions/assay-spring-boot-starter/pom.xml
COPY extensions/assay-metrics-micrometer/pom.xml ./extensions/assay-metrics-micrometer/pom.xml
COPY extensions/assay-tracing-otel/pom.xml ./extensions/assay-tracing-otel/pom.xml
COPY app/pom.xml ./app/pom.xml
COPY app/src ./app/src
RUN mvn -q -DskipTests -pl :assay -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home assay

COPY --from=build /workspace/app/target/assay.jar ./assay.jar
COPY examples ./examples

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "assay.jar"]
CMD ["--help"]
