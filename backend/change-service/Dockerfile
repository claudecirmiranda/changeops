# ── Build stage ──────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY checkstyle.xml .
RUN mvn dependency:go-offline -q
COPY src ./src

# ── CI gate (same checks as GitHub Actions) ──────
# Integration tests (*IT, *IntegrationTest) are skipped — they need
# Testcontainers (Docker-in-Docker) which is unavailable during image build.
RUN mvn -B verify -Dsurefire.excludes="**/*IT.java,**/*IntegrationTest.java"
RUN mvn -B checkstyle:check

# ── Runtime stage ─────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
