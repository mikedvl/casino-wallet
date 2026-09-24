FROM node:20.20.2-alpine3.22 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY angular.json tsconfig*.json ./
COPY src/ src/
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28.2-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/casino-wallet/browser/ /usr/share/nginx/html/
USER 101
EXPOSE 8080
