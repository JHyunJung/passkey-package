package com.crosscert.passkey.admin.web.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

/**
 * 서버 → 브라우저 토스트. {@code HX-Trigger: {"toast":{"kind":"…","message":"…"}}} 헤더를 쓰고,
 * app.js(Task 4)가 이 이벤트를 받아 토스트로 띄운다. 같은 응답에 여러 번 부르면 마지막 것이 이긴다.
 *
 * <p>HTTP 헤더 값은 Latin-1 로 전송되므로 한글을 그대로 넣으면 깨진다. 비 ASCII 문자는
 * JSON {@code \\uXXXX} 이스케이프로 내보내고, 브라우저의 JSON 파싱이 원래 문자로 되돌린다.
 */
public final class Toasts {

    public static final String HX_TRIGGER = "HX-Trigger";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();

    private Toasts() {}

    public static void success(HttpServletResponse res, String message) {
        write(res, "success", message);
    }

    public static void error(HttpServletResponse res, String message) {
        write(res, "error", message, null, null);
    }

    /** {@link #error(HttpServletResponse, String, String, String)} 에서 traceId 없이. */
    public static void error(HttpServletResponse res, String message, String detail) {
        error(res, message, detail, null);
    }

    /**
     * 오류 토스트. {@code detail}(예: {@code HTTP 409 · [CODE] 서버 메시지})과 {@code traceId} 는
     * 토스트의 접힌 "자세히" 안에만 보인다(errorToast.ts). 본문 {@code message} 에는 쉬운 문구만 둔다.
     */
    public static void error(HttpServletResponse res, String message, String detail, String traceId) {
        write(res, "error", message, detail, traceId);
    }

    /** 확인 요청(confirmDialog 의 확인 버튼)임을 알리는 요청 헤더. 값은 "1". */
    public static final String CONFIRM_HEADER = "X-Confirm";

    /** 확인 요청의 요청 헤더가 있는지. */
    public static boolean isConfirm(jakarta.servlet.http.HttpServletRequest req) {
        return "1".equals(req.getHeader(CONFIRM_HEADER));
    }

    /**
     * 확인 요청의 실패 안내. {@code HX-Trigger: {"confirmError":{"message","detail","traceId"}}} — 토스트가 아니라 열려 있는
     * confirmDialog 안의 인라인 오류로만 보인다(토스트 중복 없음). message 는 쉬운 문구, detail·traceId 는 선택.
     */
    public static void confirmError(HttpServletResponse res, String message, String detail, String traceId) {
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("message", message == null ? "" : message);
            if (detail != null && !detail.isEmpty()) m.put("detail", detail);
            if (traceId != null && !traceId.isEmpty()) m.put("traceId", traceId);
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(Map.of("confirmError", m)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("confirmError header serialization failed", e);
        }
    }

    /**
     * 제목이 있는 오류 토스트(errorToast.ts: title=무엇이 실패했는지, message=쉬운 문구). 토스트는 제목을 크게, message 를 작게 보인다.
     */
    public static void errorTitled(HttpServletResponse res, String title, String message, String detail, String traceId) {
        write(res, "error", title, message, detail, traceId);
    }

    /** 제목이 있는 성공 토스트(예: NewTenant 의 "Tenant 생성 완료" / "<이름> · WebAuthn 설정으로 이동합니다"). */
    public static void successTitled(HttpServletResponse res, String title, String message) {
        write(res, "success", title, message, null, null);
    }

    /**
     * 저장 성공: 제목 있는 성공 토스트와 함께 {@code saved} 이벤트를 보낸다({@code HX-Trigger: {"saved":true,"toast":{…}}}).
     * app.js 가 {@code saved} 로 저장 안 한 변경 표시({@code $store.dirty})를 지운다.
     */
    public static void successSaved(HttpServletResponse res, String title, String message) {
        try {
            Map<String, Object> toast = new java.util.LinkedHashMap<>();
            toast.put("kind", "success");
            if (title != null && !title.isEmpty()) toast.put("title", title);
            toast.put("message", message == null ? "" : message);
            Map<String, Object> events = new java.util.LinkedHashMap<>();
            events.put("saved", true);
            events.put("toast", toast);
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(events));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("saved toast header serialization failed", e);
        }
    }

    /** 제목이 있는 경고 토스트(예: MfaChallenge 의 "복구 코드를 사용했습니다." / "남은 복구 코드: N개"). */
    public static void warnTitled(HttpServletResponse res, String title, String message) {
        write(res, "warn", title, message, null, null);
    }

    private static void write(HttpServletResponse res, String kind, String message) {
        write(res, kind, message, null, null);
    }

    private static void write(HttpServletResponse res, String kind, String message, String detail, String traceId) {
        write(res, kind, null, message, detail, traceId);
    }

    private static void write(HttpServletResponse res, String kind, String title, String message, String detail, String traceId) {
        try {
            Map<String, Object> toast = new java.util.LinkedHashMap<>();
            toast.put("kind", kind);
            if (title != null && !title.isEmpty()) toast.put("title", title);
            toast.put("message", message == null ? "" : message);
            if (detail != null && !detail.isEmpty()) toast.put("detail", detail);
            if (traceId != null && !traceId.isEmpty()) toast.put("traceId", traceId);
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(Map.of("toast", toast)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("toast header serialization failed", e);
        }
    }
}
