# --- Build stage: compile with the Gradle wrapper (pins Gradle 9.3.0) ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew installDist --no-daemon

# --- Runtime stage: JRE only ---
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/install/mtr ./

# Secrets are injected at runtime (Secret Manager -> env), never baked into the image.
# The daily watchlist (data/watchlist.json) is mounted as a volume at runtime.
ENV JAVA_OPTS=""
ENTRYPOINT ["./bin/mtr"]
