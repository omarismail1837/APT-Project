# Stage 1: Build
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
# Build the server module
RUN mvn -pl :server -am clean package -DskipTests -Djavafx.platform=linux

# Stage 2: Run
# Using the full Ubuntu-based JRE to support JavaFX-linked modules
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/server/target/*.jar app.jar

# Railway environment variables
ENV PORT 8080
EXPOSE 8080

# Use sh -c to ensure ${PORT} is correctly expanded by the shell
ENTRYPOINT ["sh", "-c", "java -Xmx300m -Xms300m -Dserver.port=${PORT} -jar app.jar"]