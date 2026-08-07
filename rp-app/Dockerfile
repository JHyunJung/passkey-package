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
RUN ./gradlew :rp-app:dependencies --no-daemon --console=plain || true

COPY . .
RUN ./gradlew :rp-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/rp-app.jar /app/app.jar
USER appuser
EXPOSE 9090
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
