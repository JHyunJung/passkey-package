package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tenant 의 변경 추적용 스냅샷. update() before/after 비교에 쓰인다.
 * audit log payload 의 before/after 키로 직렬화 가능 (record + 기본 타입).
 */
public record TenantSnapshot(
        String displayName,
        String rpName,
        Set<String> allowedOrigins,
        Set<String> acceptedFormats,
        boolean requireUserVerification,
        boolean mdsRequired,
        String attestationConveyance,
        int webauthnTimeoutMs
) {

    public static TenantSnapshot of(Tenant t) {
        return new TenantSnapshot(
                t.getDisplayName(),
                t.getRpName(),
                new TreeSet<>(t.getAllowedOriginValues()),
                new TreeSet<>(t.getAcceptedFormatValues()),
                t.isRequireUserVerification(),
                t.isMdsRequired(),
                t.getAttestationConveyance(),
                t.getWebauthnTimeoutMs());
    }

    /**
     * before vs after 비교해 변경된 필드 이름 리스트 반환.
     * audit payload 의 changedFields 키로 직렬화.
     */
    public List<String> diff(TenantSnapshot other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(this.displayName, other.displayName))         changed.add("displayName");
        if (!Objects.equals(this.rpName, other.rpName))                   changed.add("rpName");
        if (!Objects.equals(this.allowedOrigins, other.allowedOrigins))   changed.add("allowedOrigins");
        if (!Objects.equals(this.acceptedFormats, other.acceptedFormats)) changed.add("acceptedFormats");
        if (this.requireUserVerification != other.requireUserVerification) changed.add("requireUserVerification");
        if (this.mdsRequired != other.mdsRequired)                         changed.add("mdsRequired");
        if (!Objects.equals(this.attestationConveyance, other.attestationConveyance)) changed.add("attestationConveyance");
        if (this.webauthnTimeoutMs != other.webauthnTimeoutMs)             changed.add("webauthnTimeoutMs");
        return changed;
    }
}
