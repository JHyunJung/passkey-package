plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":webauthn"))

    // Oracle NLS(orai18n) — 문자셋/날짜·언어 변환 시 드라이버가 런타임에 로드.
    runtimeOnly(rootProject.libs.oracle.nls)

    // T26 Fido2EndToEndIT brings up a Testcontainers Oracle + Redis and
    // exercises the FIDO2 RP API end-to-end. We need :core's test deps
    // (OracleContainer, junit-jupiter container support) plus the
    // webauthn4j-test ClientPlatform/PackedAuthenticator emulator to
    // build attestations/assertions the server will accept, plus the
    // Nimbus JWT parser for ID-Token JWKS verification.
    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
    // Fido2TestAuthenticator는 webauthn4j-core(com.webauthn4j.data.*)와 webauthn4j-test
    // 에뮬레이터를 직접 쓴다. core가 더 이상 webauthn4j-core를 api로 전이 노출하지 않으므로
    // (프로덕션 런타임 제거) 테스트에 명시 선언한다. 테스트 authenticator 자체구현은 후속 작업.
    testImplementation(rootProject.libs.webauthn4j.core)
    testImplementation(rootProject.libs.webauthn4j.test)
    testImplementation(rootProject.libs.nimbus.jose.jwt)
    // SdkClientEndToEndIT: 실제 sdk-java PasskeyClient 를 랜덤 포트의 passkey-app 에 붙여
    // 등록→인증→verifyIdToken 전 구간을 검증한다(Boot 4 업그레이드 계약 안전망).
    testImplementation(project(":sdk-java"))
}

springBoot {
    mainClass.set("com.crosscert.passkey.app.PasskeyApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("passkey-app.jar")
    // 실행 가능한 jar 를 루트 deploy/ 에 모아 배포 편의를 높인다.
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    // Same Docker-API-version pin as :core (see core/build.gradle.kts
    // comment). Required so Testcontainers' shaded docker-java does not
    // fall back to v1.32 and get HTTP 400 from Docker Engine v25+.
    systemProperty("api.version", "1.43")
    // 계약 스냅샷/픽스처 기록 모드: ./gradlew :passkey-app:test -Pcontract.record=true
    // 기본값 false = 비교만 하고 없거나 다르면 실패(ApiContractIT, ChallengeStoreFormatCompatTest).
    systemProperty("contract.record", providers.gradleProperty("contract.record").getOrElse("false"))
}

// Copy scripts/bootstrap-schema.sql onto the test classpath so
// Fido2EndToEndIT can ship it into the Testcontainers Oracle via
// MountableFile (same pattern as core/build.gradle.kts — scripts/ is
// the single source of truth used by docker-compose).
tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-schema.sql"))
}
