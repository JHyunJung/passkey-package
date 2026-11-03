package com.crosscert.passkey.admin.web.format;

/** 한글 조사 선택(← admin-ui lib/josa.ts). */
public final class Josa {

    private Josa() {}

    /** 받침 유무. 한글 음절이 아니면 null. 빈 문자열은 원본(NaN 비교)과 같이 받침 있음으로 본다. */
    private static Boolean hasBatchim(String word) {
        String t = word == null ? "" : word.strip();
        if (t.isEmpty()) return true;
        int code = t.charAt(t.length() - 1) - 0xac00;
        if (code < 0 || code > 11171) return null;
        return code % 28 != 0;
    }

    /** 목적격 조사. 한글이 아니면 '을'. */
    public static String eulReul(String w) {
        return Boolean.FALSE.equals(hasBatchim(w)) ? "를" : "을";
    }

    /** 주격 조사. 한글이 아니면 '이'. */
    public static String iGa(String w) {
        return Boolean.FALSE.equals(hasBatchim(w)) ? "가" : "이";
    }

    /**
     * 템플릿용 일반형. {@code pair} 는 "받침 있을 때/받침 없을 때"(예: "을/를", "이/가", "은/는").
     * 한글이 아니면 앞쪽을 쓴다.
     */
    public static String pick(String word, String pair) {
        int i = pair.indexOf('/');
        if (i < 0) return pair;
        return Boolean.FALSE.equals(hasBatchim(word)) ? pair.substring(i + 1) : pair.substring(0, i);
    }
}
