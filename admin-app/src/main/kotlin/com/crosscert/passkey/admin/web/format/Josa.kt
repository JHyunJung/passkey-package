package com.crosscert.passkey.admin.web.format

/** 한글 조사 선택(← admin-ui lib/josa.ts). */
object Josa {

    /** 받침 유무. 한글 음절이 아니면 null. 빈 문자열은 원본(NaN 비교)과 같이 받침 있음으로 본다. */
    private fun hasBatchim(word: String?): Boolean? {
        val t = word?.javaStrip() ?: ""
        if (t.isEmpty()) return true
        val code = t[t.length - 1].code - 0xac00
        if (code < 0 || code > 11171) return null
        return code % 28 != 0
    }

    /** 목적격 조사. 한글이 아니면 '을'. */
    @JvmStatic
    fun eulReul(w: String?): String = if (hasBatchim(w) == false) "를" else "을"

    /** 주격 조사. 한글이 아니면 '이'. */
    @JvmStatic
    fun iGa(w: String?): String = if (hasBatchim(w) == false) "가" else "이"

    /**
     * 템플릿용 일반형. `pair` 는 "받침 있을 때/받침 없을 때"(예: "을/를", "이/가", "은/는").
     * 한글이 아니면 앞쪽을 쓴다.
     */
    @JvmStatic
    fun pick(word: String?, pair: String): String {
        val i = pair.indexOf('/')
        if (i < 0) return pair
        return if (hasBatchim(word) == false) pair.substring(i + 1) else pair.substring(0, i)
    }
}
