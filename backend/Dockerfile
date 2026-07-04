FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd -r -u 10001 appuser

COPY --from=build /app/target/*.jar /app/app.jar

EXPOSE 8080

USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
