package com.crosscert.passkey.core.policy;

import java.util.UUID;

/**
 * 등록 ceremony 에서 AAGUID 정책을 검사하는 포트(인터페이스).
 *
 * <p>구현체: {@link DefaultAaguidPolicyChecker}.
 * 정책 위반 시 {@link AaguidPolicyViolationException} 을 던진다.
 */
public interface AaguidPolicyChecker {

    /**
     * 주어진 tenant 의 AAGUID 정책에 따라 {@code aaguid} 를 허용/차단한다.
     *
     * @param tenantId  정책 조회 대상 tenant UUID
     * @param aaguid    등록 ceremony 에서 추출한 AAGUID (null 허용 — null 이면 pass-through)
     * @throws AaguidPolicyViolationException 정책 위반 시
     */
    void check(UUID tenantId, UUID aaguid);
}
