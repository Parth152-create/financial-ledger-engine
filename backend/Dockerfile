# Multi-stage build for Spring Boot application
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# Copy maven wrapper and pom.xml first for caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

# Copy source code and build jar without running tests (tests are run in CI and locally)
COPY src/ src/
RUN ./mvnw clean package -DskipTests

# Minimal runtime image
FROM eclipse-temurin:25-jre
WORKDIR /app

# Install curl for healthcheck support
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Run as non-root user for security
RUN groupadd -r ledger && useradd -r -g ledger ledger
USER ledger:ledger

COPY --from=builder --chown=ledger:ledger /workspace/target/*.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
