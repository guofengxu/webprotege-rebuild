# Serve the host-built wp-web dist with nginx.
# Build on the host first:
#   pnpm -C frontend --filter wp-web run build
FROM nginx:alpine

COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/wp-web/dist /usr/share/nginx/html

EXPOSE 80
