# syntax=docker/dockerfile:1.7
#
# ERP backend runtime image. Build context: repository root.
#   cd backend && ./gradlew bootJar && cd .. && docker build -f infra/docker/backend.Dockerfile -t erp-backend .
# The jar is built outside `docker build` because jOOQ code generation starts a PostgreSQL
# container (Testcontainers), which needs a Docker daemon (see .dockerignore).

ARG JRE_IMAGE=eclipse-temurin:25-jre-noble

FROM ${JRE_IMAGE} AS extract
WORKDIR /workspace
COPY backend/build/libs/erp-backend.jar app.jar
# Spring Boot layered extraction: dependencies change rarely, application classes often.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${JRE_IMAGE}
RUN groupadd --system --gid 10001 erp \
 && useradd --system --uid 10001 --gid erp --home-dir /app --no-create-home --shell /usr/sbin/nologin erp
WORKDIR /app
COPY --from=extract /workspace/extracted/dependencies/ ./
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract /workspace/extracted/application/ ./

# Non-root, no shell login. Run with a read-only root filesystem and a tmpfs at /tmp.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8"
ENV SPRING_PROFILES_ACTIVE=prod
# 8080: API. 8081: management (health probes, metrics) - never expose publicly.
EXPOSE 8080 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
