# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
# CI runs both database test suites before publishing; this stage builds the JAR.
RUN --mount=type=cache,target=/root/.gradle sh gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 vocabler \
    && useradd --system --uid 10001 --gid vocabler --home-dir /app vocabler \
    && mkdir -p /app/data \
    && chown -R 10001:10001 /app
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar /app/vocabler.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/vocabler.jar"]
