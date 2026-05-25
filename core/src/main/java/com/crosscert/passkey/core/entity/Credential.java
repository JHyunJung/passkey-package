package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "CREDENTIAL")
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "credential_seq")
    @SequenceGenerator(name = "credential_seq", sequenceName = "CREDENTIAL_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TENANT_ID", length = 64, nullable = false)
    private String tenantId;

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

    public Credential(String tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] publicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public byte[] getCredentialId() { return credentialId; }
    public long getSignCount() { return signCount; }
}
