package com.crosscert.passkey.admin.web.shell;

/** 브레드크럼 한 칸. {@code href} 가 null 이면 링크 없는 글자(또는 마지막 칸이면 현재 위치). */
public record Crumb(String label, String href) {

    public static Crumb of(String label) {
        return new Crumb(label, null);
    }

    public static Crumb link(String label, String href) {
        return new Crumb(label, href);
    }
}
