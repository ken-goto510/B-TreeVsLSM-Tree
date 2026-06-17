# ---- Build stage: compile the jar inside Docker (no local Maven/JDK needed) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so they are cached as a layer.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the application.
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage: slim JRE image that runs the jar ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/index-benchmark-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
