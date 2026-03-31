# ── Build stage ──────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --prefer-offline

COPY . .
RUN npm run build

# ── Runtime stage ────────────────────────────────
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/dist ./
COPY nginx.conf /etc/nginx/conf.d/default.conf

# nginx master process must run as root to bind port 80;
# worker processes drop privileges via the 'user' directive in nginx.conf
EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost/index.html || exit 1

CMD ["nginx", "-g", "daemon off;"]
