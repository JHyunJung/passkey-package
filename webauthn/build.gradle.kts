plugins {
    `java-library`
}

dependencies {
    // 프로덕션: JSON 파싱(clientDataJSON)에만 Jackson 사용. Spring·webauthn4j 의존 없음.
    // implementation — 공개 API(WebAuthnVerifier 등)는 Jackson 타입을 노출하지 않으므로
    // consumer 컴파일 classpath에 Jackson을 새지 않게 한다 (codex P2).
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // differential 테스트 전용 — webauthn4j는 절대 프로덕션 classpath에 들어가지 않는다.
    testImplementation(rootProject.libs.webauthn4j.core)
    testImplementation(rootProject.libs.webauthn4j.test)
}
