# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:stable-alpine AS runtime
# Plantilla: al arrancar se escribe en conf.d/default.conf con el destino del balanceo (ver nginx.conf).
# Compose usa "backend"; stack.yml pasa "tasks.backend" para repartir entre las réplicas de Swarm.
ENV BACKEND_UPSTREAM_HOST=backend \
    NGINX_ENVSUBST_FILTER=^BACKEND_
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/browser/ /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
