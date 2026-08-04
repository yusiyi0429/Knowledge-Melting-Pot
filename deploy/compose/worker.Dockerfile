FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend ./backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -pl workbench-worker -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home kmp
WORKDIR /app
COPY --from=build /workspace/backend/workbench-worker/target/workbench-worker-*.jar app.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dlogback.configurationFile=logback-spring.xml", "-Dlogging.config=classpath:logback-spring.xml", "-jar", "/app/app.jar"]
