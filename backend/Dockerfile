# why backend — multi-stage Docker build for Render.
# Render has no native Java/Spring Boot runtime, so the backend ships
# as a container: Maven builds the jar, then only the jar runs on a
# slim JRE image.

# ---- Stage 1: build the jar ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy just the pom first so dependency downloads are cached in their
# own layer and reused until dependencies change.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests already run separately (mvn test); skip them here for faster image builds.
RUN mvn -B package -DskipTests && mv target/explainer-*.jar target/app.jar

# ---- Stage 2: run on a slim JRE (no JDK, no Maven, no source) ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar

# Render injects $PORT; the app falls back to 8080 when it is unset.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
