package com.crosscert.passkey.admin.web.format;

import java.util.regex.Pattern;

/** 플랫폼별 표기(← admin-ui lib/platform.ts). 서버에서는 User-Agent 로 판단한다. */
public final class PlatformLabels {

    private static final Pattern MAC = Pattern.compile("Mac|iPhone|iPad|iPod");

    private PlatformLabels() {}

    /** macOS·iOS 인지. platform 이 비어 있으면 userAgent 로 판단한다. 둘 다 없으면 false. */
    public static boolean isMacPlatform(String platform, String userAgent) {
        String basis = platform != null && !platform.isEmpty() ? platform : (userAgent == null ? "" : userAgent);
        return MAC.matcher(basis).find();
    }

    /** 명령 팔레트 단축키 표기. Mac 은 ⌘K, 그 밖은 Ctrl K. */
    public static String paletteShortcutLabel(boolean mac) {
        return mac ? "⌘K" : "Ctrl K";
    }
}
