package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.system.SystemInfoView
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaTrim
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

private val log = LoggerFactory.getLogger(SystemInfoTabController::class.java)

private const val VIEW = "admin/settings/system :: content"

/**
 * 설정 › 시스템 탭(← pages/settings/SystemInfoTab.tsx, api/systemInfo.ts). PLATFORM 전용 — `SystemInfoController.get()`.
 * 일반 탭 매핑(`/admin/_/settings/tab/{tab}`)보다 이 리터럴 경로가 우선한다. RP_ADMIN 이 직접 부르면 403(원본엔 탭 자체가 없다).
 * 조회 실패는 200 + LoadError(다시 시도 = 이 조각을 탭 영역에 다시 받기). 알 수 없는 예외의 원문은 싣지 않는다(ErrorMessages).
 */
@Controller
class SystemInfoTabController(private val systemInfo: SystemInfoController) {

    /** 상태 표시(STATUS): 배지 톤·라벨·점 색. */
    data class Status(val tone: String?, val label: String?, val dot: String?) {
        internal companion object {
            fun of(s: String?): Status {
                if ("DOWN" == s) return Status("bad", "중단", "var(--bad)")
                if ("DEGRADED" == s) return Status("warn", "저하", "var(--warn-dot)")
                return Status("ok", "정상", "var(--ok)")
            }
        }
    }

    data class Comp(val name: String?, val version: String?, val note: String?, val status: Status?)

    data class Info(
        val overall: Status?,
        val overallHint: String?,
        val serverVersion: String?,
        val deployedHint: String?,
        val deployed: String?,
        val uptime: String?,
        val components: List<Comp>?,
        val environment: String?,
        val profiles: List<String>?,
    )

    @GetMapping(TAB_PATH)
    fun tab(model: Model): String {
        val me = AccountTabController.me(model)
        if (!me.platform) throw AccessDeniedException("platform only")
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH))
        // nullable: 원 Java 는 null data 를 try 밖 toInfo 에서 NPE(500) — non-null 선언이면 try 안 검사로 바뀐다.
        val v: SystemInfoView?
        try {
            v = systemInfo.get().data()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[settings] system info load failed: {}", e.javaClass.simpleName)
            model.addAttribute("loadError", e)
            model.addAttribute("info", null)
            return VIEW
        }
        model.addAttribute("loadError", null)
        // !!: 원 Java 는 toInfo 첫 줄 v.getComponents() 에서 NPE(try 밖) — 같은 자리.
        model.addAttribute("info", toInfo(v!!))
        return VIEW
    }

    companion object {
        /** 같은 패키지 Java 테스트(SystemInfoTabControllerTest)가 읽는다 — const 라 정적 필드로 남는다. */
        const val TAB_PATH = "/admin/_/settings/tab/system"

        private fun toInfo(v: SystemInfoView): Info {
            val raw: List<SystemInfoView.Component> = v.components ?: java.util.List.of()
            val comps = ArrayList<Comp>()
            var ok: Long = 0
            var down = false
            var degraded = false
            for (c in raw) {
                if ("OK" == c.status) ok++
                if ("DOWN" == c.status) down = true
                if ("DEGRADED" == c.status) degraded = true
                val note = c.note
                comps.add(Comp(c.name, c.version, if (note == null || note.isEmpty()) null else note, Status.of(c.status)))
            }
            // 전체 상태 = 가장 나쁜 컴포넌트 상태.
            val overall = Status.of(if (down) "DOWN" else if (degraded) "DEGRADED" else "OK")
            val deployed = deployedKst(v.deployedAt)
            val hostEnv = v.host?.environment // host 가 null 이어도 "" (원 Java 의 두 null 검사)
            val env = hostEnv ?: ""
            val profiles = env.split(",").map { it.javaTrim() }.filter { it.isNotEmpty() }
            return Info(
                overall, "컴포넌트 " + ok + " / " + raw.size, v.serverVersion,
                if (deployed != null) "$deployed 배포" else "배포 시각 정보 없음", deployed,
                if (v.uptimeDays != null) v.uptimeDays.toString() + "일" else "—", comps, env, profiles,
            )
        }

        /**
         * 해석 가능한 시각만 KST 로. 없거나 깨진 값은 null.
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(SystemInfoTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun deployedKst(iso: String?): String? {
            if (iso == null || iso.isEmpty()) return null
            val t = KstFormat.parse(iso)
            return if (t == null) null else KstFormat.kstDateTimeMin(t) + " KST"
        }
    }
}
