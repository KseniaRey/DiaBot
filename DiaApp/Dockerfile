# Build stage
FROM maven:3.8.6-openjdk-19 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Package stage
FROM openjdk:19-jdk-slim
WORKDIR /app
COPY --from=build /app/target/DiaApp-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]