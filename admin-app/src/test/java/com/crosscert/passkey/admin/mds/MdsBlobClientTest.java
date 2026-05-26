package com.crosscert.passkey.admin.mds;

import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsBlobClientTest {

    private MetadataBLOBProvider underlying;
    private MdsBlobClient client;

    @BeforeEach
    void setUp() {
        underlying = mock(MetadataBLOBProvider.class);
        client = new MdsBlobClient(underlying);
    }

    @Test
    void fetchDelegatesToUnderlyingProvider() {
        MetadataBLOB blob = mock(MetadataBLOB.class);
        when(underlying.provide()).thenReturn(blob);

        MetadataBLOB result = client.fetch();
        assertThat(result).isSameAs(blob);
    }

    @Test
    void fetchSurfacesProviderExceptionAsIllegalStateException() {
        when(underlying.provide()).thenThrow(new RuntimeException("upstream 503"));

        assertThatThrownBy(() -> client.fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDS fetch failed");
    }
}
