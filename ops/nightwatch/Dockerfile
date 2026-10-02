# Nightwatch's image: Node 22, the pg driver, and src/. No build stage — Node 22 runs the .ts
# files directly by stripping the types (tsconfig.json keeps the syntax to what strips cleanly).
FROM node:22-alpine
WORKDIR /nightwatch
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src/ ./src/
USER node
# ENTRYPOINT, not CMD, so `docker compose run --rm nightwatch 2026-09-23` passes the date through.
ENTRYPOINT ["node", "src/main.ts"]
