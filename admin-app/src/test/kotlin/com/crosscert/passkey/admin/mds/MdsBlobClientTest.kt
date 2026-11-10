package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.webauthn.mds.MdsBlob
import com.crosscert.passkey.webauthn.mds.MdsException
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MdsBlobClientTest {

    companion object {
        private val FIXED_CLOCK: Clock =
            Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun fetchDownloadsVerifiesAndReturnsBlob() {
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(200)
        whenever(resp.body()).thenReturn("the.raw.jwt")
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)

        val verifier = mock<MetadataBlobVerifier>()
        val blob = MdsBlob(7, LocalDate.of(2026, 1, 1), listOf())
        whenever(verifier.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(blob)

        val rootProvider = mock<MdsRootCertProvider>()
        whenever(rootProvider.anchors()).thenReturn(setOf())

        val client = MdsBlobClient(
            http, verifier, rootProvider,
            "https://mds.fidoalliance.org/", FIXED_CLOCK,
        )

        val result = client.fetch()
        assertThat(result.rawJwt).isEqualTo("the.raw.jwt")
        assertThat(result.blob.no()).isEqualTo(7)
    }

    @Test
    fun fetchSurfacesHttpErrorAsIllegalState() {
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(503)
        whenever(resp.headers()).thenReturn(HttpHeaders.of(mapOf()) { _, _ -> true })
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)
        val client = MdsBlobClient(
            http,
            mock<MetadataBlobVerifier>(), mock<MdsRootCertProvider>(),
            "https://mds.fidoalliance.org/", FIXED_CLOCK,
        )
        assertThatThrownBy { client.fetch() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("MDS fetch failed: HTTP 503")
    }

    @Test
    fun fetchRedirectFailsWithLocationInMessage() {
        // 공급자가 주소를 옮기면(실제로 mds3 → mds, 301) 리다이렉트를 따르지 않고 실패하되,
        // 새 주소를 메시지로 드러내야 한다.
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(301)
        whenever(resp.headers()).thenReturn(
            HttpHeaders.of(mapOf("location" to listOf("https://mds.fidoalliance.org/"))) { _, _ -> true },
        )
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)
        val client = MdsBlobClient(
            http,
            mock<MetadataBlobVerifier>(), mock<MdsRootCertProvider>(),
            "https://mds3.fidoalliance.org/", FIXED_CLOCK,
        )
        assertThatThrownBy { client.fetch() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HTTP 301")
            .hasMessageContaining("Location: https://mds.fidoalliance.org/")
    }

    @Test
    fun defaultEndpointIsCurrentFidoHost() {
        // mds3.fidoalliance.org 는 301 로 mds.fidoalliance.org 로 옮겨졌다 — 기본값이 옛 주소면 동기화가 항상 실패한다.
        val param = MdsBlobClient::class.java.constructors.single().parameters
            .single { it.isAnnotationPresent(org.springframework.beans.factory.annotation.Value::class.java) }
        assertThat(param.getAnnotation(org.springframework.beans.factory.annotation.Value::class.java).value)
            .isEqualTo("\${passkey.mds.blob-endpoint:https://mds.fidoalliance.org/}")
    }

    @Test
    fun fetchSurfacesVerifyFailureAsIllegalState() {
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(200)
        whenever(resp.body()).thenReturn("bad.jwt")
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)
        val verifier = mock<MetadataBlobVerifier>()
        whenever(verifier.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(
            MdsException(MdsException.Reason.BAD_SIGNATURE, "bad sig"),
        )
        val rootProvider = mock<MdsRootCertProvider>()
        whenever(rootProvider.anchors()).thenReturn(setOf())
        val client = MdsBlobClient(
            http, verifier, rootProvider,
            "https://mds.fidoalliance.org/", FIXED_CLOCK,
        )
        assertThatThrownBy { client.fetch() }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("MDS fetch failed")
    }

    /**
     * G21 배선 검증: MdsBlobClient가 verifier.verify()를 clock.instant()를 asOf로
     * 넘겨 3-인자로 호출하는지 확인한다 — 죽은 코드였던 신선도 검사가 실제 프로덕션
     * 호출 경로에서 트리거되는지가 이 테스트의 핵심이다(2-인자만 호출하면 stale
     * 검사가 절대 실행되지 않는다).
     */
    @Test
    fun fetchInvokesThreeArgVerifyWithClockInstantAsOf() {
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(200)
        whenever(resp.body()).thenReturn("the.raw.jwt")
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)

        val verifier = mock<MetadataBlobVerifier>()
        val blob = MdsBlob(7, LocalDate.of(2026, 1, 1), listOf())
        whenever(verifier.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(blob)

        val rootProvider = mock<MdsRootCertProvider>()
        whenever(rootProvider.anchors()).thenReturn(setOf())

        val client = MdsBlobClient(
            http, verifier, rootProvider,
            "https://mds.fidoalliance.org/", FIXED_CLOCK,
        )
        client.fetch()

        verify(verifier).verify(eq("the.raw.jwt"), anyOrNull(), eq(FIXED_CLOCK.instant()))
    }

    /**
     * G21 배선 검증(가용성): stale blob이 거부되어 fetch()가 예외를 던져도, 이 시점에는
     * 아직 MdsBlobStore/Redis 캐시에 아무것도 쓰여지지 않는다 — MdsSchedulerService의
     * try 블록에서 store.store(blob)/캐시 populate는 fetch() 성공 이후에만 실행되므로,
     * stale 거부는 기존 캐시를 절대 훼손하지 않는다(fail-safe). 이 테스트는 STALE도
     * 다른 MdsException과 동일하게 IllegalStateException으로 전파되어 스케줄러의
     * catch(RuntimeException)에 잡힌다는 계약을 고정한다.
     */
    @Test
    fun fetchSurfacesStaleRejectionAsIllegalStateWithoutSideEffects() {
        val http = mock<HttpClient>()
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(200)
        whenever(resp.body()).thenReturn("stale.raw.jwt")
        whenever(http.send(anyOrNull<HttpRequest>(), anyOrNull<HttpResponse.BodyHandler<String>>())).thenReturn(resp)

        val verifier = mock<MetadataBlobVerifier>()
        whenever(verifier.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(
            MdsException(MdsException.Reason.STALE, "MDS blob stale"),
        )
        val rootProvider = mock<MdsRootCertProvider>()
        whenever(rootProvider.anchors()).thenReturn(setOf())

        val client = MdsBlobClient(
            http, verifier, rootProvider,
            "https://mds.fidoalliance.org/", FIXED_CLOCK,
        )

        assertThatThrownBy { client.fetch() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MDS fetch failed")
            .cause().isInstanceOf(MdsException::class.java)
    }
}
