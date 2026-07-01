FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/ringforge-chord-platform-0.1.0-SNAPSHOT.jar /app/ringforge.jar
ENTRYPOINT ["java", "-cp", "/app/ringforge.jar"]
