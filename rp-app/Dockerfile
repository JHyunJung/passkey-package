# rp-app — 고객사 연동 샘플 앱. QA 환경에서만 기동한다(prod 스택 제외).
# syntax 지시자를 쓰지 않는 이유는 passkey-app/Dockerfile 주석 참고.
# 고객사는 이 앱을 참고해 자사 서비스에 맞게 변형하므로 배포용 이미지가 아니다.
#   docker build -t rp-app:0.0.1-SNAPSHOT -f rp-app/Dockerfile .

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
# '|| true' 는 의도된 것이다: 이 시점엔 소스가 아직 복사되지 않아 Gradle 이
# 프로젝트를 온전히 평가하지 못해 실패할 수 있다. 목적은 의존성 캐시 워밍뿐이고,
# 받아둔 의존성은 캐시에 남는다. 진짜 검증은 아래 bootJar 이며 거기엔 || true 가
# 없으므로, 의존성 해석이 실제로 깨졌다면 그 단계에서 반드시 실패한다.
RUN ./gradlew :rp-app:dependencies --no-daemon --console=plain || true

COPY . .
RUN ./gradlew :rp-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/rp-app.jar /app/app.jar
USER appuser
EXPOSE 9090
# 이 기본값은 deploy/docker-compose.yml 의 JAVA_OPTS 기본값과 두 곳에 중복
# 정의돼 있다 — 바꿀 때 반드시 함께. compose 가 항상 JAVA_OPTS 를 주입하므로
# compose 경로에서는 아래 ENV 값이 쓰이지 않는다(docker run 직접 실행용 기본값).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
