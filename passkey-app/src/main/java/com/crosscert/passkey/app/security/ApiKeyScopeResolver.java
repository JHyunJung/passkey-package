package com.crosscert.passkey.app.security;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RP-facing 요청 경로를 요구 API key scope 로 매핑 (P1-5).
 *
 * <p>scope 값은 api_key_scope 테이블의 enum(registration/authentication/admin)과
 * 일치. 매핑 없는 경로는 scope 불요(Optional.empty) — 인증만 통과하면 된다.
 * 매핑을 단일 컴포넌트에 응집해 ApiKeyAuthFilter 가 분기 로직을 갖지 않게 한다.
 */
@Component
public class ApiKeyScopeResolver {

    public Optional<String> requiredScope(String path) {
        if (path == null) return Optional.empty();
        if (path.startsWith("/api/v1/rp/registration"))    return Optional.of("registration");
        if (path.startsWith("/api/v1/rp/authentication"))  return Optional.of("authentication");
        // self-service credential 관리(목록/이름변경/삭제)는 등록 계열 — registration scope.
        if (path.startsWith("/api/v1/rp/credentials"))      return Optional.of("registration");
        return Optional.empty();
    }
}
