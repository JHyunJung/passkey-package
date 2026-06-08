package com.crosscert.passkey.webauthn.attestation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** fmt 문자열 → AttestationVerifier 레지스트리. */
public final class AttestationVerifiers {

    private final Map<String, AttestationVerifier> byFormat;

    private AttestationVerifiers(List<AttestationVerifier> verifiers) {
        Map<String, AttestationVerifier> m = new LinkedHashMap<>();
        for (AttestationVerifier v : verifiers) m.put(v.format(), v);
        // 생성 후 불변 — 동시 읽기 안전 보장 (codex 관찰). 조회만 하므로 순서 무관.
        this.byFormat = Map.copyOf(m);
    }

    /** 현재 구현된 포맷 전부 등록. 포맷 확장 시 이 목록에 추가. */
    public static AttestationVerifiers defaults() {
        return new AttestationVerifiers(List.of(
                new NoneAttestationVerifier(),
                new PackedAttestationVerifier(),
                new FidoU2fAttestationVerifier(),
                new AppleAttestationVerifier(),
                new AndroidKeyAttestationVerifier()));
    }

    public static AttestationVerifiers of(List<AttestationVerifier> verifiers) {
        return new AttestationVerifiers(verifiers);
    }

    /** 미지원 포맷이면 null. */
    public AttestationVerifier forFormat(String format) {
        return byFormat.get(format);
    }
}
