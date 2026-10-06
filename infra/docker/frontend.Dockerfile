# syntax=docker/dockerfile:1.7
#
# ERP web application image: the built SPA behind nginx, with the API proxied on the same origin
# (ADR-023) and the security headers of SECURITY.md §10.1. Build context: repository root.
#   docker build -f infra/docker/frontend.Dockerfile -t erp-web .

FROM node:22-alpine AS build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY infra/docker/nginx/spa.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
# Runs as the unprivileged nginx user on port 8080; mount nothing writable but /tmp.
EXPOSE 8080
