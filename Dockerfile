# syntax=docker/dockerfile:1
# The API as a container. Base images are multi-architecture, so the same file builds on an x86
# laptop and on the ARM (Graviton) server. Tests are not run here: they need their own embedded
# PostgreSQL and run before a push (./mvnw test).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# a Windows checkout can leave CRLF in the wrapper; the shell would then refuse it
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/trillopos-backend-*.jar /app.jar

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home trillopos \
    && mkdir -p /data \
    && chown trillopos /data
USER trillopos
WORKDIR /app
COPY --from=build /app.jar app.jar
# the heap follows the container's memory limit; an out-of-memory JVM exits and Docker restarts it
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
