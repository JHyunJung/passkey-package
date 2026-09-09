# admin-app — 운영 콘솔 + Flyway 마이그레이션 책임. 1개만 기동한다(스케줄러 리스).
# syntax 지시자를 쓰지 않는 이유는 passkey-app/Dockerfile 주석 참고.
# admin-ui(React)는 build.gradle.kts 의 buildUi 태스크가 Node 18 을 자동
# 다운로드해 빌드하고 jar 의 static/admin 에 번들한다 — 별도 Node 설치 불필요.
#   docker build -t admin-app:0.0.1-SNAPSHOT -f admin-app/Dockerfile .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/
COPY webauthn/build.gradle.kts webauthn/
COPY passkey-app/build.gradle.kts passkey-app/
COPY admin-app/build.gradle.kts admin-app/
COPY rp-app/build.gradle.kts rp-app/
COPY sdk-java/build.gradle.kts sdk-java/
# npm 의존성도 캐시 레이어에 포함 — 소스만 바뀔 때 npm install 재실행 방지
COPY admin-ui/package.json admin-ui/package-lock.json admin-ui/
# '|| true' 는 의도된 것이다: 이 시점엔 소스가 아직 복사되지 않아 Gradle 이
# 프로젝트를 온전히 평가하지 못해 실패할 수 있다. 목적은 의존성 캐시 워밍뿐이고,
# 받아둔 의존성은 캐시에 남는다. 진짜 검증은 아래 bootJar 이며 거기엔 || true 가
# 없으므로, 의존성 해석이 실제로 깨졌다면 그 단계에서 반드시 실패한다.
RUN ./gradlew :admin-app:dependencies --no-daemon --console=plain || true

COPY . .
RUN ./gradlew :admin-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/admin-app.jar /app/app.jar
USER appuser
EXPOSE 8081
# 이 기본값은 deploy/docker-compose.yml 의 JAVA_OPTS 기본값과 두 곳에 중복
# 정의돼 있다 — 바꿀 때 반드시 함께. compose 가 항상 JAVA_OPTS 를 주입하므로
# compose 경로에서는 아래 ENV 값이 쓰이지 않는다(docker run 직접 실행용 기본값).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
