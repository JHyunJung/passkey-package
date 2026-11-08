package com.crosscert.passkey.admin.web.format

import java.util.regex.Pattern

private val MAC: Pattern = Pattern.compile("Mac|iPhone|iPad|iPod")

/** 플랫폼별 표기(← admin-ui lib/platform.ts). 서버에서는 User-Agent 로 판단한다. */
object PlatformLabels {

    /** macOS·iOS 인지. platform 이 비어 있으면 userAgent 로 판단한다. 둘 다 없으면 false. */
    @JvmStatic
    fun isMacPlatform(platform: String?, userAgent: String?): Boolean {
        val basis = if (platform != null && platform.isNotEmpty()) platform else (userAgent ?: "")
        return MAC.matcher(basis).find()
    }

    /** 명령 팔레트 단축키 표기. Mac 은 ⌘K, 그 밖은 Ctrl K. */
    @JvmStatic
    fun paletteShortcutLabel(mac: Boolean): String = if (mac) "⌘K" else "Ctrl K"
}
