FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:17-jre
RUN groupadd --system wanfu && useradd --system --gid wanfu --home /app wanfu && mkdir -p /app/uploads && chown -R wanfu:wanfu /app
WORKDIR /app
COPY --from=build /build/target/wanfu-backend-1.0.0.jar app.jar
USER wanfu
ENV SPRING_PROFILES_ACTIVE=prod APP_UPLOAD_DIR=/app/uploads TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
