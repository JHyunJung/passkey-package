package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WebauthnDiffService {

    private final TenantRepository tenantRepo;

    public WebauthnDiffService(TenantRepository tenantRepo) {
        this.tenantRepo = tenantRepo;
    }

    @Transactional(readOnly = true)
    public WebauthnConfigDiff diff(UUID tenantId, TenantAdminDto.TenantUpdateRequest proposed) {
        Tenant t = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant not found " + tenantId));

        // 현재 값
        WebauthnConfigDiff.Current cur = new WebauthnConfigDiff.Current(
                t.getRpId(), t.getRpName(),
                t.getAllowedOriginValues(),
                new ArrayList<>(t.getAcceptedFormatValues()),
                t.isRequireUserVerification(), t.isMdsRequired());

        // 제안 (null → 현재 값 유지, @NotNull/@NotBlank 이지만 diff 미리보기 시 partial 허용)
        WebauthnConfigDiff.Proposed prop = new WebauthnConfigDiff.Proposed(
                proposed.rpId() != null ? proposed.rpId() : cur.rpId(),
                proposed.rpName() != null ? proposed.rpName() : cur.rpName(),
                proposed.allowedOrigins() != null ? proposed.allowedOrigins() : cur.origins(),
                proposed.acceptedFormats() != null ? new ArrayList<>(proposed.acceptedFormats()) : cur.formats(),
                proposed.requireUserVerification() != null ? proposed.requireUserVerification() : cur.requireUserVerification(),
                proposed.mdsRequired() != null ? proposed.mdsRequired() : cur.mdsRequired());

        List<WebauthnConfigDiff.FieldChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Objects.equals(cur.rpId(), prop.rpId())) {
            changes.add(new WebauthnConfigDiff.FieldChange("rpId", cur.rpId(), prop.rpId(), null, null));
            warnings.add("RP_ID_CHANGED");
        }
        if (!Objects.equals(cur.rpName(), prop.rpName())) {
            changes.add(new WebauthnConfigDiff.FieldChange("rpName", cur.rpName(), prop.rpName(), null, null));
        }

        List<String> originsAdded = new ArrayList<>(prop.origins());
        originsAdded.removeAll(cur.origins());
        List<String> originsRemoved = new ArrayList<>(cur.origins());
        originsRemoved.removeAll(prop.origins());
        if (!originsAdded.isEmpty() || !originsRemoved.isEmpty()) {
            changes.add(new WebauthnConfigDiff.FieldChange("origins", null, null, originsAdded, originsRemoved));
            if (!originsRemoved.isEmpty()) warnings.add("ORIGIN_REMOVED");
        }

        List<String> formatsAdded = new ArrayList<>(prop.formats());
        formatsAdded.removeAll(cur.formats());
        List<String> formatsRemoved = new ArrayList<>(cur.formats());
        formatsRemoved.removeAll(prop.formats());
        if (!formatsAdded.isEmpty() || !formatsRemoved.isEmpty()) {
            changes.add(new WebauthnConfigDiff.FieldChange("formats", null, null, formatsAdded, formatsRemoved));
        }

        if (cur.requireUserVerification() != prop.requireUserVerification()) {
            changes.add(new WebauthnConfigDiff.FieldChange("requireUserVerification",
                    cur.requireUserVerification(), prop.requireUserVerification(), null, null));
            if (prop.requireUserVerification() && !cur.requireUserVerification()) {
                warnings.add("UV_RAISED_TO_REQUIRED");
            }
        }

        if (cur.mdsRequired() != prop.mdsRequired()) {
            changes.add(new WebauthnConfigDiff.FieldChange("mdsRequired",
                    cur.mdsRequired(), prop.mdsRequired(), null, null));
            if (prop.mdsRequired() && !cur.mdsRequired()) {
                warnings.add("MDS_RAISED_TO_REQUIRED");
            }
        }

        return new WebauthnConfigDiff(cur, prop, changes, warnings);
    }
}
