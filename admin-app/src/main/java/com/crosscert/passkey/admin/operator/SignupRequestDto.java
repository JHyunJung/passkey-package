package com.crosscert.passkey.admin.operator;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SignupRequestDto {
    private SignupRequestDto() {}

    /** 공개 가입 요청 본문. 비밀번호 정책은 초대 수락 때와 같은 12~128자. */
    public record Create(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 500) String reason
    ) {}

    /** 관리자 목록 뷰. bcrypt 해시는 절대 노출하지 않는다. */
    public record View(UUID id, String email, String reason, OffsetDateTime requestedAt) {}

    /** 승인 본문. 역할·테넌트 규칙은 서비스에서 검증한다. */
    public record Approve(@NotBlank String role, List<UUID> tenantIds) {}
}
