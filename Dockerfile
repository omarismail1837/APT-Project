# Step 1: Build the project
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# Copy the entire project into the container
COPY . .

# Select the module by artifactId, not directory name
RUN mvn -pl :server -am clean package -DskipTests -Djavafx.platform=linux

# Step 2: Run the project
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

COPY --from=build /app/Server/target/server-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]