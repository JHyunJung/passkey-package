package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.core.api.BusinessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;

/**
 * 사용자에게 보여 줄 쉬운 오류 문구(← admin-ui lib/describeError.ts, lib/transientError.ts).
 *
 * <p>원본의 ApiError 는 서버 응답의 HTTP 상태라서, 서버 측에서는 {@link BusinessException}·
 * {@link ResponseStatusException}·{@link AccessDeniedException}·Spring MVC {@link ErrorResponse} 의 상태로 옮긴다.
 * fetch 의 TypeError(네트워크)는 {@link IOException}, RequestTimeoutError 는 {@link TimeoutException} 에 대응한다.
 */
public final class ErrorMessages {

    /** title: 한 줄 요약, detail: 쉬운 설명(또는 서버 원문). detailIsRaw 이면 detail 이 서버가 준 원문이다. */
    public record Info(String title, String detail, Integer httpStatus, String code, boolean detailIsRaw) {

        /** 토스트 한 줄: 원문 detail 은 제외하고 쉬운 문구만 "제목. 설명" 으로 잇는다. */
        public String toastMessage() {
            return detail == null || detail.isEmpty() || detailIsRaw ? title : title + ". " + detail;
        }
    }

    private ErrorMessages() {}

    /** 상태 코드와 서버 메시지로 문구를 정한다. */
    public static Info describe(int status, String serverMessage, String code) {
        if (status >= 500) return friendly("서버가 잠시 응답하지 않습니다", "저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.", status, code);
        if (status == 403) return friendly("권한이 없습니다", "이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.", status, code);
        if (status == 429) return friendly("요청이 너무 많습니다", "잠시 후 다시 시도하세요.", status, code);
        if (status == 404) return friendly("찾을 수 없습니다", "삭제되었거나 주소가 바뀌었을 수 있습니다.", status, code);
        return new Info("요청을 처리하지 못했습니다", serverMessage, status, code, true);
    }

    public static Info describe(int status, String serverMessage) {
        return describe(status, serverMessage, null);
    }

    /**
     * 알려진 예외는 상태별 문구, 알 수 없는 예외(DataAccessException·NPE 등 서버 내부 예외)는 5xx 문구("서버가 잠시 응답하지 않습니다" + HTTP 500)다.
     * 내부 예외의 원문 메시지(SQL·클래스 이름 등)는 어떤 화면(LoadError·카드·복사 텍스트)에도 싣지 않는다.
     */
    public static Info describe(Throwable e) {
        Info known = known(e);
        return known != null ? known : describe(500, null, null);
    }

    /** 알려진 예외의 문구. 알 수 없는 예외는 null. */
    private static Info known(Throwable e) {
        if (e instanceof BusinessException be) {
            return describe(be.getErrorCode().getStatus().value(), be.getMessage(), be.getErrorCode().getCode());
        }
        if (e instanceof ResponseStatusException rse) {
            return describe(rse.getStatusCode().value(), rse.getReason() != null ? rse.getReason() : rse.getMessage());
        }
        if (e instanceof AccessDeniedException) return describe(403, e.getMessage());
        if (e instanceof ErrorResponse er) return describe(er.getStatusCode().value(), e.getMessage());
        if (e instanceof ActionBlockedException ab) return new Info(ab.getTitle(), ab.getMessage(), null, null, false);
        if (e instanceof TimeoutException) {
            return friendly("서버가 잠시 응답하지 않습니다", "응답이 오지 않아 요청을 중단했습니다. 잠시 후 다시 시도하세요.", null, null);
        }
        if (e instanceof IOException || e instanceof UncheckedIOException) {
            return friendly("네트워크에 연결할 수 없습니다", "연결 상태를 확인한 뒤 다시 시도하세요.", null, null);
        }
        return null;
    }

    /**
     * 화면 예외 처리기용. 이미 정해진 응답 상태(예: 처리기에서 500 으로 정한 예기치 못한 예외)가 있으면
     * 그 상태로 문구를 정한다. 단 {@link ActionBlockedException} 은 자기 문구를 쓴다.
     */
    public static Info describe(Throwable e, int status) {
        if (e instanceof ActionBlockedException) return describe(e);
        String code = e instanceof BusinessException be ? be.getErrorCode().getCode() : null;
        // 원문 표시(4xx 의 detail)는 업무 규칙 문구만 — JDK·라이브러리·Spring MVC 표준 예외의 원문(예: "Invalid UUID string", 타입 변환 실패)은 뺀다.
        return describe(status, serverMessage(e, status), code);
    }

    /** 화면에 보여도 되는 서버 메시지: BusinessException 의 메시지, 4xx ResponseStatusException 의 reason. 그 밖은 null. */
    private static String serverMessage(Throwable e, int status) {
        if (e instanceof BusinessException be) return be.getMessage();
        if (e instanceof ResponseStatusException rse && status < 500) return rse.getReason();
        return null;
    }

    /**
     * 오류 원문(rawErrorDetail): {@code HTTP 상태 · [코드] 서버 메시지}. 화면 예외 처리기(WebExceptionAdvice)와 RP 전환 실패(ShellController)가 같이 쓴다.
     * 서버 메시지는 업무 규칙이 정한 문구만 붙인다 — {@link BusinessException} 의 메시지와 4xx {@link ResponseStatusException} 의 reason.
     * Spring MVC 표준 예외({@link ErrorResponse}·타입 변환 실패 등)·JDK 예외(IllegalArgumentException "Invalid UUID string" 등)·
     * 권한 거부·예기치 못한 5xx 의 원문은 싣지 않는다(상태와 코드만).
     */
    public static String rawDetail(Throwable e, int status, Info info) {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (e instanceof BusinessException && info != null && info.code() != null) sb.append(" · [").append(info.code()).append(']');
        String msg = serverMessage(e, status);
        if (msg != null && !msg.isEmpty()) sb.append(' ').append(msg);
        return sb.toString();
    }

    /**
     * 네트워크 오류, 시간 초과 또는 5xx — 요청이 처리되지 않았을 수 있어 "잠시 후 다시 시도"로 안내할 실패.
     * 4xx 는 아니다.
     */
    public static boolean isTransient(Throwable e) {
        if (e == null) return false;
        if (e instanceof IOException || e instanceof UncheckedIOException || e instanceof TimeoutException) return true;
        Info k = known(e);
        Integer s = k == null ? null : k.httpStatus();
        return s != null && s >= 500;
    }

    private static Info friendly(String title, String detail, Integer status, String code) {
        return new Info(title, detail, status, code, false);
    }
}
