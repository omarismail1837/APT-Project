# Step 1: Build the project
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# Copy the entire project
COPY . .

# Build the server module
RUN mvn -pl :server -am clean package -DskipTests -Djavafx.platform=linux

# Step 2: Run the project
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Install dependencies needed for Alpine to run Java apps reliably
RUN apk add --no-cache libstdc++

COPY --from=build /app/server/target/server-1.0-SNAPSHOT.jar app.jar

# IMPORTANT: Railway dynamically assigns a port. We must use the ${PORT} variable.
# We also add -Xmx300m to prevent Out of Memory kills on Railway's 512MB tier.
ENTRYPOINT ["sh", "-c", "java -Xmx300m -Xms300m -Dserver.port=${PORT} -jar app.jar"]