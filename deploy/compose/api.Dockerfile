FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -pl workbench-api -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home kmp
WORKDIR /app
COPY --from=build /workspace/backend/workbench-api/target/workbench-api-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
