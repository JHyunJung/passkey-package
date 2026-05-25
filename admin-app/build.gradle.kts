plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
}

springBoot {
    mainClass.set("com.crosscert.passkey.admin.AdminApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("admin-app.jar")
}
