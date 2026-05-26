plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.node.gradle)
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
}

springBoot {
    mainClass.set("com.crosscert.passkey.admin.AdminApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("admin-app.jar")
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
