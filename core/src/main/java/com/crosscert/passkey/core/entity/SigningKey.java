package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "SIGNING_KEY")
public class SigningKey {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

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

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ROTATED_AT")
    private Instant rotatedAt;

    @Column(name = "REVOKED_AT")
    private Instant revokedAt;

    protected SigningKey() {}

    public SigningKey(String kid, String alg, String publicJwk, byte[] privatePkcs8) {
        this.kid = kid;
        this.alg = alg;
        this.status = "ACTIVE";
        this.publicJwk = publicJwk;
        this.privatePkcs8 = privatePkcs8;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getKid() { return kid; }
    public String getAlg() { return alg; }
    public String getStatus() { return status; }
    public String getPublicJwk() { return publicJwk; }
    public byte[] getPrivatePkcs8() { return privatePkcs8; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public void rotate(Instant now) {
        this.status = "ROTATED";
        this.rotatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = "REVOKED";
        this.revokedAt = now;
    }
}
