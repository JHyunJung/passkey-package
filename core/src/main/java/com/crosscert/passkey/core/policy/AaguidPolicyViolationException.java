package com.crosscert.passkey.core.policy;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

/**
 * 등록 ceremony 에서 AAGUID 정책 위반 시 발생.
 *
 * <p>{@link com.crosscert.passkey.core.api.GlobalExceptionHandler} 의
 * {@code handleBusiness} 핸들러가 {@link BusinessException} 을 잡아
 * ErrorCode.getStatus() (403 Forbidden) + ApiResponse 로 변환한다.
 */
public class AaguidPolicyViolationException extends BusinessException {

    private final String tenantId;
    private final String aaguid;

    public AaguidPolicyViolationException(String tenantId, String aaguid, ErrorCode code) {
        super(code, code.getMessage() + " [tenant=" + tenantId + ", aaguid=" + aaguid + "]");
        this.tenantId = tenantId;
        this.aaguid = aaguid;
    }

    public String getTenantId() { return tenantId; }
    public String getAaguid() { return aaguid; }
}
