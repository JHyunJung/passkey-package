plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":webauthn"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    // admin 화면(Thymeleaf + htmx + Alpine.js) — 서버 렌더링.
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
    // MFA 등록 화면의 otpauth QR 을 서버에서 SVG 로 렌더링(외부 QR 서비스 미사용).
    implementation("com.google.zxing:core:3.5.3")
    // Oracle NLS(orai18n) — 문자셋/날짜·언어 변환 시 드라이버가 런타임에 로드.
    runtimeOnly(rootProject.libs.oracle.nls)
    // F4 Task 6 — Monthly Audit Chain Report PDF generation.
    // openhtmltopdf 1.0.10 is the current stable LTS release; pulls in
    // pdfbox 2.0.x transitively.
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    // Boot 4 모듈화 테스트 스타터: security-test 는 spring-security-test + Boot 의 MockMvc 보안
    // 연동(spring-boot-security-test), webmvc-test 는 @WebMvcTest·@AutoConfigureMockMvc
    // (spring-boot-webmvc-test) + TestRestTemplate(spring-boot-resttestclient).
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // TestRestTemplate 자동설정(@AutoConfigureTestRestTemplate)이 RestTemplateBuilder 를 요구 —
    // Boot 4 는 RestTemplate 지원이 별도 모듈(spring-boot-restclient)이다.
    testImplementation("org.springframework.boot:spring-boot-restclient")
    // 화면 HTML 응답 파싱용.
    testImplementation("org.jsoup:jsoup:1.18.1")
    // T24 AdminFlowIT (Phase 2 acceptance gate) brings up a Testcontainers
    // Oracle XE 21 + Redis 7 and exercises admin flows end-to-end. Same
    // dependency shape as :passkey-app for Phase 1's Fido2EndToEndIT.
    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
    // T23 MdsSchedulerIT stubs the FIDO MDS3 endpoint via WireMock so the
    // IT runs offline and exercises the real FidoMDS3MetadataBLOBProvider
    // chain against a controlled BLOB + test root CA.
    testImplementation(rootProject.libs.wiremock.standalone)
    // QW-1 (sec-admin-vpd-exempt-sole-layer): ArchUnit enforces that
    // tenant-scoped admin services reference TenantBoundary, so a future
    // refactor that drops the isolation check fails the build (VPD removed;
    // admin-app does cross-tenant queries — TenantBoundary is the sole layer).
    testImplementation(rootProject.libs.archunit.junit5)
}

springBoot {
    mainClass.set("com.crosscert.passkey.admin.AdminApplication")
    // Generates META-INF/build-info.properties so BuildProperties bean
    // is available at runtime (consumed by SystemInfoService).
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("admin-app.jar")
    // 실행 가능한 jar 를 루트 deploy/ 에 모아 배포 편의를 높인다.
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    // Same Docker-API-version pin as :core / :passkey-app (Phase 0
    // followup notes). Required so Testcontainers' shaded docker-java
    // does not fall back to v1.32 and get HTTP 400 from Docker Engine
    // v25+.
    systemProperty("api.version", "1.43")
    // 계약 픽스처 기록 모드: ./gradlew :admin-app:test -Pcontract.record=true
    // 기본값 false = 비교만 하고 없거나 다르면 실패(AuditChainJacksonCompatTest).
    systemProperty("contract.record", providers.gradleProperty("contract.record").getOrElse("false"))
}

// Copy scripts/bootstrap-schema.sql onto the test classpath so
// AdminFlowIT can ship it into the Testcontainers Oracle via
// MountableFile (same pattern as core / passkey-app — scripts/ is
// the single source of truth used by docker-compose).
tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-schema.sql"))
}
