package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.webauthn.mds.MdsBlob
import com.crosscert.passkey.webauthn.mds.MdsException
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier
import com.crosscert.passkey.webauthn.mds.NativeMetadataBlobVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(MdsBlobClient::class.java)

/**
 * MDS3 BLOB을 HTTPS로 다운로드하고 자체 [MetadataBlobVerifier]로
 * JWS 서명·X.509 체인을 검증한 뒤 파싱한다. webauthn4j 의존 없음.
 */
@Component
class MdsBlobClient(
    private val httpClient: HttpClient,
    private val verifier: MetadataBlobVerifier,
    private val rootProvider: MdsRootCertProvider,
    @Value("\${passkey.mds.blob-endpoint:https://mds.fidoalliance.org/}") private val endpoint: String,
    private val clock: Clock,
) {

    fun fetch(): FetchResult {
        val started = Instant.now()
        val rawJwt: String = try {
            val req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30)).GET().build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                // HttpClient 는 리다이렉트를 따르지 않는다(기본 NEVER) — 공급자가 주소를 옮기면
                // 3xx 로 실패하므로 Location 을 메시지에 실어 운영자가 새 주소를 바로 알 수 있게 한다.
                val location = resp.headers().firstValue("location").orElse(null)
                throw IllegalStateException(
                    "MDS fetch failed: HTTP " + resp.statusCode() +
                        (if (location != null) " (Location: $location — passkey.mds.blob-endpoint 확인)" else ""),
                )
            }
            // Java String.trim() 과 같은 규칙(코드 포인트 <= U+0020 만 제거) — Kotlin trim() 은 유니코드 공백 전체를 제거한다.
            resp.body().trim { it <= ' ' }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // HttpClient.send는 InterruptedException을 던질 수 있다. broad catch로 묶되
            // 인터럽트 신호는 스레드 풀/스케줄러를 위해 복원한다 (codex 관찰).
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val durMs = Duration.between(started, Instant.now()).toMillis()
            log.error(
                "mds blob fetch: transport error url={} durMs={} cause={}",
                endpoint, durMs, e.toString(), e,
            )
            throw IllegalStateException("MDS fetch failed: " + e.message, e)
        }
        try {
            // asOf=clock.instant()를 넘겨 nextUpdate 신선도까지 검증한다(fail-closed) —
            // 검증 시각을 외부 Clock에서 주입해 결정적 검증·테스트 가능성을 확보한다.
            val blob = verifier.verify(rawJwt, rootProvider.anchors(), clock.instant())
            val durMs = Duration.between(started, Instant.now()).toMillis()
            val entries = if (blob.entries() == null) 0 else blob.entries().size
            log.info("mds blob fetch: url={} entries={} durMs={}", endpoint, entries, durMs)
            return FetchResult(rawJwt, blob)
        } catch (e: MdsException) {
            val durMs = Duration.between(started, Instant.now()).toMillis()
            if (e.reason() == MdsException.Reason.BAD_SIGNATURE ||
                e.reason() == MdsException.Reason.UNTRUSTED_CHAIN
            ) {
                log.warn(
                    "mds blob fetch: signature/chain verify failed url={} durMs={} reason={} cause={}",
                    endpoint, durMs, e.reason(), e.toString(),
                )
            } else if (e.reason() == MdsException.Reason.STALE) {
                // STALE은 서명/파싱 오류가 아니라 공급자(FIDO MDS3) 측 갱신 지연 가능성이
                // 있는 신선도 문제 — warn으로 구분한다. 거부 자체는 유지한다: 호출부
                // (MdsSchedulerService)는 fetch() 실패 시 기존 Redis 캐시(mds:aaguid:*)와
                // MdsBlobStore를 건드리지 않고 SyncResult.failed + HIGH 알림만 발행하므로
                // stale 거부가 가용성을 해치지 않는다(다음 6h 주기까지 이전 유효 캐시 유지,
                // TTL 7h > 주기).
                log.warn(
                    "mds blob fetch: stale blob rejected url={} durMs={} reason={} cause={}",
                    endpoint, durMs, e.reason(), e.toString(),
                )
            } else {
                log.error(
                    "mds blob fetch: parse error url={} durMs={} reason={} cause={}",
                    endpoint, durMs, e.reason(), e.toString(), e,
                )
            }
            throw IllegalStateException("MDS fetch failed: " + e.message, e)
        }
    }

    data class FetchResult(val rawJwt: String, val blob: MdsBlob)

    @Configuration
    internal class Wiring {
        @Bean
        fun metadataBlobVerifier(): MetadataBlobVerifier = NativeMetadataBlobVerifier()

        @Bean
        fun mdsHttpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }
}
