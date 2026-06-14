package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * cross-origin 웹 SPA 를 위한 정확한 origin 화이트리스트.
 * ⚠️ reflected-origin(요청 Origin 반사)·와일드카드 금지(spec §3). 정확한 origin 목록만.
 * 이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것(드리프트 방지).
 * 설정: rp.cors.allowed-origins (콤마 구분 또는 YAML 리스트). 비면 CORS 비활성.
 *
 * 원본 Java 는 record 였다. Spring Boot 는 @Bean factory-method 로 제공된
 * @ConfigurationProperties 를 무조건 JAVA_BEAN 으로 바인딩하는데(ConfigurationPropertiesBean.get),
 * record 접근자는 `allowedOrigins()` 라 JavaBeanBinder(getXxx 만 인식)가 프로퍼티로 보지 않아
 * 외부 yml 이 fixture @Bean 값을 덮어쓰지 못한다. 일반 Kotlin data class 는 getXxx 를 만들어
 * val 이면 "No setter", var 면 yml override 가 된다. @JvmRecord 로 원본 record 동작을 재현한다.
 * (WebAuthnControllerTest 가 CorsProperties 를 fixture @Bean 으로 주입한다.)
 */
@JvmRecord
@ConfigurationProperties(prefix = "rp.cors")
data class CorsProperties(
    val allowedOrigins: List<String>?,
) {
    init {
        // "no wildcard" 규칙을 문서가 아니라 부팅 시 강제한다(spec §3).
        // 와일드카드(*)·패턴(*.example.com)·빈 값을 거부 → 잘못된 설정으로 cross-origin 이
        // 무차별 허용되는 일을 차단. 비면(목록 없음) CORS 자체가 비활성이므로 정상.
        allowedOrigins?.forEach { origin ->
            require(!origin.isBlank()) { "rp.cors.allowed-origins 에 빈 값 금지" }
            require(!origin.contains("*")) {
                "rp.cors.allowed-origins 에 와일드카드 금지(정확한 origin 만): $origin"
            }
        }
    }
}
