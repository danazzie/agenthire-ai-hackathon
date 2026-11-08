# The agent runner, as Astropods builds it (astropods.yml at the repo root; the build context is the repo
# root, because the runner compiles the shared agents module). Locally:
#   docker build -f agent-runner/Dockerfile -t hiremind-agents .
#   docker run --env-file .env -e CONTROL_PLANE_URL=http://host.docker.internal:8088 -p 8090:80 hiremind-agents

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon -q
COPY agents agents
COPY agent-runner agent-runner
# Not built here, but settings.gradle.kts includes it, so Gradle needs it present.
COPY control-plane/build.gradle.kts control-plane/build.gradle.kts
RUN ./gradlew :agent-runner:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
COPY --from=build /src/agent-runner/build/install/agent-runner /app
# Astropods' frontend interface routes to port 80. Binding it needs root unless the cluster lowers
# ip_unprivileged_port_start, which Astropods does not document; the container holds only the runner's
# own settings and never any PayPal credential.
ENV PORT=80 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"
EXPOSE 80
CMD ["/app/bin/agent-runner"]
