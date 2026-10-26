# The control plane: Spring Boot on JDK 21, with the pinned Deno that runs the sandboxed verifier.
# Render builds this from the repo root (render.yaml). Locally:
#   docker build -t mutual-stake .
#   docker run --env-file .env -e DB_HOST=host.docker.internal -p 8088:8088 mutual-stake

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
# The Gradle distribution gets its own layer, so a code change does not download it again.
RUN ./gradlew --version --no-daemon -q
COPY agents agents
COPY control-plane control-plane
RUN ./gradlew :control-plane:bootJar --no-daemon -q

# The same Deno version the tests run against (DenoVerifierRunner.DENO_VERSION).
FROM denoland/deno:bin-2.9.7 AS deno

FROM eclipse-temurin:21-jre
COPY --from=deno /deno /usr/local/bin/deno
RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /home/app
COPY --from=build /src/control-plane/build/libs/control-plane-0.1.0.jar app.jar
# The heap is capped so the JVM leaves room for a verifier run (a Deno process) on a small instance.
ENV MUTUALSTAKE_DENO=/usr/local/bin/deno \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -Xss512k"
EXPOSE 8088
CMD ["java", "-jar", "app.jar"]
