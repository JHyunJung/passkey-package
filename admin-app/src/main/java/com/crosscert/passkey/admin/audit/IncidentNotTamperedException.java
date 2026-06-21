package com.crosscert.passkey.admin.audit;

/** incident 생성 요청 시 해당 테넌트가 실제로는 위변조 상태가 아닐 때. → HTTP 422. */
public class IncidentNotTamperedException extends RuntimeException {
    public IncidentNotTamperedException(String message) { super(message); }
}
