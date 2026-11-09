package com.crosscert.passkey.admin.web.shell

/** 브레드크럼 한 칸. `href` 가 null 이면 링크 없는 글자(또는 마지막 칸이면 현재 위치). */
data class Crumb(val label: String?, val href: String?) {

    companion object {
        fun of(label: String?): Crumb = Crumb(label, null)

        fun link(label: String?, href: String?): Crumb = Crumb(label, href)
    }
}
