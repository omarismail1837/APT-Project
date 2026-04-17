# Step 1: Build the project
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn -pl server -am clean package -DskipTests -X

# Step 2: Run the project
# We switched from openjdk to eclipse-temurin because it's more reliable
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
# Ensure 'Server' is capitalized exactly like your folder name
COPY --from=build /app/server/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]