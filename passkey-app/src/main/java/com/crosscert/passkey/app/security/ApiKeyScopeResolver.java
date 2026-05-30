package com.crosscert.passkey.app.security;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RP-facing 요청 경로를 요구 API key scope 로 매핑 (P1-5).
 *
 * <p>scope 값은 api_key_scope 테이블의 enum(registration/authentication/admin)과
 * 일치. 이 resolver 는 현재 registration/authentication 만 매핑(admin 은 RP-facing
 * 아님). {@code /api/v1/rp/} 밖의 경로는 scope 불요(Optional.empty) — 인증만 통과하면 된다.
 * {@code /api/v1/rp/} 하위인데 매핑 안 된 경로는 fail-closed: 어떤 키도 보유 불가능한
 * sentinel scope 를 요구해 필터가 403(새 RP 엔드포인트가 scope 매핑 없이 추가돼도 open 되지 않음).
 * 매핑을 단일 컴포넌트에 응집해 ApiKeyAuthFilter 가 분기 로직을 갖지 않게 한다.
 */
@Component
public class ApiKeyScopeResolver {

    public Optional<String> requiredScope(String path) {
        if (path == null) return Optional.empty();
        if (underBase(path, "/api/v1/rp/registration"))    return Optional.of("registration");
        if (underBase(path, "/api/v1/rp/authentication"))  return Optional.of("authentication");
        // self-service credential 관리(목록/이름변경/삭제)는 등록 계열 — registration scope.
        // 주의: DELETE(기존 패스키 삭제)도 이 scope로 허용된다 — 파괴적 작업의 scope 분리는
        // 향후 정책 논의 대상(followups 기록). 현재는 등록 계열로 통일.
        if (underBase(path, "/api/v1/rp/credentials"))      return Optional.of("registration");
        // fail-closed: /api/v1/rp/ 하위인데 매핑 안 된 경로는 어떤 키도 보유 불가능한
        // sentinel scope 를 요구해 403. 새 RP 엔드포인트가 scope 매핑 없이 추가돼도
        // 실수로 open 되지 않는다(매핑을 명시적으로 추가해야 동작).
        if (underBase(path, "/api/v1/rp")) return Optional.of("__unmapped_rp__");
        return Optional.empty();
    }

    /** prefix 가 경로 세그먼트 경계에서 매칭될 때만 true (registration 이 registrationXYZ 를 잡지 않게). */
    private static boolean underBase(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }
}
