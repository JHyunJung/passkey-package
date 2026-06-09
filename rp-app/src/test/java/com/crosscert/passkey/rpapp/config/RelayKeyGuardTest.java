package com.crosscert.passkey.rpapp.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RelayKeyGuard 단위 테스트(P2-a). 운영 프로필(또는 프로필 미지정)에서 relay 데모 기본 키
 * 사용을 차단하고, dev/local 이 명시적으로 active 이거나 강한 키를 주입했으면 통과하는지 검증한다.
 */
class RelayKeyGuardTest {

    private static RelayKeyGuard guard(String secret, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new RelayKeyGuard(new RelayProperties(secret, Duration.ofMinutes(5)), env);
    }

    @Test
    void nonDevProfileWithDemoKey_failsFast() {
        RelayKeyGuard g = guard(RelayKeyGuard.DEMO_SECRET, "prod");
        assertThatThrownBy(g::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RP_RELAY_SECRET");
    }

    @Test
    void devProfileWithDemoKey_passes() {
        RelayKeyGuard g = guard(RelayKeyGuard.DEMO_SECRET, "dev");
        assertThatCode(g::check).doesNotThrowAnyException();
    }

    @Test
    void localProfileWithDemoKey_passes() {
        RelayKeyGuard g = guard(RelayKeyGuard.DEMO_SECRET, "local");
        assertThatCode(g::check).doesNotThrowAnyException();
    }

    @Test
    void noActiveProfileWithDemoKey_failsFast() {
        // 빈 secret → RelayProperties 폴백이 DEMO_SECRET. 활성 프로필 없음(env-only 운영)은
        // 운영으로 간주 → 데모 키 거부(의미 변경: 빈 프로필을 더 이상 dev 로 취급하지 않음).
        RelayKeyGuard g = guard(null);
        assertThatThrownBy(g::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RP_RELAY_SECRET");
    }

    @Test
    void noActiveProfileWithStrongKey_passes() {
        // 프로필 미지정이어도 강한 키를 주입했으면 통과(운영 정상 경로).
        RelayKeyGuard g = guard("a-strong-injected-production-secret");
        assertThatCode(g::check).doesNotThrowAnyException();
    }

    @Test
    void nonDevProfileWithStrongKey_passes() {
        RelayKeyGuard g = guard("a-strong-injected-production-secret", "prod");
        assertThatCode(g::check).doesNotThrowAnyException();
    }
}
