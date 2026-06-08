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

    // 테스트 전용 — TestCa가 attestation 체인 검증용 self-signed CA/leaf를 발급. 프로덕션엔 BC 없음.
    // jdk15to18 1.84로 정렬: webauthn4j-test가 bcprov/bcutil-jdk15to18:1.84를 전이로 끌어오므로
    // bcpkix도 같은 artifact family(jdk15to18)·같은 버전으로 맞춘다. jdk18on을 섞으면 Gradle이
    // 별개 모듈로 보아 두 BC family가 classpath에 공존(클래스 충돌·NoSuchFieldError) (codex P2).
    testImplementation("org.bouncycastle:bcpkix-jdk15to18:1.84")
}
