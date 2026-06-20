package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "SIGNING_KEY")
public class SigningKey extends BaseEntity {

    @Column(name = "KID", length = 64, nullable = false, updatable = false)
    private String kid;

    @Column(name = "ALG", length = 16, nullable = false, updatable = false)
    private String alg;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Lob
    @Column(name = "PUBLIC_JWK", nullable = false, updatable = false)
    private String publicJwk;

    @Lob
    @Column(name = "PRIVATE_PKCS8", nullable = false, updatable = false)
    private byte[] privatePkcs8;

    @Column(name = "ROTATED_AT")
    private OffsetDateTime rotatedAt;

    @Column(name = "REVOKED_AT")
    private OffsetDateTime revokedAt;

    protected SigningKey() {}

    public SigningKey(String kid, String alg, String publicJwk, byte[] privatePkcs8) {
        this.kid = kid;
        this.alg = alg;
        this.status = "ACTIVE";
        this.publicJwk = publicJwk;
        this.privatePkcs8 = privatePkcs8;
    }

    public String getKid() { return kid; }
    public String getAlg() { return alg; }
    public String getStatus() { return status; }
    public String getPublicJwk() { return publicJwk; }
    public byte[] getPrivatePkcs8() { return privatePkcs8; }
    public OffsetDateTime getRotatedAt() { return rotatedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }

    public void rotate(OffsetDateTime now) {
        this.status = "ROTATED";
        this.rotatedAt = now;
    }

    public void revoke(OffsetDateTime now) {
        this.status = "REVOKED";
        this.revokedAt = now;
    }
}
