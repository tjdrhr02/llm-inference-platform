# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 캐시 최적화: 의존성 먼저
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline

# 소스 복사 후 패키징
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package


FROM eclipse-temurin:17-jre
WORKDIR /app

# non-root 실행 (K8s/운영 권장)
RUN useradd -r -u 10001 -g root spring \
  && mkdir -p /app \
  && chown -R 10001:0 /app

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8080

# 기본은 prod. 실행 시 -e SPRING_PROFILES_ACTIVE=dev 로 오버라이드 가능
ENV SPRING_PROFILES_ACTIVE=prod

# 런타임 튜닝은 여기 또는 실행 시 -e JAVA_TOOL_OPTIONS=... 로 조정
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

