package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * cross-origin 웹 클라이언트를 위한 허용 origin 화이트리스트({@code rp.cors.allowed-origins}).
 * ⚠️ 정확한 origin 목록만 허용한다 — 와일드카드·요청 Origin 반사는 금지. 비우면 CORS 가 비활성(같은-origin 만).
 * 고객사는 자사 웹 origin 을 passkey-app 테넌트의 allowed-origins 와 동일하게 맞춘다.
 */
@ConfigurationProperties(prefix = "rp.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
    public CorsProperties {
        // 와일드카드(*)·패턴(*.example.com)·빈 값을 거부한다 — 잘못된 설정으로 cross-origin 이
        // 무차별 허용되는 것을 부팅 시점에 차단한다. 비면(목록 없음) CORS 자체가 비활성이라 정상.
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin.isBlank()) {
                    throw new IllegalArgumentException("rp.cors.allowed-origins 에 빈 값 금지");
                }
                if (origin.contains("*")) {
                    throw new IllegalArgumentException(
                            "rp.cors.allowed-origins 에 와일드카드 금지(정확한 origin 만): " + origin);
                }
            }
        }
    }
}
