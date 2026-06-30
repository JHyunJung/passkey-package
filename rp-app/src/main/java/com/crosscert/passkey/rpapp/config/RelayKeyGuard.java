package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 운영(또는 프로필 미지정) 환경에서 relay 데모 기본 키 사용을 막는 가드. 기동 시 검사해 위반이면 실패시킨다. */
@Component
public class RelayKeyGuard {

    /** 데모/개발용 기본 HMAC 키. RelayProperties 폴백과 공유(상수 한곳 정의). */
    public static final String DEMO_SECRET = "dev-rp-relay-secret-not-for-prod-change-me";

    private final RelayProperties props;
    private final Environment env;

    public RelayKeyGuard(RelayProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        Set<String> active = Set.of(env.getActiveProfiles());
        // dev/local 이 명시적으로 active 일 때만 데모 키 허용. 빈 프로필(env-only 운영)은
        // 운영으로 간주해 데모 키를 거부한다.
        boolean devOrLocal = active.contains("dev") || active.contains("local");
        if (!devOrLocal && DEMO_SECRET.equals(props.secret())) {
            throw new IllegalStateException(
                    "rp.relay.secret 이 데모 기본 키입니다. 운영(또는 프로필 미지정) 환경에서는 RP_RELAY_SECRET 로 강한 키를 주입하세요.");
        }
    }
}
