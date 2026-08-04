# Multi-stage so the runtime image carries a JRE and a jar, not a JDK and a Gradle cache.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build files first, on their own layer. Dependency resolution is the slow part and it only needs
# to re-run when the build files change — copying src/ in the same step would invalidate the
# cached dependencies on every source edit.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

# The wrapper is checked out on Windows, so it can arrive with CRLF line endings. The kernel then
# looks for an interpreter named "/bin/sh\r" and reports "no such file or directory" — pointing at
# the shebang line rather than at the line endings, which is a memorably unhelpful error.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew dependencies --no-daemon --quiet || true

COPY src ./src
# Tests are deliberately not run here: they need Testcontainers, which needs a Docker daemon, and
# building an image is the wrong place to be starting containers. CI runs them as a separate step
# before it ever gets to docker build.
RUN ./gradlew bootJar --no-daemon --quiet -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Don't run as root. Nothing here needs it.
RUN groupadd --system chronos && useradd --system --gid chronos chronos

COPY --from=build /src/build/libs/*-SNAPSHOT.jar app.jar
RUN chown chronos:chronos app.jar
USER chronos

EXPOSE 8080

# exec form, so java is PID 1 and receives SIGTERM directly. Wrapped in a shell it would be the
# shell that gets signalled, Spring's shutdown hook would never run, and every worker stop would
# strand its claimed jobs for a full lease — the exact failure graceful shutdown exists to avoid.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
