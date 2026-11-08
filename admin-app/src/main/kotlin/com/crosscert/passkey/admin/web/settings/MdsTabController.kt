package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.mds.MdsAdminController
import com.crosscert.passkey.admin.mds.MdsHistoryView
import com.crosscert.passkey.admin.mds.MdsSchedulerService
import com.crosscert.passkey.admin.mds.MdsStatusView
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.Labels
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(MdsTabController::class.java)

private const val VIEW = "admin/settings/mds :: tab"
private const val WARN_VIEW = "admin/settings/mds :: reloadWarning"

/** 원본 MDS_HISTORY_STRIP_LIMIT, TABLE_ROWS. */
private const val HISTORY_LIMIT = 30
private const val TABLE_ROWS = 6
private const val ERROR_PREVIEW_MAX = 80

/** 서버 시드(version 0, 1970-01-01) 판정: 2000년 이전은 "한 번도 동기화되지 않음". */
private val SEED_CUTOFF: Instant = Instant.parse("2000-01-01T00:00:00Z")

/** Java `String.replaceAll("\\s+", " ")` 와 같은 정규식(플래그 없음). */
private val WHITESPACE: Pattern = Pattern.compile("\\s+")

/**
 * 설정 › MDS Status 탭(← pages/settings/MdsStatusTab.tsx, api/mdsStatus.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다(RP_ADMIN → 403, REST 미호출).
 * 키 관리(`KeyMgmtController`)는 원본 설정 화면 어디에도 없어 이 탭에 두지 않는다.
 *
 * 상태(`MdsAdminController.status`)와 이력(`history(30)`)을 함께 읽고 둘 다 끝난 뒤에 그린다. 상태 조회 실패는 로드 실패(LoadError),
 * 이력 조회 실패는 "이력 없음"이 아니라 이력 카드만 LoadError 다(이 경우 헤드라인은 중립 "이력 확인 불가").
 *
 * 즉시 갱신(`POST …/mds/sync` → `MdsAdminController.sync`): 긴 작업이라 요청 시간 제한 120초(원본 LONG_TIMEOUT_MS)이고, 요청 중에는 버튼이 비활성 +
 * hx-sync=this:drop 라 겹쳐 보내지 못한다(원본 syncingRef). 결과 SYNCED/SKIPPED/FAILED 별 토스트 후 탭을 다시 읽어 바꾼다. 서버 원문(error)은 싣지 않는다.
 * 폴링은 없다(원본도 없음).
 */
@Controller
class MdsTabController(
    private val mds: MdsAdminController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    data class Trust(val mode: String?, val label: String?, val description: String?)

    data class Rate(val percent: String?, val counts: String?)

    data class Run(
        val time: String?,
        val version: String?,
        val failed: Boolean,
        val changeText: String?,
        val changeTitle: String?,
        val tone: String?,
        val label: String?,
        val ms: String?,
    )

    data class View(
        val headTone: String?,
        val headTitle: String?,
        val sub: String?,
        val version: String?,
        val anchors: String?,
        val lastSync: String?,
        val nextUpdate: String?,
        val rate: Rate?,
        val trust: Trust?,
        val historyOk: Boolean,
        val runs: List<Run>?,
        val strip: List<Boolean?>?,
        val stripLabel: String?,
    )

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    fun tab(model: Model): String {
        SettingsSupport.platform(model)
        load(model, false)
        return VIEW
    }

    @PostMapping(SYNC_PATH)
    fun sync(res: HttpServletResponse, model: Model): String {
        SettingsSupport.platform(model)
        // nullable: 원 Java 는 null data 를 try 밖 r.getStatus() 에서 NPE(500) — non-null 선언이면 try 안 검사로 바뀐다.
        val r: MdsSchedulerService.SyncResult?
        try {
            r = mds.sync().data()
        } catch (e: RuntimeException) {
            return SettingsSupport.fail(res, "MDS 갱신 실패", e, false) // 오류 상태 + 토스트, 본문 없음(탭은 그대로)
        }
        // !!: 원 Java 의 r.getStatus() NPE 와 같은 자리(try 밖).
        if ("SYNCED" == r!!.status) {
            Toasts.successTitled(res, "MDS 즉시 갱신 완료", "버전 " + r.version)
        } else if ("SKIPPED" == r.status) {
            Toasts.warnTitled(res, "MDS 갱신 건너뜀", "다른 인스턴스가 동기화 중입니다.")
        } else {
            // 서버 원문(r.error())은 화면에 싣지 않는다.
            Toasts.errorTitled(res, "MDS 갱신 실패", "갱신하지 못했습니다. 잠시 후 다시 시도하세요.", null, null)
        }
        // 갱신은 성공했다. 다시 읽기(상태·이력 모두)에 실패하면 화면은 그대로 두고 경고만 보인다(원본: 이전 상태·이력을 유지 + 다시 시도).
        if (load(model, true)) return VIEW
        res.setHeader("HX-Reswap", "none")
        model.addAttribute(
            "reloadError",
            if (model.getAttribute("loadError") != null) model.getAttribute("loadError") else model.getAttribute("historyError"),
        )
        return WARN_VIEW
    }

    // ── 읽기 ────────────────────────────────────────────────

    /** strict 면 이력 조회 실패도 실패로 본다(false). 아니면 상태만 못 읽을 때 false. */
    private fun load(model: Model, strict: Boolean): Boolean {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH))
        model.addAttribute("syncAttrs", syncAttrs())
        model.addAttribute("loadError", null)
        model.addAttribute("historyError", null)
        model.addAttribute("v", null)
        // nullable: 원 Java 는 null data 를 try 밖 view() 에서 NPE — non-null 선언이면 try 안 검사로 바뀐다.
        val status: MdsStatusView?
        try {
            status = mds.status().data()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[settings] mds status load failed: {}", e.javaClass.simpleName)
            model.addAttribute("loadError", e)
            return false
        }
        var history: List<MdsHistoryView>? = null
        try {
            history = mds.history(HISTORY_LIMIT).data()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[settings] mds history load failed: {}", e.javaClass.simpleName)
            model.addAttribute("historyError", e)
        }
        // !!: 원 Java 는 view() 첫 줄 s.getVersion() 에서 NPE(try 밖) — 같은 자리.
        model.addAttribute("v", view(status!!, history, Instant.now(clock)))
        return history != null || !strict
    }

    companion object {
        /** 같은 패키지 Java 테스트(MdsTabControllerTest)가 읽는다 — const 라 정적 필드로 남는다. */
        const val TAB_PATH = "/admin/_/settings/tab/mds"
        const val SYNC_PATH = "/admin/_/settings/mds/sync"

        private fun syncAttrs(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-post"] = SYNC_PATH
            m["hx-target"] = "#mds-root"
            m["hx-swap"] = "outerHTML"
            m["hx-sync"] = "this:drop"
            m["hx-disabled-elt"] = "this"
            return m
        }

        // ── 화면 모델 계산(원본 MdsStatusTab 본문) ───────────────

        private fun view(s: MdsStatusView, history: List<MdsHistoryView>?, now: Instant): View {
            val historyOk = history != null
            val h: List<MdsHistoryView> = history ?: java.util.List.of()
            val neverSynced = s.version == 0L || isSeedTime(s.fetchedAt)
            val next = s.nextUpdate
            val nextUpdate = if (next != null && !isSeedDate(next)) next else null
            val versionText = if (!neverSynced) "#" + s.version else "—"
            val latest = if (h.isEmpty()) null else h[0]
            // 헤드라인의 기준은 상태. 시드뿐이거나 이력을 못 읽었으면 중립, 아니면 가장 최근 실행이 실패일 때만 실패로 표시한다.
            val tone = if (neverSynced || !historyOk) "neutral" else if (latest != null && "FAILED" == latest.status) "bad" else "ok"
            val title = if ("bad" == tone) {
                "동기화 실패 · BLOB $versionText"
            } else if ("neutral" == tone) {
                if (!historyOk && !neverSynced) "이력 확인 불가 · BLOB $versionText" else "동기화 이력 없음"
            } else {
                "정상 · BLOB $versionText"
            }
            val sub = "FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다" + (if (nextUpdate != null) " · 다음 갱신 $nextUpdate 03:00 KST" else "")
            val sr = s.successRate30d
            val rate = if (sr != null && sr.total > 0) {
                Rate(Math.round(sr.ok * 100.0 / sr.total).toString() + "%", sr.ok.toString() + " / " + sr.total)
            } else {
                null
            }
            val runs = ArrayList<Run>()
            val strip = ArrayList<Boolean?>()
            var fail: Long = 0
            for (r in h.subList(0, Math.min(h.size, TABLE_ROWS))) runs.add(run(r))
            for (i in h.size - 1 downTo 0) strip.add(stripValue(h[i].status))
            for (r in h) if ("FAILED" == r.status) fail++
            val stripLabel = "최근 " + h.size + "회 동기화 결과 · 실패 " + fail + "회"
            return View(
                tone, title, sub, versionText, s.trustAnchorCount.toString(),
                if (neverSynced) "—" else timeAgo(s.fetchedAt, now), nextUpdate, rate, trust(s.trustMode), historyOk, runs, strip, stripLabel,
            )
        }

        private fun stripValue(status: String?): Boolean? = when (status ?: "") {
            "SYNCED" -> true
            "FAILED" -> false
            else -> null // 건너뜀·모르는 값: 기록 없음 칸
        }

        private fun run(r: MdsHistoryView): Run {
            val status = r.status ?: ""
            val tone = when (status) {
                "SYNCED" -> "ok"
                "FAILED" -> "bad"
                else -> "neutral"
            }
            val label = when (status) {
                "SYNCED" -> "동기화됨"
                "SKIPPED" -> "건너뜀"
                "FAILED" -> "실패"
                else -> status
            }
            val errorMessage = r.errorMessage
            val failed = "FAILED" == status && errorMessage != null && !errorMessage.javaIsBlank()
            // failed 이면 errorMessage 는 null 이 아니다(위 조건 — 컴파일러가 스마트 캐스트한다).
            val change = if (failed) failureReason(errorMessage) else r.changeSummary ?: "—"
            return Run(
                if (r.startedAt == null) "—" else KstFormat.kstDateTimeMin(r.startedAt), if (r.version != null) "#" + r.version else "—",
                failed, change, if (failed) flat(errorMessage) else null, tone, label, if (r.durationMs != null) r.durationMs.toString() + "ms" else "—",
            )
        }

        /**
         * 실패 사유(서버 errorMessage)를 한 줄 평문으로. 길면 줄이고(80자) 전체는 title 툴팁(템플릿).
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(MdsTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun failureReason(message: String): String {
            val flat = WHITESPACE.matcher(message).replaceAll(" ").javaTrim()
            return if (flat.length > ERROR_PREVIEW_MAX) flat.substring(0, ERROR_PREVIEW_MAX - 1) + "…" else flat
        }

        private fun flat(message: String?): String =
            if (message == null) "" else WHITESPACE.matcher(message).replaceAll(" ").javaTrim()

        /** 신뢰 모드: 사람이 읽는 라벨 + 설명, 원문은 툴팁. 없으면 null(기본값을 지어내지 않는다). 모르는 값은 원문을 라벨로. */
        private fun trust(mode: String?): Trust? {
            if (mode == null || mode.isEmpty()) return null
            val known: Labels.MdsTrustMode? = Labels.MDS_TRUST_MODES[mode]
            return if (known != null) Trust(mode, known.label, known.description) else Trust(mode, mode, null)
        }

        /** public `@JvmStatic`: 같은 패키지 Java 테스트(MdsTabControllerTest)가 부른다. */
        @JvmStatic
        fun isSeedTime(iso: String?): Boolean {
            if (iso == null || iso.isEmpty()) return true
            val t = KstFormat.parse(iso)
            return t == null || t.isBefore(SEED_CUTOFF)
        }

        private fun isSeedDate(date: String): Boolean {
            try {
                return LocalDate.parse(date).year < 2000
            } catch (e: RuntimeException) {
                return isSeedTime(date)
            }
        }

        /**
         * 원본 timeAgo(초 단위): n초 전 / n분 전 / n시간 전 / n일 전.
         * public `@JvmStatic`: 같은 패키지 Java 테스트(MdsTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun timeAgo(iso: String?, now: Instant): String {
            val t = KstFormat.parse(iso) ?: return "—"
            val s = Math.max(0L, (now.toEpochMilli() - t.toEpochMilli()) / 1000)
            if (s < 60) return s.toString() + "초 전"
            val m = s / 60
            if (m < 60) return m.toString() + "분 전"
            val h = m / 60
            if (h < 24) return h.toString() + "시간 전"
            return (h / 24).toString() + "일 전"
        }
    }
}
