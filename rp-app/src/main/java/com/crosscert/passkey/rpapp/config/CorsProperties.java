package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * cross-origin 웹 SPA 를 위한 정확한 origin 화이트리스트.
 * ⚠️ reflected-origin(요청 Origin 반사)·와일드카드 금지(spec §3). 정확한 origin 목록만.
 * 이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것(드리프트 방지).
 * 설정: rp.cors.allowed-origins (콤마 구분 또는 YAML 리스트). 비면 CORS 비활성.
 */
@ConfigurationProperties(prefix = "rp.cors")
public record CorsProperties(List<String> allowedOrigins) {
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        // "no wildcard" 규칙을 문서가 아니라 부팅 시 강제한다(spec §3).
        // 와일드카드(*)·패턴(*.example.com)·빈 값을 거부 → 잘못된 설정으로 cross-origin 이
        // 무차별 허용되는 일을 차단. 비면(목록 없음) CORS 자체가 비활성이므로 정상.
        for (String origin : allowedOrigins) {
            if (origin == null || origin.isBlank()) {
                throw new IllegalArgumentException("rp.cors.allowed-origins 에 빈 값 금지");
            }
            if (origin.contains("*")) {
                throw new IllegalArgumentException(
                        "rp.cors.allowed-origins 에 와일드카드 금지(정확한 origin 만): " + origin);
            }
        }
    }
}
