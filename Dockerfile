FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY build/libs/*.jar app.jar
COPY application.yml application.yml

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]