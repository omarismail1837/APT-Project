# Step 1: Build the project
FROM maven:3.8.5-openjdk-17 AS build
COPY . .
RUN mvn -pl Server -am clean package -DskipTests

# Step 2: Run the project
FROM openjdk:17-jdk-slim
WORKDIR /app
# This is the line that fixes your error by looking in the sub-folder
COPY --from=build /Server/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]