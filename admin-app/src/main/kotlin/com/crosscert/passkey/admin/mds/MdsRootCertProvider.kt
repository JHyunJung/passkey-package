package com.crosscert.passkey.admin.mds

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * Loads the FIDO Alliance MDS3 root CA certificate from a Spring
 * resource (default: classpath:fido/fido-mds-root.crt). Exposes it as
 * an X509Certificate and as a PKIX [TrustAnchor]
 * set for the native [com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier].
 *
 * Test profile (application-test.yml) overrides
 * `passkey.mds.root-cert` to a test-only self-signed CA so
 * MdsSchedulerIT can verify the full fetch + cert-chain path without
 * depending on the live FIDO Alliance PKI.
 */
@Component
class MdsRootCertProvider(
    @Value("\${passkey.mds.root-cert:classpath:fido/fido-mds-root.crt}") certResource: Resource,
) {

    private val root: X509Certificate = try {
        certResource.inputStream.use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
    } catch (e: Exception) {
        throw IllegalStateException("failed to load FIDO MDS3 root cert from $certResource", e)
    }

    fun get(): X509Certificate = root

    /** FIDO MDS3 root cert를 PKIX TrustAnchor 집합으로. MetadataBlobVerifier.verify에 주입. */
    fun anchors(): Set<TrustAnchor> = java.util.Set.of(TrustAnchor(root, null))
}
