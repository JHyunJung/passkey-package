package com.crosscert.passkey.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 가입 요청. 승인 전 상태만 보관한다 — 승인되면 admin_user 로 옮겨지고
 * 이 행은 삭제된다(거절도 삭제). 그래서 갱신 경로가 없고 모든 컬럼이 updatable=false.
 *
 * <p>BaseEntity 를 상속하지 않는다 — 테이블에 created_at/updated_at 이 없고
 * requested_at 하나만 있다(갱신되지 않는 행에 updated_at 은 의미가 없다).
 */
@Entity
@Table(name = "ADMIN_SIGNUP_REQUEST")
public class AdminSignupRequest {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "ID", columnDefinition = "RAW(16)")
    private UUID id;

    @Column(name = "EMAIL", length = 255, nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "BCRYPT_HASH", length = 72, nullable = false, updatable = false)
    private String bcryptHash;

    @Column(name = "REASON", length = 500, updatable = false)
    private String reason;

    @Column(name = "REQUESTED_AT", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    protected AdminSignupRequest() {}

    public AdminSignupRequest(String email, String bcryptHash, String reason, OffsetDateTime requestedAt) {
        this.email = email;
        this.bcryptHash = bcryptHash;
        this.reason = reason;
        this.requestedAt = requestedAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getBcryptHash() { return bcryptHash; }
    public String getReason() { return reason; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
}
