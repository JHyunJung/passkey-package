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
 * 설정 › 시스템 탭. PLATFORM 전용 — `SystemInfoController.get()`.
 * 일반 탭 매핑(`/admin/_/settings/tab/{tab}`)보다 이 리터럴 경로가 우선한다. RP_ADMIN 이 직접 부르면 403(RP_ADMIN 의 탭 줄에는 이 탭이 없다).
 * 조회 실패는 200 + loadError 조각(다시 시도 = 이 조각을 탭 영역에 다시 받기). 알 수 없는 예외의 원문은 싣지 않는다(ErrorMessages).
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
        // nullable 로 받는다: data() 는 Java 플랫폼 타입이라 non-null 로 선언하면 대입 자리(try 안)에 null 검사가 생겨 null 이
        // 아래 catch(로드 실패 카드)로 간다. 지금은 null 이면 아래 `v!!`(try 밖)에서 NPE — WebExceptionAdvice 의 500.
        // get() 은 data 를 늘 채운다.
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
        // `!!`: 위 선언 주석 참고.
        model.addAttribute("info", toInfo(v!!))
        return VIEW
    }

    companion object {
        /** internal: 같은 모듈 테스트(SystemInfoTabControllerTest)가 읽는다. */
        internal const val TAB_PATH = "/admin/_/settings/tab/system"

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
            val hostEnv = v.host?.environment // host·environment 어느 쪽이 null 이어도 ""
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
         * internal: 같은 모듈 테스트(SystemInfoTabControllerTest)가 부른다.
         */
        internal fun deployedKst(iso: String?): String? {
            if (iso == null || iso.isEmpty()) return null
            val t = KstFormat.parse(iso)
            return if (t == null) null else KstFormat.kstDateTimeMin(t) + " KST"
        }
    }
}
