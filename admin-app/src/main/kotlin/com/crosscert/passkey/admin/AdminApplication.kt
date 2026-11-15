package com.crosscert.passkey.admin

import com.crosscert.passkey.core.config.KstTime
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

// scanBasePackages picks up beans from :core. @EntityScan and
// @EnableJpaRepositories are required separately because Spring Data JPA
// auto-configuration defaults to the @SpringBootApplication's own
// package — without explicit pointers, sibling-package entities and
// repositories in :core would not be discovered.
// @ConfigurationPropertiesScan registers @ConfigurationProperties records
// across all sub-packages of com.crosscert.passkey (including :core).
//
// 요청 흐름(서블릿 필터는 @Order 값이 작은 것부터):
//   core TraceIdFilter·core OnpremTenantPinFilter(HIGHEST, 뒤는 onprem 모드만) → core LicenseGuardFilter(+1, onprem 모드만)
//   → core RequestLoggingFilter(+10, 항상) → Spring Session(+50, Redis 에서 세션을 읽는다)
//   → Spring Security 체인(-100, AdminSecurityConfig: CSRF·폼 로그인·인가 → AdminMdcFilter → MfaPendingFilter → TenantContextAdminFilter)
//   → 컨트롤러: `/admin/api/` 아래는 JSON REST, 그 밖의 `/admin` 화면은 admin.web 의 Thymeleaf 컨트롤러.
//   (local/dev/test 프로필에서는 core DevTenantHeaderFilter(+10)도 낀다.)
@SpringBootApplication(scanBasePackages = ["com.crosscert.passkey"])
@ConfigurationPropertiesScan("com.crosscert.passkey")
@EntityScan("com.crosscert.passkey.core.entity")
@EnableJpaRepositories("com.crosscert.passkey.core.repository")
@EnableScheduling
@EnableAspectJAutoProxy
class AdminApplication

fun main(args: Array<String>) {
    // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
    // raw-JDBC bridge 경로(mds_sync_history 등)는 JVM default TZ 로 wall-clock
    // 을 저장하므로, 이 한 줄이 그 경로까지 KST 로 정렬시킨다. runApplication
    // 이전에 호출해야 모든 빈/스케줄러가 KST 로 초기화된다.
    TimeZone.setDefault(TimeZone.getTimeZone(KstTime.ZONE))
    runApplication<AdminApplication>(*args) {
        // 기본 추론은 main 이 있는 AdminApplicationKt 를 잡아 시작 로그 카테고리·문구
        // ("Started AdminApplicationKt")가 바뀐다 — AdminApplication 으로 고정한다
        // (scripts/init-dev-db.sh·init-db-external.sh·docs/dev-setup.md 가 "Started AdminApplication" 에 의존).
        setMainApplicationClass(AdminApplication::class.java)
    }
}
