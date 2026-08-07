# passkey-app — WebAuthn 등록/인증 서버. 무상태이므로 N개로 스케일 가능.
# syntax 지시자는 쓰지 않는다 — BuildKit 전용 문법(RUN --mount 등)을 쓰지 않으므로
# docker/dockerfile 프론트엔드 이미지를 받아올 이유가 없고, 이 환경에서는 그 pull 이
# DeadlineExceeded 로 실패해 Dockerfile 파싱 전에 빌드를 막았다.
# 빌드 컨텍스트는 리포지토리 루트다(멀티모듈: core/webauthn 소스가 필요).
#   docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 의존성 레이어 캐싱: 빌드 스크립트만 먼저 복사해 의존성을 받아두면
# 소스만 바뀐 재빌드에서 이 레이어가 재사용된다.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/
COPY webauthn/build.gradle.kts webauthn/
COPY passkey-app/build.gradle.kts passkey-app/
COPY admin-app/build.gradle.kts admin-app/
COPY rp-app/build.gradle.kts rp-app/
COPY sdk-java/build.gradle.kts sdk-java/
# '|| true' 는 의도된 것이다: 이 시점엔 소스가 아직 복사되지 않아 Gradle 이
# 프로젝트를 온전히 평가하지 못해 실패할 수 있다. 목적은 의존성 캐시 워밍뿐이고,
# 받아둔 의존성은 캐시에 남는다. 진짜 검증은 아래 bootJar 이며 거기엔 || true 가
# 없으므로, 의존성 해석이 실제로 깨졌다면 그 단계에서 반드시 실패한다.
RUN ./gradlew :passkey-app:dependencies --no-daemon --console=plain || true

# 소스 복사 후 실제 빌드. bootJar 는 deploy/passkey-app.jar 로 떨어진다
# (passkey-app/build.gradle.kts 의 archiveFileName/destinationDirectory).
COPY . .
RUN ./gradlew :passkey-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
# 비루트 실행 — 컨테이너 탈출 시 피해 축소
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/passkey-app.jar /app/app.jar
USER appuser
EXPOSE 8080
# JAVA_OPTS 로 힙 상한을 컨테이너 메모리에 맞춘다(compose 의 mem_limit 인식).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
