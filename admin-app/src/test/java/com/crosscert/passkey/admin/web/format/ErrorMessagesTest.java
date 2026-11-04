package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ← admin-ui/src/lib/describeError.test.ts (describeError / ActionBlockedError / josa), lib/transientError.test.ts.
 * ApiError(httpStatus) → 서버 측 상태를 가진 예외(ResponseStatusException·BusinessException),
 * TypeError('Failed to fetch') → IOException.
 */
class ErrorMessagesTest {

    private static ResponseStatusException status(int s) {
        return new ResponseStatusException(HttpStatusCode.valueOf(s), "HTTP " + s);
    }

    // ── describeError ──
    @Test
    @DisplayName("5xx 는 서버 일시 오류")
    void fiveXxIsTransientServerError() {
        ErrorMessages.Info i = ErrorMessages.describe(status(503));
        assertThat(i.title()).isEqualTo("서버가 잠시 응답하지 않습니다");
        assertThat(i.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("fetch 실패(TypeError)는 네트워크 오류")
    void fetchFailureIsNetworkError() {
        assertThat(ErrorMessages.describe(new ConnectException("Failed to fetch")).title()).isEqualTo("네트워크에 연결할 수 없습니다");
    }

    @Test
    @DisplayName("403 은 권한 없음, 404 는 찾을 수 없음")
    void forbiddenAndNotFound() {
        assertThat(ErrorMessages.describe(status(403)).title()).isEqualTo("권한이 없습니다");
        assertThat(ErrorMessages.describe(status(404)).title()).isEqualTo("찾을 수 없습니다");
    }

    @Test
    @DisplayName("429 는 요청 과다 — 잠시 후 다시 시도")
    void tooManyRequests() {
        ErrorMessages.Info i = ErrorMessages.describe(status(429));
        assertThat(i.title()).isEqualTo("요청이 너무 많습니다");
        assertThat(i.detail()).isEqualTo("잠시 후 다시 시도하세요.");
    }

    @Test
    @DisplayName("그 밖의 ApiError 는 서버 메시지를 detail 로")
    void otherErrorsUseServerMessageAsDetail() {
        ErrorMessages.Info i = ErrorMessages.describe(new BusinessException(ErrorCode.INVALID_INPUT, "slug 형식이 올바르지 않습니다"));
        assertThat(i.title()).isEqualTo("요청을 처리하지 못했습니다");
        assertThat(i.detail()).isEqualTo("slug 형식이 올바르지 않습니다");
        assertThat(i.code()).isEqualTo(ErrorCode.INVALID_INPUT.getCode());
    }

    // ── ActionBlockedError ──
    @Test
    @DisplayName("지금 실행하지 않은 이유를 쉬운 문구 그대로 옮긴다(알 수 없는 오류가 아니다)")
    void actionBlockedCarriesTheReasonAsIs() {
        ErrorMessages.Info i = ErrorMessages.describe(new ActionBlockedException("목록을 새로 고친 뒤 다시 시도하세요."));
        assertThat(i.title()).isEqualTo("지금은 실행할 수 없습니다");
        assertThat(i.detail()).isEqualTo("목록을 새로 고친 뒤 다시 시도하세요.");
        assertThat(i.httpStatus()).isNull();
    }

    // ── josa (describeError.test.ts 안의 describe) ──
    @Test
    @DisplayName("받침 유무로 을/를, 이/가")
    void josaByBatchim() {
        assertThat(Josa.eulReul("목록")).isEqualTo("을");
        assertThat(Josa.eulReul("정보")).isEqualTo("를");
        assertThat(Josa.iGa("키")).isEqualTo("가");
        assertThat(Josa.iGa("목록")).isEqualTo("이");
    }

    @Test
    @DisplayName("한글이 아니면 을/이 기본")
    void josaDefaultsForNonHangul() {
        assertThat(Josa.eulReul("Credential")).isEqualTo("을");
        assertThat(Josa.iGa("API")).isEqualTo("이");
    }

    // ── transientError ──
    @Test
    @DisplayName("fetch 실패(TypeError)와 5xx 는 일시 오류")
    void networkAndFiveXxAreTransient() {
        assertThat(ErrorMessages.isTransient(new IOException("Failed to fetch"))).isTrue();
        assertThat(ErrorMessages.isTransient(status(500))).isTrue();
        assertThat(ErrorMessages.isTransient(status(503))).isTrue();
    }

    @Test
    @DisplayName("4xx 와 일반 Error 는 일시 오류가 아니다")
    void fourXxAndPlainErrorsAreNotTransient() {
        assertThat(ErrorMessages.isTransient(new BusinessException(ErrorCode.INVALID_INPUT, "bad"))).isFalse();
        assertThat(ErrorMessages.isTransient(status(404))).isFalse();
        assertThat(ErrorMessages.isTransient(new RuntimeException("boom"))).isFalse();
        assertThat(ErrorMessages.isTransient(null)).isFalse();
    }

    @Test
    @DisplayName("알 수 없는 서버 예외는 5xx 문구(HTTP 500)이고 원문 메시지를 싣지 않는다")
    void unknownExceptionNeverExposesMessage() {
        for (Throwable t : new Throwable[] {new RuntimeException(), new IllegalStateException("SELECT secret FROM users"), new NullPointerException("x.y")}) {
            ErrorMessages.Info i = ErrorMessages.describe(t);
            assertThat(i.title()).isEqualTo("서버가 잠시 응답하지 않습니다");
            assertThat(i.httpStatus()).isEqualTo(500);
            assertThat(i.detail()).doesNotContain("secret").doesNotContain("x.y");
            assertThat(i.detailIsRaw()).isFalse();
        }
    }

    @Test
    @DisplayName("rawDetail(공통): BusinessException 메시지·4xx ResponseStatusException reason 만 붙이고 ErrorResponse·타입 변환·JDK·권한 거부·5xx 원문은 빼고 상태만")
    void rawDetailOnlySafeMessages() throws Exception {
        BusinessException be = new BusinessException(ErrorCode.INVALID_INPUT, "slug 형식이 올바르지 않습니다");
        assertThat(ErrorMessages.rawDetail(be, 400, ErrorMessages.describe(be)))
                .isEqualTo("HTTP 400 · [" + ErrorCode.INVALID_INPUT.getCode() + "] slug 형식이 올바르지 않습니다");
        ResponseStatusException rse = new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "이미 활성");
        assertThat(ErrorMessages.rawDetail(rse, 409, ErrorMessages.describe(rse))).isEqualTo("HTTP 409 이미 활성");
        ResponseStatusException rse5 = new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "pool exhausted");
        assertThat(ErrorMessages.rawDetail(rse5, 503, ErrorMessages.describe(rse5))).isEqualTo("HTTP 503");
        var missing = new org.springframework.web.bind.MissingServletRequestParameterException("tenantId", "String");
        assertThat(ErrorMessages.rawDetail(missing, 400, ErrorMessages.describe(missing, 400))).isEqualTo("HTTP 400");
        var mismatch = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException("x", java.util.UUID.class, "id", null,
                new IllegalArgumentException("Invalid UUID string: x"));
        assertThat(ErrorMessages.rawDetail(mismatch, 400, ErrorMessages.describe(mismatch, 400))).isEqualTo("HTTP 400");
        assertThat(ErrorMessages.describe(mismatch, 400).detail()).isNull(); // 오류 화면 설명에도 원문 없음
        var iae = new IllegalArgumentException("Invalid UUID string: x");
        assertThat(ErrorMessages.rawDetail(iae, 400, ErrorMessages.describe(iae, 400))).isEqualTo("HTTP 400");
        var denied = new org.springframework.security.access.AccessDeniedException("platform operator only");
        assertThat(ErrorMessages.rawDetail(denied, 403, ErrorMessages.describe(denied))).isEqualTo("HTTP 403");
        assertThat(ErrorMessages.rawDetail(new IllegalStateException("jdbc:secret"), 500, ErrorMessages.describe(500, null))).isEqualTo("HTTP 500");
    }
}
