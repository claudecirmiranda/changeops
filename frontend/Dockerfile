# ── Build stage ──────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci --prefer-offline

COPY . .

# ── CI gate (same checks as GitHub Actions) ──────
RUN npx tsc --noEmit
RUN npm run lint
RUN npx prettier --check "src/**/*.{ts,tsx}"
RUN npm run test -- --run --reporter=verbose
RUN npm run build

# ── Runtime stage ────────────────────────────────
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY --from=builder /app/dist ./
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost/index.html || exit 1

CMD ["nginx", "-g", "daemon off;"]
