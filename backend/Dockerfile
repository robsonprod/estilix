# Build stage: compile and package the Spring Boot jar (tests run in CI, not here).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 estilix
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER estilix
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
