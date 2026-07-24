FROM eclipse-temurin:25-jre-alpine
COPY build/libs/mrv-2.0.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
