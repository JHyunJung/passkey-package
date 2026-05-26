package com.crosscert.passkey.admin.mds;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.metadata.FidoMDS3MetadataBLOBProvider;
import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Thin Spring wrapper around webauthn4j's
 * {@link FidoMDS3MetadataBLOBProvider}. Encapsulates the
 * constructor-driven configuration so callers depend on a narrow
 * {@link #fetch()} method.
 *
 * <p>The underlying provider downloads the JWT BLOB over HTTPS,
 * verifies its signature against the configured FIDO Alliance trust
 * anchor, walks the cert chain, and returns a parsed
 * {@link MetadataBLOB}.
 */
@Component
public class MdsBlobClient {

    private final MetadataBLOBProvider provider;

    public MdsBlobClient(MetadataBLOBProvider provider) {
        this.provider = provider;
    }

    public MetadataBLOB fetch() {
        try {
            return provider.provide();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }
    }

    @Configuration
    static class Wiring {
        @Bean
        public MetadataBLOBProvider fidoMds3MetadataBLOBProvider(
                MdsRootCertProvider rootProvider,
                @Value("${passkey.mds.blob-endpoint:https://mds3.fidoalliance.org/}")
                String endpoint) {
            ObjectConverter oc = new ObjectConverter();
            return new FidoMDS3MetadataBLOBProvider(oc, endpoint, rootProvider.get());
        }
    }
}
