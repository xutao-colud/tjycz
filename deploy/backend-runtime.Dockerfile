FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system wanfu && useradd --system --gid wanfu --home /app wanfu && mkdir -p /app/uploads && chown -R wanfu:wanfu /app
WORKDIR /app
COPY app.jar app.jar
USER wanfu
ENV SPRING_PROFILES_ACTIVE=prod APP_UPLOAD_DIR=/app/uploads TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
