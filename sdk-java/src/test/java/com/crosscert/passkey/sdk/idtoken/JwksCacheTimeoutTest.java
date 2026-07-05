package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * G19 — JWKS fetch에 connect/read timeout이 없으면 hung 엔드포인트가 single-flight
 * 락 하에서 전체 id-token 검증을 무한 차단한다(JwksCache.get() → synchronized(refreshLock)
 * → fetch() → JWKSet.load(jwksUrl) with connectTimeout=0/readTimeout=0).
 *
 * <p>WireMock의 {@code withFixedDelay}로 응답을 인위적으로 지연시켜 실제 소켓 read를
 * 재현하고, JwksCache가 짧은 readTimeout 내에 fail-fast 하는지 검증한다. 폴백 스냅샷이
 * 없는 최초 부팅 상황이므로 timeout은 PasskeyIdTokenException으로 관측된다(F27의
 * 기존 "fetch 실패 시 예외 전파, 폴백 없으면 fail-closed" 동작과 동일 계약).
 */
class JwksCacheTimeoutTest {

    private WireMockServer wm;

    @AfterEach
    void tearDown() {
        if (wm != null) wm.stop();
    }

    @Test
    void get_hungJwksEndpoint_failsFastInsteadOfBlockingForever() {
        // given: JWKS 엔드포인트가 읽기 타임아웃(500ms)보다 훨씬 긴 지연(10s) 후 응답한다.
        // timeout이 걸려 있지 않다면 JWKSet.load()가 이 응답을 기다리며 최소 10초간
        // refreshLock을 쥔 채 블로킹한다.
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(10_000)
                        .withBody("{\"keys\":[]}")));

        JwksCache cache = new JwksCache(
                URI.create("http://localhost:" + wm.port()),
                Duration.ofMinutes(5),
                Clock.systemUTC(),
                Duration.ofMillis(500),  // connectTimeout
                Duration.ofMillis(500)); // readTimeout

        long startNanos = System.nanoTime();
        // then: 10초 지연을 기다리지 않고 read timeout(500ms) 근방에서 fail-fast 해야 한다.
        assertThatThrownBy(cache::get)
                .isInstanceOf(PasskeyIdTokenException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertThat(elapsedMs)
                .as("read timeout(500ms)을 크게 넘겨 10s 지연을 다 기다리면 안 된다")
                .isLessThan(5_000);
    }
}
