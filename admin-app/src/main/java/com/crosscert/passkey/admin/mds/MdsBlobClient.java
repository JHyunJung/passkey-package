package com.crosscert.passkey.admin.mds;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.metadata.FidoMDS3MetadataBLOBProvider;
import com.webauthn4j.metadata.MetadataBLOBProvider;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

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

    private static final Logger log = LoggerFactory.getLogger(MdsBlobClient.class);

    private final MetadataBLOBProvider provider;
    private final String endpoint;

    public MdsBlobClient(MetadataBLOBProvider provider,
                         @Value("${passkey.mds.blob-endpoint:https://mds3.fidoalliance.org/}")
                         String endpoint) {
        this.provider = provider;
        this.endpoint = endpoint;
    }

    public MetadataBLOB fetch() {
        // webauthn4j's FidoMDS3MetadataBLOBProvider performs the HTTPS
        // download, JWT signature verification, and cert-chain walk
        // inside provide(). We can't observe HTTP status / blob size
        // directly, so we surface the boundary call: success with
        // duration + entry count, signature/parse failure as WARN,
        // and a generic ERROR for the rest.
        Instant started = Instant.now();
        try {
            MetadataBLOB blob = provider.provide();
            long durMs = Duration.between(started, Instant.now()).toMillis();
            int entries = blob.getPayload() == null || blob.getPayload().getEntries() == null
                    ? 0
                    : blob.getPayload().getEntries().size();
            log.info("mds blob fetch: url={} entries={} durMs={}", endpoint, entries, durMs);
            return blob;
        } catch (RuntimeException e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            String name = e.getClass().getSimpleName();
            // Heuristic: webauthn4j throws *SignatureException /
            // *VerificationException family on trust failures, vs
            // generic IO/RuntimeException for transport.
            if (name.contains("Signature") || name.contains("Verification")
                    || name.contains("Certificate") || name.contains("Trust")) {
                log.warn("mds blob fetch: signature verify failed url={} durMs={} cause={}",
                        endpoint, durMs, e.toString());
            } else {
                log.error("mds blob fetch: error url={} durMs={} cause={}",
                        endpoint, durMs, e.toString(), e);
            }
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
