package com.crosscert.passkey.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void okWithDataPopulatesFieldsAndPicksUpTraceId() {
        MDC.put("traceId", "abc123");
        ApiResponse<String> r = ApiResponse.ok("payload");
        assertThat(r.success()).isTrue();
        assertThat(r.code()).isEqualTo("OK");
        assertThat(r.message()).isEqualTo("Success");
        assertThat(r.data()).isEqualTo("payload");
        assertThat(r.error()).isNull();
        assertThat(r.traceId()).isEqualTo("abc123");
        assertThat(r.timestamp()).isNotNull();
    }

    @Test
    void okVoidLeavesDataNull() {
        ApiResponse<Void> r = ApiResponse.ok();
        assertThat(r.success()).isTrue();
        assertThat(r.data()).isNull();
    }

    @Test
    void errorFromCodeFillsErrorDetail() {
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.TENANT_NOT_FOUND);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("T001");
        assertThat(r.message()).isEqualTo("Tenant not found");
        assertThat(r.error().errorCode()).isEqualTo("T001");
        assertThat(r.error().fieldErrors()).isNull();
    }

    @Test
    void errorWithDetailOverridesMessageButKeepsCode() {
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.INVALID_INPUT, "custom detail");
        assertThat(r.code()).isEqualTo("C001");
        assertThat(r.message()).isEqualTo("custom detail");
    }

    @Test
    void errorWithFieldErrorsIncludesAllOfThem() {
        FieldError fe = new FieldError("email", "abc", "must be valid");
        ApiResponse<Void> r = ApiResponse.error(ErrorCode.INVALID_INPUT, List.of(fe));
        assertThat(r.error().fieldErrors()).containsExactly(fe);
    }
}
