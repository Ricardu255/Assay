# Build with Maven, run with a minimal JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home assay

COPY --from=build /workspace/target/assay.jar ./assay.jar
COPY examples ./examples

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "assay.jar"]
CMD ["--help"]
