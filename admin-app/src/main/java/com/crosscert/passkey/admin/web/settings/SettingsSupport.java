package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.web.format.ErrorMessages;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import com.crosscert.passkey.admin.web.support.Toasts;
import com.crosscert.passkey.core.api.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;

/** 설정 탭 컨트롤러 공통: PLATFORM 확인(리터럴 탭 경로는 일반 매핑의 판정을 받지 않는다), 오류 응답 매핑. */
final class SettingsSupport {

    static final String EMPTY_VIEW = "admin/fragments/shell/empty :: none";

    private SettingsSupport() {}

    /** PLATFORM 이 아니면 AccessDeniedException(403). REST 는 부르지 않는다. */
    static CurrentAdmin platform(Model model) {
        CurrentAdmin me = AccountTabController.me(model);
        if (!me.getPlatform()) throw new AccessDeniedException("platform only");
        return me;
    }

    /** 예외의 HTTP 상태. 모르는 예외는 500. */
    static int statusOf(RuntimeException e) {
        Integer s = ErrorMessages.describe(e).getHttpStatus();
        return s == null ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : s;
    }

    /**
     * 제목 있는 오류 응답(원본 errorToast(e, title)): 실제 상태 + 오류 토스트 + 빈 본문. 확인 요청이면 토스트가 아니라 다이얼로그 안 인라인(confirmError).
     * 알 수 없는 예외의 원문은 싣지 않는다(ErrorMessages).
     */
    static String fail(HttpServletResponse res, String title, RuntimeException e, boolean confirm) {
        if (e instanceof AccessDeniedException ade) throw ade;
        ErrorMessages.Info info = ErrorMessages.describe(e);
        int status = statusOf(e);
        String detail = "HTTP " + status + (e instanceof BusinessException be ? " · [" + be.getErrorCode().getCode() + "]" : "");
        if (confirm) Toasts.confirmError(res, title + ". " + info.toastMessage(), detail, MDC.get("traceId"));
        else Toasts.errorTitled(res, title, info.toastMessage(), detail, MDC.get("traceId"));
        res.setStatus(status);
        return EMPTY_VIEW;
    }

    /**
     * 화면 상태(낡은 목록·막힌 대상) 때문에 REST 를 부르지 않은 동작: 원본 {@code blocked()} 가 만든 ApiError 처럼 "요청을 처리하지 못했습니다. <사유>" + 409.
     * 확인 요청이면 다이얼로그 안 인라인(confirmError), 아니면 오류 토스트. 본문 없음.
     */
    static String blocked(HttpServletResponse res, String reason, boolean confirm) {
        String msg = "요청을 처리하지 못했습니다. " + reason;
        if (confirm) Toasts.confirmError(res, msg, "HTTP 409", MDC.get("traceId"));
        else Toasts.error(res, msg, "HTTP 409", MDC.get("traceId"));
        res.setStatus(HttpServletResponse.SC_CONFLICT);
        return EMPTY_VIEW;
    }
}
