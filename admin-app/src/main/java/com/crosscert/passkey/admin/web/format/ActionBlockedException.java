package com.crosscert.passkey.admin.web.format;

/**
 * 화면 상태 때문에 지금 실행하지 않은 동작(← describeError.ts 의 ActionBlockedError).
 * {@link ErrorMessages} 가 title·detail 로 그대로 옮긴다.
 */
public class ActionBlockedException extends RuntimeException {

    public static final String DEFAULT_TITLE = "지금은 실행할 수 없습니다";

    private final String title;

    public ActionBlockedException(String detail) {
        this(detail, DEFAULT_TITLE);
    }

    public ActionBlockedException(String detail, String title) {
        super(detail);
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
