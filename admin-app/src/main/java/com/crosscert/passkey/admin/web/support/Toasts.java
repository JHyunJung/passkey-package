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

    /**
     * 오류 토스트. {@code detail}(예: {@code HTTP 409 · [CODE] 서버 메시지})과 {@code traceId} 는
     * 토스트의 접힌 "자세히" 안에만 보인다(errorToast.ts). 본문 {@code message} 에는 쉬운 문구만 둔다.
     */
    public static void error(HttpServletResponse res, String message, String detail, String traceId) {
        write(res, "error", message, detail, traceId);
    }

    private static void write(HttpServletResponse res, String kind, String message) {
        write(res, kind, message, null, null);
    }

    private static void write(HttpServletResponse res, String kind, String message, String detail, String traceId) {
        try {
            Map<String, Object> toast = new java.util.LinkedHashMap<>();
            toast.put("kind", kind);
            toast.put("message", message == null ? "" : message);
            if (detail != null && !detail.isEmpty()) toast.put("detail", detail);
            if (traceId != null && !traceId.isEmpty()) toast.put("traceId", traceId);
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(Map.of("toast", toast)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("toast header serialization failed", e);
        }
    }
}
