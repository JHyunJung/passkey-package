package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.core.entity.SigningKey;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class KeyMgmtDto {

    private KeyMgmtDto() {}

    public record SigningKeyView(
            UUID id,
            String kid,
            String alg,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime rotatedAt,
            OffsetDateTime revokedAt
    ) {
        public static SigningKeyView from(SigningKey k) {
            return new SigningKeyView(
                    k.getId(), k.getKid(), k.getAlg(), k.getStatus(),
                    k.getCreatedAt(), k.getRotatedAt(), k.getRevokedAt());
        }
    }

    public record RotateResponse(String oldKid, String newKid) {}

    public record KeyList(List<SigningKeyView> keys) {}
}
