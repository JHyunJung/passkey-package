package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 등록된 패스키 1건. 이 파일의 {@code @FilterDef} 가 프로젝트 전체의 {@code tenantFilter} 정의이고,
 * 다른 엔티티는 {@code @Filter} 로 같은 이름을 참조한다.
 *
 * <p>{@code @Version}·{@code @DynamicUpdate} 가 없어 dirty checking 의 UPDATE 가 SIGN_COUNT 를 포함한 모든 컬럼을 쓴다.
 * 락 없이 읽어 label 등을 고치고 flush 하면 그사이 커밋된 {@code /authentication/finish} 의 signCount 갱신을
 * 읽었던 값으로 되돌려 replay 방어가 약해진다. 그래서 수정 경로는
 * {@link com.crosscert.passkey.core.repository.CredentialRepository#findByCredentialIdForUpdate}·
 * {@link com.crosscert.passkey.core.repository.CredentialRepository#findOwnedForUpdate} 로 행을 잠근 뒤 고친다.
 */
@Entity
@Table(name = "CREDENTIAL")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Credential extends BaseEntity {

    @Column(name = "TENANT_ID", nullable = false, columnDefinition = "RAW(16)")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID tenantId;

    @Column(name = "USER_HANDLE", length = 64, nullable = false)
    private byte[] userHandle;

    @Column(name = "CREDENTIAL_ID", length = 1023, nullable = false)
    private byte[] credentialId;

    @Lob
    @Column(name = "COSE_PUBLIC_KEY", nullable = false)
    private byte[] cosePublicKey;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    @Column(name = "AAGUID", length = 16)
    private byte[] aaguid;

    @Column(name = "TRANSPORTS", length = 128)
    private String transports;

    @Column(name = "ATTESTATION_FMT", length = 64)
    private String attestationFmt;

    @Column(name = "LAST_USED_AT")
    private OffsetDateTime lastUsedAt;

    @Column(name = "LABEL", length = 128)
    private String label;

    protected Credential() {}

    public Credential(UUID tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] cosePublicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.cosePublicKey = cosePublicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
    }

    public UUID getTenantId() { return tenantId; }
    public byte[] getCredentialId() { return credentialId; }
    public long getSignCount() { return signCount; }
    public byte[] getUserHandle() { return userHandle; }
    public byte[] getAaguid() { return aaguid; }
    public String getTransports() { return transports; }
    public void setTransports(String transports) { this.transports = transports; }
    public String getAttestationFmt() { return attestationFmt; }
    public void setAttestationFmt(String attestationFmt) { this.attestationFmt = attestationFmt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public byte[] getCosePublicKey() { return cosePublicKey; }

    /**
     * Atomic state mutation after a successful authentication. signCount
     * must be strictly increasing (replay defense); callers verify that
     * before calling this. The stored COSE public key is immutable here —
     * the prior signature took a byte[] that callers only ever passed back
     * unchanged (self-assign no-op), so it was removed.
     */
    public void recordAuthentication(long newSignCount, OffsetDateTime now) {
        this.signCount = newSignCount;
        this.lastUsedAt = now;
    }
}
