package com.crosscert.passkey.admin.web.format

/**
 * 화면 상태 때문에 지금 실행하지 않은 동작(← describeError.ts 의 ActionBlockedError).
 * [ErrorMessages] 가 title·detail 로 그대로 옮긴다.
 */
open class ActionBlockedException(detail: String?, val title: String?) : RuntimeException(detail) {

    constructor(detail: String?) : this(detail, DEFAULT_TITLE)

    companion object {
        const val DEFAULT_TITLE = "지금은 실행할 수 없습니다"
    }
}
