package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "CREDENTIAL")
public class Credential {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "USER_HANDLE", length = 64, nullable = false)
    private byte[] userHandle;

    @Column(name = "CREDENTIAL_ID", length = 1023, nullable = false)
    private byte[] credentialId;

    @Lob
    @Column(name = "PUBLIC_KEY", nullable = false)
    private byte[] publicKey;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    @Column(name = "AAGUID", length = 16)
    private byte[] aaguid;

    @Column(name = "TRANSPORTS", length = 128)
    private String transports;

    @Column(name = "ATTESTATION_FMT", length = 64)
    private String attestationFmt;

    @Lob
    @Column(name = "BACKUP_STATE")
    private String backupStateJson;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    protected Credential() {}

    public Credential(UUID tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] publicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public byte[] getCredentialId() { return credentialId; }
    public long getSignCount() { return signCount; }
    public byte[] getUserHandle() { return userHandle; }
    public byte[] getAaguid() { return aaguid; }
    public byte[] getCredentialRecordBytes() { return publicKey; } // BLOB now holds CBOR CredentialRecord — see followups doc

    /**
     * Atomic state mutation after a successful authentication. signCount
     * must be strictly increasing (replay defense); callers verify that
     * before calling this.
     */
    public void recordAuthentication(long newSignCount, byte[] newCredentialRecordBytes, java.time.Instant now) {
        this.signCount = newSignCount;
        this.publicKey = newCredentialRecordBytes;
        this.lastUsedAt = now;
    }
}
