# 운영 이미지는 로컬에서 검증한 bootJar만 포함한다. 비밀은 실행 시 주입한다.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN groupadd --gid 10001 pebble \
    && useradd --uid 10001 --gid pebble --no-create-home --shell /usr/sbin/nologin pebble \
    && mkdir -p /app/logs/admin-audit \
    && chown -R pebble:pebble /app/logs \
    && chmod 700 /app/logs/admin-audit

ARG JAR_FILE=build/libs/pebble-api-0.0.1-SNAPSHOT.jar
COPY --chown=pebble:pebble ${JAR_FILE} /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
