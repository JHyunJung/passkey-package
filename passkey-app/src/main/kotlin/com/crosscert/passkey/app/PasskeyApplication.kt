package com.crosscert.passkey.app

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
//   SecurityHeadersFilter·core TraceIdFilter·core OnpremTenantPinFilter(HIGHEST, 마지막은 onprem 모드만)
//   → core LicenseGuardFilter(+1, onprem 모드만) → RateLimitFilter(+2)
//   → ApiKeyAuthFilter(+5, X-API-Key 검증 후 TenantContextHolder 설정)
//   → core RequestLoggingFilter(+10, 항상) → api.v1.rp 컨트롤러 → fido2 서비스.
//   (local/dev/test 프로필에서는 core DevTenantHeaderFilter(+10)도 끼지만 /api/v1/rp/** 는 건너뛴다.)
// 등록·인증은 같은 2단계다: start 가 challenge 를 ChallengeStore(Redis)에 넣고 토큰을 돌려주면,
// finish 가 그 토큰으로 challenge 를 한 번만 꺼내 검증한다. 인증 finish 가 발급한 ID Token 은
// RP 가 JwksController(/.well-known/jwks.json, API 키 불필요)의 공개키로 검증한다.
@SpringBootApplication(scanBasePackages = ["com.crosscert.passkey"])
@ConfigurationPropertiesScan("com.crosscert.passkey")
@EntityScan("com.crosscert.passkey.core.entity")
@EnableJpaRepositories("com.crosscert.passkey.core.repository")
@EnableScheduling
@EnableAspectJAutoProxy
class PasskeyApplication

fun main(args: Array<String>) {
    // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
    // runApplication 이전에 호출해야 모든 빈/스케줄러가 KST 로 초기화된다.
    TimeZone.setDefault(TimeZone.getTimeZone(KstTime.ZONE))
    runApplication<PasskeyApplication>(*args) {
        // main 이 최상위 함수라 Boot 의 기본 추론은 PasskeyApplicationKt 를 잡아 시작 로그 카테고리·문구가
        // "Started PasskeyApplicationKt" 가 된다 — 로그가 "Started PasskeyApplication" 으로 남도록 고정한다.
        setMainApplicationClass(PasskeyApplication::class.java)
    }
}
