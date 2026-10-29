# Build stage: compile the jar with Maven.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy the pom first so dependencies are cached when only the code changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Run stage: only a JRE and the jar, no Maven or source code.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/distributed-kv-store.jar app.jar

# Do not run as root inside the container.
RUN useradd --system --uid 1001 kv && mkdir -p /data && chown kv /data
USER kv

ENV DATA_DIR=/data PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["node"]
