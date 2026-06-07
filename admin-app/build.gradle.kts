plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.node.gradle)
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    // F4 Task 6 — Monthly Audit Chain Report PDF generation.
    // openhtmltopdf 1.0.10 is the current stable LTS release; pulls in
    // pdfbox 2.0.x transitively.
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    testImplementation("org.springframework.security:spring-security-test")
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
    // refactor that drops the isolation check fails the build (admin-app
    // runs VPD-EXEMPT — TenantBoundary is the sole isolation layer).
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

// admin-ui (React+Vite) lives at the repo root. We tell the node plugin
// to operate there and to download a project-local Node 18.
node {
    version.set("18.20.0")
    download.set(true)
    nodeProjectDir.set(rootProject.file("admin-ui"))
}

// `npm install` + `npm run build` produce admin-ui/dist/. Then copy
// that into the admin-app classpath so Spring Boot's static serving
// picks it up at /admin/<asset>.
val buildUi by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn("npmInstall")
    workingDir.set(rootProject.file("admin-ui"))
    npmCommand.set(listOf("run", "build"))
    inputs.dir(rootProject.file("admin-ui/src"))
    inputs.file(rootProject.file("admin-ui/index.html"))
    inputs.file(rootProject.file("admin-ui/package.json"))
    inputs.file(rootProject.file("admin-ui/package-lock.json"))
    inputs.file(rootProject.file("admin-ui/vite.config.ts"))
    outputs.dir(rootProject.file("admin-ui/dist"))
}

tasks.named<Copy>("processResources") {
    dependsOn(buildUi)
    from(rootProject.file("admin-ui/dist")) {
        into("static/admin")
    }
}

tasks.named<Test>("test") {
    // Same Docker-API-version pin as :core / :passkey-app (Phase 0
    // followup notes). Required so Testcontainers' shaded docker-java
    // does not fall back to v1.32 and get HTTP 400 from Docker Engine
    // v25+.
    systemProperty("api.version", "1.43")
}

// Copy scripts/bootstrap-vpd.sql onto the test classpath so
// AdminFlowIT can ship it into the Testcontainers Oracle via
// MountableFile (same pattern as core / passkey-app — scripts/ is
// the single source of truth used by docker-compose).
tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-vpd.sql"))
}
