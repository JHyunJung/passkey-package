package com.crosscert.passkey.core.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Periodically calls the license server to refresh the cached token.
 * Active only when passkey.deployment.mode=onprem.
 *
 * Expected response: { "status": "active|revoked", "latestToken": "<JWS>" }.
 * - status=active + valid latestToken -> StateMachine.onHeartbeatSuccess
 * - non-2xx, transport error, parse failure, signature failure
 *   -> StateMachine.onHeartbeatFailure(reason)
 *
 * <p>HTTP 호출에는 connect {@link #CONNECT_TIMEOUT}·read {@link #READ_TIMEOUT} 타임아웃이 걸린다.
 * 응답하지 않는 라이선스 서버가 스케줄러 스레드를 영구 점유하지 않도록 하기 위함이며, 타임아웃은
 * {@code ResourceAccessException}(= {@link RestClientException})으로 올라와 연결 실패와 같은
 * "http: ..." 네트워크 오류 경로(NETWORK_GRACE)로 처리된다. 기본 클라이언트(JDK HttpClient)는
 * 그대로이고 타임아웃만 추가했다.
 *
 * <p>간격의 단일 출처는 {@link LicenseProperties#heartbeatInterval()} — 아래
 * {@code @Scheduled} 의 {@code :PT1H} 폴백은 프로퍼티가 바인딩되지 않았을 때만 쓰이며,
 * 레코드 기본값과 항상 같은 값이어야 한다. 이는
 * {@code LicenseHeartbeatSchedulerIntervalDefaultTest} 가 리플렉션으로 고정한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseHeartbeatScheduler {

    private final LicenseStateMachine stateMachine;
    private final LicenseVerifier verifier;
    private final LicenseCache cache;
    private final LicenseProperties props;
    private final Clock clock;
    private final RestClient http;

    /** 정상 서버는 ms 단위로 연결되므로 넉넉하게 잡는다(OS 기본 SYN 재시도 한도보다 짧게). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /**
     * 정상 응답 시간보다 훨씬 길고, 기본 heartbeat 간격(PT1H)보다는 훨씬 짧게 잡는다.
     * Spring 6.2 JDK 팩토리에서는 요청 전송 시점부터 재므로 사실상 교환 전체(connect 포함)의 상한이다.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    public LicenseHeartbeatScheduler(LicenseStateMachine stateMachine,
                                     LicenseVerifier verifier,
                                     LicenseCache cache,
                                     LicenseProperties props,
                                     Clock clock) {
        this(stateMachine, verifier, cache, props, clock, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /**
     * 테스트용 — 짧은 타임아웃을 주입한다. Spring DI 는 위 {@code @Autowired} 생성자를 쓴다.
     */
    LicenseHeartbeatScheduler(LicenseStateMachine stateMachine,
                              LicenseVerifier verifier,
                              LicenseCache cache,
                              LicenseProperties props,
                              Clock clock,
                              Duration connectTimeout,
                              Duration readTimeout) {
        this.stateMachine = stateMachine;
        this.verifier = verifier;
        this.cache = cache;
        this.props = props;
        this.clock = clock;
        this.http = RestClient.builder()
                .baseUrl(props.heartbeatUrl())
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
    }

    /**
     * RestClient.builder() 가 지금 클래스패스에서 고르는 기본 팩토리와 같은 JDK HttpClient 기반
     * 팩토리다({@code HttpClient.newHttpClient()} == {@code newBuilder().build()}). connect·read
     * 타임아웃만 더한다.
     */
    private static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout,
                                                              Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Scheduled(
            fixedDelayString = "${passkey.license.heartbeat-interval:PT1H}",
            initialDelayString = "PT10S"
    )
    public void heartbeat() {
        String jti = stateMachine.token().jti();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.get()
                    .uri("/{jti}/verify", jti)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                stateMachine.onHeartbeatFailure("empty response");
                return;
            }
            Object status = body.get("status");
            if (!"active".equals(status)) {
                stateMachine.onHeartbeatFailure("status=" + status);
                return;
            }
            Object latest = body.get("latestToken");
            if (!(latest instanceof String jws)) {
                stateMachine.onHeartbeatFailure("missing latestToken");
                return;
            }
            LicenseToken refreshed = verifier.verify(jws);
            stateMachine.onHeartbeatSuccess(refreshed, clock.instant());
            Path cachePath = props.cachePath();
            cache.write(cachePath, new LicenseCache.Entry(jws, clock.instant()));
            log.debug("license heartbeat ok: jti={}", refreshed.jti());
        } catch (RestClientException e) {
            stateMachine.onHeartbeatFailure("http: " + e.getClass().getSimpleName());
        } catch (LicenseVerificationException e) {
            stateMachine.onHeartbeatFailure("verify: " + e.getMessage());
        } catch (Exception e) {
            stateMachine.onHeartbeatFailure("unexpected: " + e.getClass().getSimpleName());
            log.warn("license heartbeat unexpected error", e);
        }
    }
}
