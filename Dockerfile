FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/tasks/_parley-room_executableJarJvm/parley-room-jvm-executable.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]