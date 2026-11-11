package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 운영(또는 프로필 미지정) 환경에서 {@code passkey.issuer-base} 가 비었거나 localhost 폴백이면 기동을 실패시킨다.
 *
 * <p>application.yml 의 기본값({@code http://localhost:8080})은 로컬 데모용이다. 운영에서 PASSKEY_ISSUER_BASE 를
 * 빠뜨리면 조용히 이 값으로 떨어져 모든 ID Token 검증이 iss 불일치(P004)로 실패하므로, 부팅 시점에 바로 알린다.
 * dev/local 이 명시적으로 active 일 때만 localhost 를 허용한다({@link RelayKeyGuard} 와 같은 기준).
 */
@Component
public class IssuerBaseGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final PasskeyProperties props;
    private final Environment env;

    public IssuerBaseGuard(PasskeyProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        Set<String> active = Set.of(env.getActiveProfiles());
        if (active.contains("dev") || active.contains("local")) {
            return;
        }
        URI issuer = props.issuerBase();
        String scheme = issuer == null ? null : issuer.getScheme();
        String host = issuer == null ? null : issuer.getHost();
        boolean httpScheme = "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        if (!httpScheme || host == null || host.isBlank() || LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "passkey.issuer-base 가 비었거나 로컬 주소입니다(" + issuer + "). 운영(또는 프로필 미지정) 환경에서는 "
                            + "PASSKEY_ISSUER_BASE 를 passkey-app 의 passkey.id-token.issuer-base 와 같은 값으로 주입하세요.");
        }
    }
}
