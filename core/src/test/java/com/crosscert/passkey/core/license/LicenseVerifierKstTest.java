package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KST 마이그레이션 회귀 가드 (CRITICAL #2).
 *
 * <p>LicenseVerifier 의 nbf/exp 비교는 {@code clock.instant()} (절대 epoch) 와
 * JWT {@code Date.toInstant()} (RFC 7519 NumericDate = epoch seconds) 사이의
 * 절대시각 비교다. Clock 빈이 Asia/Seoul 로 바뀌어도 {@code instant()} 가
 * 반환하는 절대시각은 그대로이므로 만료/유효성 판정은 불변이어야 한다.
 *
 * <p>이 테스트는 그 불변식을 고정한다 — License 만료 비교 로직은 KST 전환
 * 이후에도 절대 epoch 비교로 남아야 하며, OffsetDateTime 으로 변환해서는 안 된다.
 */
class LicenseVerifierKstTest {

    private final LicensePublicKeyProvider keys =
            new LicensePublicKeyProvider("license-public.test.ed25519.pub");
    private final LicenseProperties props = new LicenseProperties(
            null, null, "license.crosscert.com", "passkey-onprem", null, null);

    /**
     * 가장 작은 불변식: 동일 절대시각을 UTC/KST 두 Clock 으로 생성해도
     * instant() 는 동일하다 — Clock 의 ZONE 은 만료 판정에 영향이 없다.
     */
    @Test
    void clockInstantIsZoneIndependentForExpiry() {
        Instant fixed = Instant.parse("2026-06-20T09:00:00Z");
        Clock utc = Clock.fixed(fixed, ZoneId.of("UTC"));
        Clock kst = Clock.fixed(fixed, ZoneId.of("Asia/Seoul"));
        assertThat(utc.instant()).isEqualTo(kst.instant());
    }

    /**
     * 실제 LicenseVerifier 를 동일 절대시각의 UTC Clock 과 KST Clock 으로 각각
     * 구동했을 때 exp/nbf 판정이 동일함을 증명한다. 만료 직전(유효)·직후(만료)
     * 양쪽 모두에서 두 zone 의 verdict 가 일치해야 한다.
     */
    @Test
    void verdictIdenticalAcrossUtcAndKstClocksAtSameInstant() throws Exception {
        // 토큰은 발급 시점 now() 기준 nbf=now, exp=now+1h. 검증 clock 은
        // 그 발급 기준에 상대적으로 고정해야 의미 있는 verdict 가 나온다.
        Instant issuedAt = Instant.now();
        String token = LicenseTestFixtures.issueValid(Duration.ofHours(1), List.of("mds"));

        // exp 직전(유효): 두 zone 동일 절대시각 → 둘 다 통과.
        Instant justBeforeExp = issuedAt.plus(Duration.ofMinutes(30));
        LicenseToken utcResult = verifierAt(justBeforeExp, "UTC").verify(token);
        LicenseToken kstResult = verifierAt(justBeforeExp, "Asia/Seoul").verify(token);
        // 동일 토큰·동일 절대시각이므로 추출 결과(특히 expiresAt)도 동일.
        assertThat(utcResult.expiresAt()).isEqualTo(kstResult.expiresAt());
        assertThat(utcResult.jti()).isEqualTo(kstResult.jti());

        // exp 이후(만료): 두 zone 모두 동일하게 거부.
        Instant afterExp = issuedAt.plus(Duration.ofHours(2));
        LicenseVerifier utcAfter = verifierAt(afterExp, "UTC");
        LicenseVerifier kstAfter = verifierAt(afterExp, "Asia/Seoul");
        assertThatThrownBy(() -> utcAfter.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("expired");
        assertThatThrownBy(() -> kstAfter.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("expired");
    }

    private LicenseVerifier verifierAt(Instant instant, String zone) {
        return new LicenseVerifier(keys, props, Clock.fixed(instant, ZoneId.of(zone)));
    }
}
