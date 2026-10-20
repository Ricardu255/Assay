# Build with Maven, run with a minimal JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY app/pom.xml ./app/pom.xml
COPY app/src ./app/src
RUN mvn -q -DskipTests -f app/pom.xml package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home assay

COPY --from=build /workspace/app/target/assay.jar ./assay.jar
COPY examples ./examples

USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "assay.jar"]
CMD ["--help"]
