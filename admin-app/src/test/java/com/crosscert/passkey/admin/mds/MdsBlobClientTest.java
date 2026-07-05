package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsException;
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdsBlobClientTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void fetchDownloadsVerifiesAndReturnsBlob() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("the.raw.jwt");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);

        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        MdsBlob blob = new MdsBlob(7, LocalDate.of(2026, 1, 1), List.of());
        when(verifier.verify(any(), any(), any())).thenReturn(blob);

        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/", FIXED_CLOCK);

        MdsBlobClient.FetchResult result = client.fetch();
        assertThat(result.rawJwt()).isEqualTo("the.raw.jwt");
        assertThat(result.blob().no()).isEqualTo(7);
    }

    @Test
    void fetchSurfacesHttpErrorAsIllegalState() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);
        MdsBlobClient client = new MdsBlobClient(http,
                mock(MetadataBlobVerifier.class), mock(MdsRootCertProvider.class),
                "https://mds3.fidoalliance.org/", FIXED_CLOCK);
        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MDS fetch failed");
    }

    @Test
    void fetchSurfacesVerifyFailureAsIllegalState() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("bad.jwt");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);
        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        when(verifier.verify(any(), any(), any())).thenThrow(
                new MdsException(MdsException.Reason.BAD_SIGNATURE, "bad sig"));
        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());
        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/", FIXED_CLOCK);
        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MDS fetch failed");
    }

    /**
     * G21 배선 검증: MdsBlobClient가 verifier.verify()를 clock.instant()를 asOf로
     * 넘겨 3-인자로 호출하는지 확인한다 — 죽은 코드였던 신선도 검사가 실제 프로덕션
     * 호출 경로에서 트리거되는지가 이 테스트의 핵심이다(2-인자만 호출하면 stale
     * 검사가 절대 실행되지 않는다).
     */
    @Test
    void fetchInvokesThreeArgVerifyWithClockInstantAsOf() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("the.raw.jwt");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);

        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        MdsBlob blob = new MdsBlob(7, LocalDate.of(2026, 1, 1), List.of());
        when(verifier.verify(any(), any(), any())).thenReturn(blob);

        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/", FIXED_CLOCK);
        client.fetch();

        verify(verifier).verify(eq("the.raw.jwt"), any(), eq(FIXED_CLOCK.instant()));
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
    void fetchSurfacesStaleRejectionAsIllegalStateWithoutSideEffects() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("stale.raw.jwt");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(resp);

        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        when(verifier.verify(any(), any(), any())).thenThrow(
                new MdsException(MdsException.Reason.STALE, "MDS blob stale"));
        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/", FIXED_CLOCK);

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDS fetch failed")
                .cause().isInstanceOf(MdsException.class);
    }
}
