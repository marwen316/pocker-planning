# syntax=docker/dockerfile:1
FROM node:22-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
COPY backend/ ./backend/
COPY --from=frontend /build/frontend/dist/frontend/browser/ ./frontend/dist/frontend/browser/
RUN mvn -B -Dfrontend.skip=true verify

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 planning \
    && useradd --uid 10001 --gid planning --no-create-home planning
WORKDIR /app
COPY --from=backend --chown=planning:planning /build/target/pocker-planning.jar ./app.jar
USER planning
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=4 \
  CMD curl --fail --silent http://127.0.0.1:8080/ > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
