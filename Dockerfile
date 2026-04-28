# Stage 1: Build the application using JDK 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Runtime environment using JRE 21
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the jar from the build stage
COPY --from=build /app/target/*.jar app.jar

# JAVA_OPTS defaults to Generational ZGC, which is the highlight of Java 21
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms2g -Xmx4g"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]