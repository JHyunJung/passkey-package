package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsException;
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier;
import com.crosscert.passkey.webauthn.mds.NativeMetadataBlobVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * MDS3 BLOB을 HTTPS로 다운로드하고 자체 {@link MetadataBlobVerifier}로
 * JWS 서명·X.509 체인을 검증한 뒤 파싱한다. webauthn4j 의존 없음.
 */
@Slf4j
@Component
public class MdsBlobClient {

    private final HttpClient httpClient;
    private final MetadataBlobVerifier verifier;
    private final MdsRootCertProvider rootProvider;
    private final String endpoint;
    private final Clock clock;

    public MdsBlobClient(HttpClient httpClient,
                         MetadataBlobVerifier verifier,
                         MdsRootCertProvider rootProvider,
                         @Value("${passkey.mds.blob-endpoint:https://mds3.fidoalliance.org/}")
                         String endpoint,
                         Clock clock) {
        this.httpClient = httpClient;
        this.verifier = verifier;
        this.rootProvider = rootProvider;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    public FetchResult fetch() {
        Instant started = Instant.now();
        String rawJwt;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("MDS fetch failed: HTTP " + resp.statusCode());
            }
            rawJwt = resp.body().trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // HttpClient.send는 InterruptedException을 던질 수 있다. broad catch로 묶되
            // 인터럽트 신호는 스레드 풀/스케줄러를 위해 복원한다 (codex 관찰).
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.error("mds blob fetch: transport error url={} durMs={} cause={}",
                    endpoint, durMs, e.toString(), e);
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }
        try {
            // asOf=clock.instant()를 넘겨 nextUpdate 신선도까지 검증한다(fail-closed) —
            // 검증 시각을 외부 Clock에서 주입해 결정적 검증·테스트 가능성을 확보한다.
            MdsBlob blob = verifier.verify(rawJwt, rootProvider.anchors(), clock.instant());
            long durMs = Duration.between(started, Instant.now()).toMillis();
            int entries = blob.entries() == null ? 0 : blob.entries().size();
            log.info("mds blob fetch: url={} entries={} durMs={}", endpoint, entries, durMs);
            return new FetchResult(rawJwt, blob);
        } catch (MdsException e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            if (e.reason() == MdsException.Reason.BAD_SIGNATURE
                    || e.reason() == MdsException.Reason.UNTRUSTED_CHAIN) {
                log.warn("mds blob fetch: signature/chain verify failed url={} durMs={} reason={} cause={}",
                        endpoint, durMs, e.reason(), e.toString());
            } else if (e.reason() == MdsException.Reason.STALE) {
                // STALE은 서명/파싱 오류가 아니라 공급자(FIDO MDS3) 측 갱신 지연 가능성이
                // 있는 신선도 문제 — warn으로 구분한다. 거부 자체는 유지한다: 호출부
                // (MdsSchedulerService)는 fetch() 실패 시 기존 Redis 캐시(mds:aaguid:*)와
                // MdsBlobStore를 건드리지 않고 SyncResult.failed + HIGH 알림만 발행하므로
                // stale 거부가 가용성을 해치지 않는다(다음 6h 주기까지 이전 유효 캐시 유지,
                // TTL 7h > 주기).
                log.warn("mds blob fetch: stale blob rejected url={} durMs={} reason={} cause={}",
                        endpoint, durMs, e.reason(), e.toString());
            } else {
                log.error("mds blob fetch: parse error url={} durMs={} reason={} cause={}",
                        endpoint, durMs, e.reason(), e.toString(), e);
            }
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }
    }

    public record FetchResult(String rawJwt, MdsBlob blob) {}

    @Configuration
    static class Wiring {
        @Bean
        public MetadataBlobVerifier metadataBlobVerifier() {
            return new NativeMetadataBlobVerifier();
        }
        @Bean
        public HttpClient mdsHttpClient() {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }
    }
}
