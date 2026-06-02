FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/index-benchmark-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
