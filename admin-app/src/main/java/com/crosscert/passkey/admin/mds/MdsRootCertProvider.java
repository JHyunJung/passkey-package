package com.crosscert.passkey.admin.mds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Loads the FIDO Alliance MDS3 root CA certificate from a Spring
 * resource (default: classpath:fido/fido-mds-root.crt). Exposes it as
 * an X509Certificate and as a PKIX {@link java.security.cert.TrustAnchor}
 * set for the native {@link com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier}.
 *
 * <p>Test profile (application-test.yml) overrides
 * {@code passkey.mds.root-cert} to a test-only self-signed CA so
 * MdsSchedulerIT can verify the full fetch + cert-chain path without
 * depending on the live FIDO Alliance PKI.
 */
@Component
public class MdsRootCertProvider {

    private final X509Certificate root;

    public MdsRootCertProvider(
            @Value("${passkey.mds.root-cert:classpath:fido/fido-mds-root.crt}")
            Resource certResource) {
        try (InputStream in = certResource.getInputStream()) {
            this.root = (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to load FIDO MDS3 root cert from " + certResource, e);
        }
    }

    public X509Certificate get() {
        return root;
    }

    /** FIDO MDS3 root cert를 PKIX TrustAnchor 집합으로. MetadataBlobVerifier.verify에 주입. */
    public java.util.Set<java.security.cert.TrustAnchor> anchors() {
        return java.util.Set.of(new java.security.cert.TrustAnchor(root, null));
    }
}
