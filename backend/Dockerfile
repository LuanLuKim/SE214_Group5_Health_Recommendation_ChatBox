# Build từ thư mục gốc repo: docker build -f backend/Dockerfile .
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
RUN mvn -B -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/backend/target/health-assistant-backend-0.1.0.jar app.jar
# Flyway đọc migration và seed từ đây.
COPY database database
ENV HOST=0.0.0.0 PORT=4000 DATABASE_DIR=/app/database
USER app
EXPOSE 4000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
