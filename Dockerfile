# Builds the one artifact the hall runs: the Spring Boot jar with the React build inside it.
#
# Three stages, so the runtime image carries a JRE and the jar and nothing else — no node, no
# maven, no source. The SPA is built first, by itself, and its dist/ is handed to the Maven
# stage already built; Maven's own npm steps (exec-maven-plugin, see pom.xml) are skipped with
# -Dexec.skip=true and the copy-frontend resources step picks the dist up exactly as it would
# on the Mac. Same jar layout as `mvn package` produces there, same static/index.html inside.
#
# No profile is baked in. SPRING_PROFILES_ACTIVE comes from compose.yaml, so this image is the
# same one whether it runs behind the proxy or on a laptop.

# ---- 1. the SPA -------------------------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /build/frontend
# The lockfile first, on its own layer, so the install is cached until a dependency changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---- 2. the jar -------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
# The pom first, so the dependency download is cached until the pom changes.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend /build/frontend/dist ./frontend/dist
# -Dexec.skip=true skips both npm executions; copy-frontend (maven-resources-plugin) still
# runs and finds the dist from stage 1. Tests run on the Mac and in CI, not here.
RUN mvn -B -DskipTests -Dexec.skip=true package

# ---- 3. the runtime ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre
# A fixed uid, so the host can chown ./data to it before the first start (see
# docs/DEPLOY-VPS.md). Nothing in the container ever runs as root.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
# `*.jar` matches the repackaged fat jar only; the pre-repackage `*.jar.original` does not end
# in .jar and is left behind.
COPY --from=backend --chown=app:app /build/target/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
