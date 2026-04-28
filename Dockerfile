# Use the Alpine-based JRE for the smallest possible size
FROM eclipse-temurin:21-jre-alpine

# Alpine is so small it doesn't even have common shell tools by default,
# but this Temurin image includes what we need for Java.
WORKDIR /app

# Copy your locally built JAR
# (Build it first with: ./mvnw clean package -DskipTests)
COPY target/*.jar app.jar

# Standard port
EXPOSE 8080

# Execute
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]