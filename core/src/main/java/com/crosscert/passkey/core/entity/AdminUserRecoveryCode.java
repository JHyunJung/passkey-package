package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 관리자 MFA recovery code 레코드 (1회용 코드의 sha-256 hash).
 *
 * <p>BaseEntity 상속 안 함 — V36 의 ADMIN_USER_RECOVERY_CODE 테이블은
 * {@code id RAW(16) DEFAULT SYS_GUID()} 로 DB 가 PK 를 생성하고, BaseEntity 가
 * 요구하는 {@code UPDATED_AT} 컬럼이 없다.
 *
 * <p>id/created_at 매핑은 <b>DB-default read-back 패턴</b>이다: 두 컬럼을
 * insert 문에서 제외하고({@code insertable = false}) Oracle DEFAULT
 * (SYS_GUID()/SYSTIMESTAMP)가 채우게 둔 뒤, insert 직후 DB 값을 즉시 되읽어온다
 * ({@code @Generated(INSERT)}). Hibernate 6.6 에서 정상 하이드레이션된다.
 *
 * <p>이는 SchedulerLease 의 <b>explicit-id 방식과는 다르다</b> — SchedulerLease 는
 * {@code @Generated} 를 쓰지 않고 caller 가 생성자로 id 를 명시 전달한다. 본 엔티티의
 * DB-default read-back 방식은 core 모듈에서 새로 도입된 패턴이다.
 */
@Entity
@Table(name = "ADMIN_USER_RECOVERY_CODE")
public class AdminUserRecoveryCode {

    @Id
    @Generated(event = EventType.INSERT)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)", insertable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ADMIN_USER_ID", columnDefinition = "RAW(16)", nullable = false, updatable = false)
    private UUID adminUserId;

    @Column(name = "CODE_HASH", length = 64, nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "USED_AT")
    private OffsetDateTime usedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "CREATED_AT", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AdminUserRecoveryCode() {}

    public AdminUserRecoveryCode(UUID adminUserId, String codeHash) {
        this.adminUserId = adminUserId;
        this.codeHash = codeHash;
    }

    // ──────────────────────────────────────────────────────────────────
    // Accessors

    public UUID getId() { return id; }

    public UUID getAdminUserId() { return adminUserId; }

    public String getCodeHash() { return codeHash; }

    public OffsetDateTime getUsedAt() { return usedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    // ──────────────────────────────────────────────────────────────────
    // Business helpers

    public boolean isUsed() { return usedAt != null; }

    public void markUsed(OffsetDateTime now) { this.usedAt = now; }
}
