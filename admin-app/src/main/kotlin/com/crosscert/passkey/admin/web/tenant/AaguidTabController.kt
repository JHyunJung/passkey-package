package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.policy.AaguidPolicyDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.format.Labels
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(AaguidTabController::class.java)

/**
 * Tenant 상세의 AAGUID 정책 탭(← pages/tenant/AaguidPolicyTab.tsx, api/aaguidPolicy.ts, tenantSignals.ts unverifiedAaguidCount).
 *
 * 모든 엔드포인트는 [TenantTabs.load] 로 접근 경계를 먼저 확인한다. 흐름은 WebAuthn 탭과 같다: 폼 제출 = 미리보기
 * (`POST …/aaguid/preview`: 검증 → 저장 확인 등급 L3/L2 결정 → 확인 다이얼로그) → 확인 = 저장(`PUT …/aaguid` →
 * `AaguidPolicyController.update`). 항목 메모는 이 화면이 편집하지 않으므로 저장할 때 서버의 현재 메모를 항목별로 되돌려 보낸다
 * (원본은 불러온 항목의 note 를 그대로 보냄). 저장 성공은 탭을 새 정책으로 다시 그리고 탭 줄의 "확인 필요" 배지를 OOB 로 갱신한다(원본 onSaved).
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 테넌트 조회·검증이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리.
 */
@Controller
class AaguidTabController(
    private val tenants: TenantAdminController,
    private val aaguid: AaguidPolicyController,
) {

    // ── 모델 ────────────────────────────────────────────────

    data class Entry(val aaguid: String?, val note: String?, val mdsName: String?)

    /** 정책(저장된 값 또는 편집 중인 값). mode 는 enum 이름. */
    data class Policy(val mode: String, val mdsStrict: Boolean, val entries: List<Entry>) {
        companion object {
            internal fun of(v: AaguidPolicyDto.View): Policy {
                val es = ArrayList<Entry>()
                for (e in v.entries) es.add(Entry(if (e.aaguid == null) "" else e.aaguid.toString(), e.note, e.mdsName))
                // !!: 원 Java v.getMode().name() 의 NPE 와 같은 자리.
                return Policy(v.mode!!.name, v.mdsStrict, es)
            }
        }
    }

    sealed interface Change

    data class ModeChange(val from: String?, val to: String?) : Change

    data class EntryChange(val added: Boolean, val aaguid: String?) : Change

    data class NoteChange(val aaguid: String?, val from: String?, val to: String?) : Change

    data class MdsChange(val from: Boolean, val to: Boolean) : Change

    /** 확인 다이얼로그 목록의 한 줄. */
    data class Line(val text: String?, val mono: Boolean)

    enum class ConfirmKind { BLOCK_ALL, MDS_STRICT, DIFF }

    data class ModeOption(val value: String?, val label: String?, val desc: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/aaguid")
    fun tab(@PathVariable id: String, model: Model): String {
        val v: TenantAdminDto.TenantView
        val policy: AaguidPolicyDto.View
        try {
            v = TenantTabs.load(tenants, id)
            // !!: 원 Java aaguid.get(v.getId()) — null 이면 Kotlin 피호출자 파라미터 검사 NPE(이 try 가 받아 로드 실패 카드). 같은 자리.
            policy = aaguid.get(v.id!!)
        } catch (e: BusinessException) {
            throw e
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[aaguid-tab] load failed: {}", e.toString())
            model.addAttribute("loadError", e)
            model.addAttribute("retryAttrs", TenantTabs.requesterAttrs("$FRAG$id/tab/aaguid"))
            return TAB_VIEW
        }
        // v.id 는 위 try 에서 non-null 로 확인됐다(원 Java v.getId().toString()).
        fill(model, v.id.toString(), Policy.of(policy))
        return TAB_VIEW
    }

    /** ChipInput 원격 검증(AAGUID): 입력을 8-4-4-4-12 소문자로 맞춘다. 본문 = 오류 문구(비면 통과), X-Normalized-Value = 맞춘 값. */
    @GetMapping(FRAG + "{id}/aaguid/entry-check")
    fun entryCheck(@PathVariable id: String, @RequestParam(name = "value", defaultValue = "") value: String): ResponseEntity<String> {
        TenantTabs.load(tenants, id)
        val f = formatAaguid(value)
        if (!UUID_RE.matcher(f).matches()) return text("AAGUID 형식이 올바르지 않습니다. 8-4-4-4-12 형식의 16진수 32자를 입력하세요.", null)
        return text("", f)
    }

    /** 미리보기(저장… 버튼): 검증 실패는 422 + 필드 옆 오류(OOB). 통과하면 저장 확인 등급에 맞는 다이얼로그. 변경이 없으면 경고 토스트만. */
    @PostMapping(FRAG + "{id}/aaguid/preview")
    fun preview(
        @PathVariable id: String,
        @RequestParam(name = "mode", defaultValue = "") mode: String,
        @RequestParam(name = "aaguid", required = false) aaguids: List<String?>?,
        @RequestParam(name = "mdsStrict", defaultValue = "false") mdsStrict: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val errors = validate(mode, aaguids)
        model.addAttribute("errors", errors)
        model.addAttribute("dialog", false)
        if (errors.isNotEmpty()) {
            res.status = 422
            return RESULT_VIEW
        }
        // !!: 원 Java aaguid.get(v.getId()) — null 이면 Kotlin 피호출자 파라미터 검사 NPE. 같은 자리.
        val saved = Policy.of(aaguid.get(v.id!!))
        val draft = draft(saved, mode, aaguids, mdsStrict)
        val changes = policyChanges(saved, draft)
        if (changes.isEmpty()) {
            Toasts.warnTitled(res, "변경 사항이 없습니다.", "")
            return RESULT_VIEW
        }
        val kind = saveConfirmKind(saved, draft)
        model.addAttribute("dialog", true)
        model.addAttribute("kind", kind.name)
        model.addAttribute("changes", lines(changes))
        model.addAttribute("changeCount", changes.size)
        model.addAttribute("draft", draft)
        model.addAttribute("draftAaguids", draft.entries.map { it.aaguid })
        model.addAttribute("strictNote", allowlistUnknownNote(draft))
        model.addAttribute("impact", MDS_STRICT_IMPACT)
        model.addAttribute("saveAttrs", saveAttrs(v))
        return RESULT_VIEW
    }

    /**
     * 저장(확인 다이얼로그의 확인). `AaguidPolicyController.update` 로 보낸다(항목 메모는 서버의 현재 값). 성공이면 탭을 새 정책으로 다시 그리고
     * 탭 줄의 AAGUID 배지를 갱신한다. 검증·REST 거절은 다이얼로그 안 인라인 오류.
     */
    @PutMapping(FRAG + "{id}/aaguid")
    fun save(
        @PathVariable id: String,
        @RequestParam(name = "mode", defaultValue = "") mode: String,
        @RequestParam(name = "aaguid", required = false) aaguids: List<String?>?,
        @RequestParam(name = "mdsStrict", defaultValue = "false") mdsStrict: String,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val errors = validate(mode, aaguids)
        if (errors.isNotEmpty()) {
            res.status = HttpServletResponse.SC_BAD_REQUEST
            Toasts.confirmError(res, errors.values.iterator().next(), null, null)
            return EMPTY_VIEW
        }
        // !!: 원 Java aaguid.get(v.getId()) — null 이면 Kotlin 피호출자 파라미터 검사 NPE. 같은 자리.
        val tid = v.id!!
        val saved = Policy.of(aaguid.get(tid))
        val draft = draft(saved, mode, aaguids, mdsStrict)
        val entries = ArrayList<AaguidPolicyDto.EntryInput>()
        for (e in draft.entries) entries.add(AaguidPolicyDto.EntryInput(UUID.fromString(e.aaguid), e.note))
        val updated = aaguid.update(
            tid,
            AaguidPolicyDto.UpdateRequest(TenantAaguidPolicy.Mode.valueOf(draft.mode), draft.mdsStrict, entries),
            auth!!,
        )
        val p = Policy.of(updated)
        Toasts.successSaved(res, "AAGUID 정책 저장 완료", "")
        fill(model, tid.toString(), p)
        val unverified = unverifiedCount(p)
        model.addAttribute("badgeOob", true)
        model.addAttribute("badgeText", if (unverified > 0) "확인 필요 $unverified" else null)
        model.addAttribute("badgeReason", if (unverified > 0) "MDS 미확인 AAGUID " + unverified + "건" else null)
        return TAB_VIEW
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private fun fill(model: Model, tid: String, p: Policy) {
        val base = "$FRAG$tid/aaguid"
        val modes = ArrayList<ModeOption>()
        for (m in MODES) modes.add(ModeOption(m, Labels.aaguidMode(m), MODE_DESC[m]))
        val values = p.entries.map { it.aaguid }.filter { a -> a != null && a.isNotEmpty() }
        val meta = LinkedHashMap<String?, String?>()
        for (e in p.entries) if (e.mdsName != null) meta[e.aaguid] = e.mdsName
        val unknown = unverifiedCount(p)
        model.addAttribute("tid", tid)
        model.addAttribute("p", p)
        model.addAttribute("modes", modes)
        model.addAttribute("modeLabel", Labels.aaguidMode(p.mode))
        model.addAttribute("values", values)
        model.addAttribute("meta", meta)
        model.addAttribute("known", ArrayList(meta.keys))
        model.addAttribute("unknownCount", unknown)
        model.addAttribute("previewUrl", "$base/preview")
        model.addAttribute("entryCheckUrl", "$base/entry-check")
        model.addAttribute("listLabel", if ("ALLOWLIST" == p.mode) "허용된 AAGUID" else "차단된 AAGUID")
        model.addAttribute("emptyAllow", "ALLOWLIST" == p.mode && values.isEmpty())
        model.addAttribute("emptyDeny", "DENYLIST" == p.mode && values.isEmpty())
        model.addAttribute("strictHint", (if (p.mdsStrict) "" else "켜면 ") + MDS_STRICT_IMPACT + allowlistUnknownNote(p))
        model.addAttribute("impact", MDS_STRICT_IMPACT)
        model.addAttribute("saveLabels", "{\"mode\":\"mode\",\"aaguid\":\"AAGUID 목록\",\"mdsStrict\":\"MDS strict\"}")
    }

    companion object {
        private const val FRAG = "/admin/_/tenants/"
        private const val TAB_VIEW = "admin/tenant/aaguid :: tab"
        private const val RESULT_VIEW = "admin/tenant/aaguid :: result"
        private const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

        private val UUID_RE: Pattern =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE)
        private const val MDS_STRICT_IMPACT = "MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다."
        private val MODES: List<String> = java.util.List.of("ANY", "ALLOWLIST", "DENYLIST")
        private val MODE_DESC: Map<String, String> =
            java.util.Map.of("ANY", "모든 인증기 허용", "ALLOWLIST", "목록의 모델만 허용", "DENYLIST", "목록의 모델만 차단")

        private val NON_HEX = Regex("[^0-9a-f]")

        // ── 검증·계산 ───────────────────────────────────────────

        private fun validate(mode: String, aaguids: List<String?>?): Map<String, String> {
            val e = LinkedHashMap<String, String>()
            if (!MODES.contains(mode)) e["mode"] = "정책 방식이 올바르지 않습니다."
            val bad = ArrayList<String>()
            if (aaguids != null) {
                for (a in aaguids) {
                    if (a != null && !a.javaIsBlank() && !UUID_RE.matcher(a.javaTrim()).matches()) bad.add("'$a' 는 올바른 AAGUID 형식이 아닙니다.")
                }
            }
            if (bad.isNotEmpty()) e["aaguid"] = bad.joinToString(" ")
            return e
        }

        /** 편집 중 정책: 목록 순서를 유지하고, 저장된 항목은 메모·MDS 이름을 그대로(원본 draft 와 같다), 새 항목은 둘 다 null. */
        private fun draft(saved: Policy, mode: String, aaguids: List<String?>?, mdsStrict: String): Policy {
            val byId = LinkedHashMap<String?, Entry>()
            for (e in saved.entries) byId[e.aaguid] = e
            val entries = ArrayList<Entry>()
            val seen = ArrayList<String>()
            if (aaguids != null) {
                for (raw in aaguids) {
                    if (raw == null || raw.javaIsBlank()) continue
                    val a = raw.javaTrim().lowercase(Locale.ROOT)
                    if (seen.contains(a)) continue
                    seen.add(a)
                    entries.add(byId.getOrDefault(a, Entry(a, null, null)))
                }
            }
            return Policy(mode, "true".equals(mdsStrict, ignoreCase = true), entries)
        }

        /** 입력에서 16진수만 남기고(최대 32자) 8-4-4-4-12 위치에 하이픈을 넣는다(formatAaguid). */
        @JvmStatic
        fun formatAaguid(raw: String?): String {
            var hex = if (raw == null) "" else raw.lowercase(Locale.ROOT).replace(NON_HEX, "")
            if (hex.length > 32) hex = hex.substring(0, 32)
            val seg = intArrayOf(8, 4, 4, 4, 12)
            val out = ArrayList<String>()
            var i = 0
            for (len in seg) {
                if (i >= hex.length) break
                out.add(hex.substring(i, Math.min(i + len, hex.length)))
                i += len
            }
            return out.joinToString("-")
        }

        /**
         * 저장된 정책과 편집 중인 정책의 실제 차이. 백엔드는 entries 를 집합(AAGUID)으로 쓰므로 순서와 서버가 채우는 mdsName 은 변경이 아니다.
         */
        @JvmStatic
        fun policyChanges(saved: Policy, draft: Policy): List<Change> {
            val out = ArrayList<Change>()
            if (saved.mode != draft.mode) out.add(ModeChange(saved.mode, draft.mode))
            val savedMap = byId(saved)
            val draftMap = byId(draft)
            for ((key, value) in draftMap) {
                val before = savedMap[key]
                if (before == null) out.add(EntryChange(true, key))
                else if (before.note != value.note) out.add(NoteChange(key, before.note, value.note))
            }
            for (a in savedMap.keys) if (!draftMap.containsKey(a)) out.add(EntryChange(false, a))
            if (saved.mdsStrict != draft.mdsStrict) out.add(MdsChange(saved.mdsStrict, draft.mdsStrict))
            return out
        }

        private fun byId(p: Policy): Map<String, Entry> {
            val m = LinkedHashMap<String, Entry>()
            for (e in p.entries) {
                val a = e.aaguid
                if (a != null && a.isNotEmpty()) m[a] = e
            }
            return m
        }

        /** 저장 확인 등급. 빈 허용 목록 = L3 체크, MDS 엄격 켜기 = L2 영향 요약, 그 밖 = L2 변경 비교. */
        @JvmStatic
        fun saveConfirmKind(saved: Policy, draft: Policy): ConfirmKind {
            if ("ALLOWLIST" == draft.mode && byId(draft).isEmpty()) return ConfirmKind.BLOCK_ALL
            if (!saved.mdsStrict && draft.mdsStrict) return ConfirmKind.MDS_STRICT
            return ConfirmKind.DIFF
        }

        /**
         * SaveBar 요약용 변경 필드 이름(저장 바의 필드 이름과 같은 키: mode / aaguid / mdsStrict).
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(AaguidTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun changedFields(saved: Policy, draft: Policy): List<String> {
            val cs = policyChanges(saved, draft)
            val out = ArrayList<String>()
            if (cs.any { c -> c is ModeChange }) out.add("mode")
            if (cs.any { c -> c is EntryChange || c is NoteChange }) out.add("aaguid")
            if (cs.any { c -> c is MdsChange }) out.add("mdsStrict")
            return out
        }

        /**
         * mode≠ANY 이고 mdsName==null 인 AAGUID 수(목록에 보이는 항목만).
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(AaguidTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun unverifiedCount(p: Policy): Int {
            if ("ANY" == p.mode) return 0
            return p.entries.count { e -> e.aaguid != null && e.aaguid.isNotEmpty() && e.mdsName == null }
        }

        /** 허용 목록 모드에서는 목록 안의 MDS 미확인 모델도 같이 막힌다. */
        private fun allowlistUnknownNote(p: Policy): String {
            val n = if ("ALLOWLIST" == p.mode) unverifiedCount(p) else 0
            return if (n > 0) " 허용 목록 중 MDS 미확인 " + n + "개도 차단됩니다." else ""
        }

        private fun onOff(b: Boolean): String = if (b) "켜짐" else "꺼짐"

        private fun lines(changes: List<Change>): List<Line> {
            val out = ArrayList<Line>()
            for (c in changes) {
                when (c) {
                    is ModeChange -> out.add(Line("방식: " + Labels.aaguidMode(c.from) + " → " + Labels.aaguidMode(c.to), false))
                    is EntryChange -> out.add(Line((if (c.added) "+ " else "- ") + c.aaguid, true))
                    is NoteChange -> out.add(Line(c.aaguid + " 메모: " + (c.from ?: "없음") + " → " + (c.to ?: "없음"), true))
                    is MdsChange -> out.add(Line("MDS 엄격: " + onOff(c.from) + " → " + onOff(c.to), false))
                }
            }
            return out
        }

        private fun saveAttrs(v: TenantAdminDto.TenantView): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-put"] = FRAG + v.id + "/aaguid"
            m["hx-target"] = "#" + TenantTabs.TARGET_ID
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = TenantTabs.SYNC
            return m
        }

        private fun text(body: String, normalized: String?): ResponseEntity<String> {
            val b = ResponseEntity.ok().contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
            if (normalized != null) b.header("X-Normalized-Value", normalized)
            b.header(HttpHeaders.CACHE_CONTROL, "no-store")
            return b.body(body)
        }
    }
}
