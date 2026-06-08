package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsException;
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsBlobClientTest {

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
        when(verifier.verify(any(), any())).thenReturn(blob);

        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/");

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
                "https://mds3.fidoalliance.org/");
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
        when(verifier.verify(any(), any())).thenThrow(
                new MdsException(MdsException.Reason.BAD_SIGNATURE, "bad sig"));
        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());
        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/");
        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MDS fetch failed");
    }
}
