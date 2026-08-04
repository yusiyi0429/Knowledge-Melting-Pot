FROM node:24-alpine AS build
WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@11.9.0 --activate
COPY pnpm-workspace.yaml pnpm-lock.yaml ./
COPY frontend/package.json ./frontend/package.json
RUN pnpm install --frozen-lockfile --filter knowledge-melting-pot-frontend...
COPY frontend ./frontend
RUN pnpm --dir frontend build

FROM nginx:1.28.3-alpine3.23
# The official image pins nginx.org-built modules to an exact apk revision, which
# prevents Alpine security revisions of nginx from being installed by `apk upgrade`.
# This static frontend only needs the core Alpine nginx package.
RUN apk del \
      nginx-module-acme \
      nginx-module-geoip \
      nginx-module-image-filter \
      nginx-module-njs \
      nginx-module-xslt \
      nginx \
    && apk add --no-cache nginx \
    && apk upgrade --no-cache
COPY deploy/compose/nginx.conf /etc/nginx/http.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
