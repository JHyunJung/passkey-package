plugins {
    `java-library`
    `maven-publish`
}

// group 은 root allprojects 가 com.crosscert.passkey 로 설정.
// toolchain 17 + repositories(mavenCentral) 은 root subprojects 가 처리.
//
// version 은 외부 RP 에 배포되는 라이브러리라 root 의 0.0.1-SNAPSHOT(내부 모듈 공통)
// 을 덮어 SDK 단독 versioning 한다 — 첫 stable release.
version = "1.2.0"

java {
    withSourcesJar()
}

// Boot 3.5 BOM 이 관리하는 Jackson 2(2.21.4)에 알려진 취약점(jackson-core·databind HIGH 포함, 2.21.7 에서 수정)이
// 있어 수정된 패치로 고정한다(카탈로그 jackson2-sdk). BOM 프로퍼티를 덮어쓰지 않고 항목을 명시하는 이유는
// 이 선언이 배포 POM 의 dependencyManagement 에 그대로 실려, BOM 없이 SDK 를 받는 RP 도 같은 버전을 받게
// 하기 위해서다(프로퍼티 덮어쓰기는 POM 에 남지 않는다). jackson-annotations 는 버전 체계가 달라(2.21) BOM 값 유지.
// Boot 3.5 BOM 이 2.21.7 이상을 관리하게 되면 이 블록과 카탈로그 항목을 지운다.
dependencyManagement {
    dependencies {
        val jackson2 = rootProject.libs.versions.jackson2.sdk.get()
        dependency("com.fasterxml.jackson.core:jackson-core:$jackson2")
        dependency("com.fasterxml.jackson.core:jackson-databind:$jackson2")
        dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jackson2")
    }
}

dependencies {
    // Spring Boot 3.5 BOM (root subprojects 가 sdk-java 에만 카탈로그 spring-boot-sdk 버전으로
    // import — 다른 모듈은 Boot 4) 이 spring-web 6.2.x,
    // jackson-databind, jackson-jsr310, slf4j-api 를 모두 관리.
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(rootProject.libs.nimbus.jose.jwt)
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(rootProject.libs.wiremock.standalone)
    // junit-platform-launcher 는 root subprojects 가 모든 모듈에 testRuntimeOnly 적용
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
