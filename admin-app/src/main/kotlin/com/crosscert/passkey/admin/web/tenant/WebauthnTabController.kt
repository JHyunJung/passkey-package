package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.tenant.AllowedOriginFormat
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.tenant.WebauthnConfigDiff
import com.crosscert.passkey.admin.tenant.WebauthnDiffRequest
import com.crosscert.passkey.admin.web.format.ApkKeyHash
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.OriginValidation
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger(WebauthnTabController::class.java)

/**
 * Tenant 상세의 WebAuthn 설정 탭(← pages/tenant/WebauthnConfigTab.tsx, api/webauthn.ts).
 *
 * 모든 엔드포인트는 [TenantTabs.load] 로 접근 경계를 먼저 확인한다(RP_ADMIN 의 다른 테넌트 → 403). 화면에 없는 값(rpId·displayName·
 * acceptedFormats·`mdsRequired`)은 요청에서 받지 않고 그때 서버에서 읽은 현재 값을 그대로 다시 보낸다 — api/webauthn.ts 의 adaptConfig·역변환과
 * 같은 결과이면서 오래된 값으로 다른 관리자의 변경을 되돌리지 않고 클라이언트가 위조할 수도 없다.
 *
 * 흐름: 폼 제출 = 미리보기(`POST …/webauthn/diff`: 검증 → 서버 diff + 로컬 diff 병합 → 확인 다이얼로그 조각) → 다이얼로그 확인 =
 * 저장(`PUT …/webauthn`: 비교한 바로 그 값, 성공하면 탭을 새 값으로 다시 그림). 검증 실패는 422 + 필드 옆 오류(OOB, 입력·변경 상태 유지),
 * 저장 단계 거절은 확인 다이얼로그 안 인라인 오류다.
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 테넌트 조회·검증이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자(`TenantAdminController.update`)의 파라미터 검사 NPE 가 나던 자리(try 밖).
 */
@Controller
class WebauthnTabController(
    private val tenants: TenantAdminController,
    private val validator: Validator,
) {

    // ── 모델 ────────────────────────────────────────────────

    data class Guide(val tone: String?, val text: String?)

    /** 화면에 그리는 편집 값(폼 입력). timeoutMs 는 입력 원문이다. rpId·rpName·at 은 서버 값(nullable)에서 올 수 있다. */
    data class Form(
        val rpId: String?,
        val rpName: String?,
        val timeoutMs: String,
        val origins: List<String>,
        val uv: String,
        val at: String?,
    ) {
        companion object {
            fun of(v: TenantAdminDto.TenantView): Form = Form(
                // List.copyOf: 원 Java 와 같이 null 목록·원소면 NPE.
                v.rpId, v.rpName, v.webauthnTimeoutMs.toString(), java.util.List.copyOf(v.allowedOrigins),
                if (v.requireUserVerification) "REQUIRED" else "PREFERRED", v.attestationConveyance,
            )
        }
    }

    /** 한 줄 변경(원본 Change). 배열 필드의 from/to 는 List<String>. */
    data class Change(val key: String?, val from: Any?, val to: Any?)

    /** 변경 비교 한 줄(화면용). */
    data class DiffRow(
        val key: String?,
        val label: String?,
        val array: Boolean,
        val removed: List<String?>?,
        val added: List<String?>?,
        val from: String?,
        val to: String?,
        val fromTitle: String?,
        val toTitle: String?,
    )

    data class GuideBox(val value: String?, val label: String?, val text: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/webauthn")
    fun tab(@PathVariable id: String, model: Model): String {
        val v = try {
            TenantTabs.load(tenants, id)
        } catch (e: BusinessException) {
            throw e // 403·404 는 WebExceptionAdvice(본문 없음 + 토스트) — 데이터를 그리지 않는다
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            // 그 밖의 조회 실패는 인라인 LoadError(원본: 스켈레톤에 멈추지 않고 다시 시도)
            log.warn("[webauthn-tab] load failed: {}", e.toString())
            model.addAttribute("loadError", e)
            model.addAttribute("retryAttrs", TenantTabs.requesterAttrs("$FRAG$id/tab/webauthn"))
            return TAB_VIEW
        }
        fill(model, v, Form.of(v))
        return TAB_VIEW
    }

    /** ChipInput 원격 검증(origin): 본문 = 오류 문구(비면 통과), 통과하면 X-Normalized-Value = 정규화한 origin. */
    @GetMapping(FRAG + "{id}/webauthn/origin-check")
    fun originCheck(
        @PathVariable id: String,
        @RequestParam(name = "value", defaultValue = "") value: String,
        @RequestParam(name = "existing", required = false) existing: List<String?>?,
    ): ResponseEntity<String> {
        val v = TenantTabs.load(tenants, id)
        val r = OriginValidation.validate(value, v.rpId)
        if (!r.ok) return text(r.error, null)
        if (existing != null && existing.contains(r.value)) return text("'" + r.value + "' 는 이미 추가되어 있습니다.", null)
        return text("", r.value)
    }

    /** Android 앱 키(SHA-256 지문 → apk-key-hash origin). 본문 = 오류 문구(비면 통과), X-Normalized-Value = origin. */
    @GetMapping(FRAG + "{id}/webauthn/apk-key")
    fun apkKey(
        @PathVariable id: String,
        @RequestParam(name = "value", defaultValue = "") value: String,
        @RequestParam(name = "existing", required = false) existing: List<String?>?,
    ): ResponseEntity<String> {
        TenantTabs.load(tenants, id)
        val r = ApkKeyHash.fromFingerprint(value)
        if (!r.ok) return text(r.error, null)
        if (existing != null && existing.contains(r.value)) return text("이미 추가된 Android 키입니다.", null)
        return text("", r.value)
    }

    /**
     * 미리보기(저장… 버튼). 검증 실패는 422 + 필드 옆 오류(OOB). 통과하면 서버 diff(`TenantAdminController.diff`)와 로컬 diff 를 합쳐
     * 확인 다이얼로그를 돌려준다. 변경이 없으면 경고 토스트만.
     */
    @PostMapping(FRAG + "{id}/webauthn/diff")
    fun diff(
        @PathVariable id: String,
        @RequestParam(name = "rpName", defaultValue = "") rpName: String,
        @RequestParam(name = "timeoutMs", defaultValue = "") timeoutMs: String,
        @RequestParam(name = "origins", required = false) origins: List<String?>?,
        @RequestParam(name = "userVerification", defaultValue = "") uv: String,
        @RequestParam(name = "attestationConveyance", defaultValue = "") at: String,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val draft = Form(v.rpId, rpName, timeoutMs.javaTrim(), clean(origins), uv, at)
        val errors = validate(v, draft)
        model.addAttribute("errors", errors)
        model.addAttribute("dialog", false)
        if (errors.isNotEmpty()) {
            res.status = 422
            return RESULT_VIEW
        }

        val merged = try {
            val local = diffObjects(Form.of(v), draft)
            // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리(try 안 — 비교 실패 토스트).
            val server = tenants.diff(
                v.id!!.toString(),
                WebauthnDiffRequest(v.rpId, draft.rpName, draft.origins, v.acceptedFormats, "REQUIRED" == draft.uv, v.mdsRequired),
            )
            mergeChanges(adaptDiff(server), local)
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[webauthn-tab] diff failed: {}", e.toString())
            val info = ErrorMessages.describe(e)
            val status = info.httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            Toasts.errorTitled(res, "변경 사항을 비교하지 못했습니다", info.toastMessage(), "HTTP $status", MDC.get("traceId"))
            res.status = status
            return EMPTY_VIEW
        }
        if (merged.isEmpty()) {
            Toasts.warnTitled(res, "변경 사항이 없습니다.", "")
            return RESULT_VIEW
        }
        val rows = ArrayList<DiffRow>()
        for (c in merged) rows.add(row(c))
        model.addAttribute("dialog", true)
        model.addAttribute("rows", rows)
        model.addAttribute("title", "변경 사항 " + merged.size + "개를 저장할까요?")
        model.addAttribute("draft", draft)
        model.addAttribute("changeCount", merged.size)
        model.addAttribute("saveAttrs", saveAttrs(v))
        return RESULT_VIEW
    }

    /**
     * 저장(확인 다이얼로그의 확인). 비교한 값 그대로 `TenantAdminController.update` 로 보낸다. 화면에 없는 값은 지금 서버 값.
     * 성공하면 탭을 새 값으로 다시 그리고(`HX-Trigger: saved`) 토스트를 띄운다. 검증·REST 거절은 다이얼로그 안 인라인 오류.
     */
    @PutMapping(FRAG + "{id}/webauthn")
    fun save(
        @PathVariable id: String,
        @RequestParam(name = "rpName", defaultValue = "") rpName: String,
        @RequestParam(name = "timeoutMs", defaultValue = "") timeoutMs: String,
        @RequestParam(name = "origins", required = false) origins: List<String?>?,
        @RequestParam(name = "userVerification", defaultValue = "") uv: String,
        @RequestParam(name = "attestationConveyance", defaultValue = "") at: String,
        @RequestParam(name = "changes", defaultValue = "0") changes: String,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        val draft = Form(v.rpId, rpName, timeoutMs.javaTrim(), clean(origins), uv, at)
        val errors = validate(v, draft)
        if (errors.isNotEmpty()) {
            // 비교 단계가 이미 걸렀으므로 여기서의 실패는 위조·경합이다: 다이얼로그 안 인라인 오류(스왑 없음).
            res.status = HttpServletResponse.SC_BAD_REQUEST
            Toasts.confirmError(res, errors.values.iterator().next(), null, null)
            return EMPTY_VIEW
        }
        // !!: 원 Java v.getId().toString() 의 NPE, auth 는 Kotlin 피호출자 파라미터 검사 NPE 와 같은 자리(인자 평가 순서 그대로).
        val updated = tenants.update(v.id!!.toString(), request(v, draft), auth!!).data()
        val n = parseCount(changes)
        Toasts.successSaved(res, "WebAuthn 설정을 저장했습니다", n.toString() + "개 항목이 바뀌었습니다.")
        fill(model, updated, Form.of(updated))
        return TAB_VIEW
    }

    // ── 검증 ────────────────────────────────────────────────

    /** 필드 이름 → 오류 문구(비면 통과). 서버(REST)가 거절할 값을 미리 걸러 필드 옆에 보인다. */
    private fun validate(v: TenantAdminDto.TenantView, f: Form): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        // !!: 원 Java f.rpName().isBlank() 의 NPE 와 같은 자리(초안의 rpName 은 요청 파라미터라 null 이 아니다).
        if (f.rpName!!.javaIsBlank()) e["rpName"] = "RP 표시 이름을 입력하세요."
        val ms = parseMs(f.timeoutMs)
        if (ms == null || ms < 1000 || ms > 600000) e["timeoutMs"] = "대기 시간은 1000–600000ms 범위의 숫자여야 합니다."
        if (f.origins.isEmpty()) {
            e["origins"] = "허용 origin 을 하나 이상 추가하세요."
        } else {
            val bad = ArrayList<String>()
            for (o in f.origins) if (!AllowedOriginFormat.isValid(o)) bad.add("'$o' 는 올바른 origin 형식이 아닙니다.")
            if (bad.isNotEmpty()) e["origins"] = bad.joinToString(" ")
        }
        if (!UV_VALUES.contains(f.uv)) e["userVerification"] = "사용자 검증은 필수 또는 권장만 고를 수 있습니다."
        // !!: 원 Java List.of(..).contains(null) 의 NPE 와 같은 자리(초안의 at 은 요청 파라미터라 null 이 아니다).
        if (!AT_VALUES.contains(f.at!!)) e["attestationConveyance"] = "인증기 증명 값이 올바르지 않습니다."
        if (e.isEmpty()) {
            // 길이 제한 등 나머지 제약은 REST 와 같은 Bean Validation 으로.
            for (cv in validator.validate(request(v, f))) {
                val p = cv.propertyPath.toString()
                if (p.startsWith("rpName")) {
                    e.putIfAbsent("rpName", "RP 표시 이름은 256자 이하로 입력하세요.")
                } else if (p.startsWith("allowedOrigins")) {
                    e.putIfAbsent("origins", "허용 origin 은 512자 이하여야 합니다.")
                } else if (p.startsWith("webauthnTimeoutMs")) {
                    e.putIfAbsent("timeoutMs", "대기 시간은 1000–600000ms 범위의 숫자여야 합니다.")
                } else {
                    e.putIfAbsent("form", "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요.")
                }
            }
        }
        return e
    }

    // ── 모델 채우기 ─────────────────────────────────────────

    private fun fill(model: Model, v: TenantAdminDto.TenantView, f: Form) {
        val base = FRAG + v.id + "/webauthn"
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        model.addAttribute("tid", v.id!!.toString())
        model.addAttribute("f", f)
        model.addAttribute("diffUrl", "$base/diff")
        model.addAttribute("originCheckUrl", "$base/origin-check")
        model.addAttribute("apkUrl", "$base/apk-key")
        model.addAttribute("originPlaceholder", "https://" + f.rpId + " 입력 후 Enter")
        model.addAttribute("timeoutGuide", timeoutGuide(parseMs(f.timeoutMs)))
        model.addAttribute("uvOptions", options(UV_VALUES, UV_LABELS))
        model.addAttribute("atOptions", options(AT_VALUES, AT_LABELS))
        model.addAttribute("uvGuides", guides(UV_VALUES, UV_LABELS, UV_GUIDE))
        model.addAttribute("atGuides", guides(AT_VALUES, AT_LABELS, AT_GUIDE))
        model.addAttribute("saveLabels", saveLabelsJson())
    }

    companion object {
        private const val FRAG = "/admin/_/tenants/"
        private const val TAB_VIEW = "admin/tenant/webauthn :: tab"
        private const val RESULT_VIEW = "admin/tenant/webauthn-diff :: result"
        private const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

        private val UV_LABELS: Map<String, String> = java.util.Map.of("REQUIRED", "필수", "PREFERRED", "권장", "DISCOURAGED", "요구 안 함")
        private val AT_LABELS: Map<String, String> =
            java.util.Map.of("NONE", "요청 안 함", "INDIRECT", "익명 허용", "DIRECT", "직접", "ENTERPRISE", "기업용")

        /** '요구 안 함'(DISCOURAGED)은 서버가 boolean 만 저장해 되돌아오지 않으므로 숨긴다(스펙 §8). */
        private val UV_VALUES: List<String> = java.util.List.of("REQUIRED", "PREFERRED")
        private val AT_VALUES: List<String> = java.util.List.of("NONE", "INDIRECT", "DIRECT", "ENTERPRISE")

        private val UV_GUIDE: Map<String, String> = java.util.Map.of(
            "REQUIRED", "PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다. UV를 못 하는 인증기는 패스키 등록·인증이 실패합니다. 2FA 수준의 보안이 필요할 때 권장.",
            "PREFERRED", "가능하면 UV를 수행하되, 지원하지 않는 인증기는 UV 없이도 허용합니다. 보안과 호환성의 균형 — 대부분의 서비스 기본값.",
        )
        private val AT_GUIDE: Map<String, String> = java.util.Map.of(
            "NONE", "attestation을 요청하지 않습니다. 프라이버시가 가장 높고 등록이 단순해, 인증기 모델 검증이 불필요한 일반 서비스에 권장.",
            "INDIRECT", "attestation을 원하되 클라이언트가 익명화할 수 있게 허용합니다. 출처 정보를 받되 개별 기기 추적은 피하고 싶을 때.",
            "DIRECT", "인증기의 attestation을 그대로 받습니다. AAGUID·모델 확인이나 MDS 매칭으로 허용 기기를 통제하려면 필요. 등록 시 사용자 동의 프롬프트가 뜰 수 있습니다.",
            "ENTERPRISE", "기기를 개별 식별할 수 있는 attestation을 요구합니다. 사전 등록된 인증기만 쓰는 사내 환경 전용 — 일반 사용자 대상 서비스에는 부적합.",
        )

        /** 화면 라벨. diff 다이얼로그·저장 바 요약·설정 행 이름에 쓰고, 원시 키는 title 툴팁으로 남긴다. */
        private val FIELD_LABELS: Map<String, String> = java.util.Map.of(
            "rpId", "RP ID",
            "rpName", "RP 표시 이름",
            "timeoutMs", "대기 시간",
            "origins", "허용 origin",
            "userVerification", "사용자 검증",
            "requireUserVerification", "사용자 검증",
            "mdsRequired", "MDS 검증 필수",
            "attestationConveyance", "인증기 증명",
            "formats", "허용 증명 형식",
        )
        private val FIELD_ORDER: List<String> =
            java.util.List.of("rpName", "timeoutMs", "origins", "userVerification", "attestationConveyance", "formats")

        /** api/webauthn.ts update 의 역변환. 화면에 없는 값은 읽어 온 현재 값(mdsRequired 포함)을 그대로 쓴다. */
        private fun request(v: TenantAdminDto.TenantView, f: Form): TenantAdminDto.TenantUpdateRequest {
            val ms = parseMs(f.timeoutMs)
            return TenantAdminDto.TenantUpdateRequest(
                v.displayName, v.rpId, f.rpName, f.origins, v.acceptedFormats,
                "REQUIRED" == f.uv, v.mdsRequired, f.at, ms ?: 0,
            )
        }

        // ── diff ────────────────────────────────────────────────

        /** diffObjects: 화면에 보이는 필드 중 처음 값과 달라진 것(rpId·formats 는 편집하지 않는다). */
        private fun diffObjects(a: Form, b: Form): List<Change> {
            val out = ArrayList<Change>()
            if (a.rpName != b.rpName) out.add(Change("rpName", a.rpName, b.rpName))
            val am = parseMs(a.timeoutMs)
            val bm = parseMs(b.timeoutMs)
            if (am != bm) out.add(Change("timeoutMs", am, bm))
            if (a.origins != b.origins) out.add(Change("origins", a.origins, b.origins))
            if (a.uv != b.uv) out.add(Change("userVerification", a.uv, b.uv))
            if (a.at != b.at) out.add(Change("attestationConveyance", a.at, b.at))
            return out
        }

        /** adaptDiffChanges: 배열 필드는 서버가 from/to 대신 added/removed 를 채운다(from=removed, to=added 로 옮긴다). */
        private fun adaptDiff(diff: WebauthnConfigDiff): List<Change> {
            val out = ArrayList<Change>()
            // !!: 원 Java diff.getChanges() 순회의 NPE 와 같은 자리.
            for (c in diff.changes!!) {
                val added = c.added
                val removed = c.removed
                if (added != null || removed != null) {
                    out.add(Change(c.field, removed ?: java.util.List.of<String>(), added ?: java.util.List.of<String>()))
                } else {
                    out.add(Change(c.field, c.from, c.to))
                }
            }
            return out
        }

        /**
         * L2 확인 목록 = 서버 diff + 로컬 diff 중 서버가 비교하지 않는 필드(timeoutMs·attestationConveyance). 화면 순서로 정렬(안정).
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(WebauthnTabControllerTest)가 부른다.
         */
        internal fun mergeChanges(server: List<Change>, local: List<Change>): List<Change> {
            val covered = LinkedHashSet<String?>()
            for (c in server) covered.add(norm(c.key))
            val merged = ArrayList(server)
            for (c in local) if (!covered.contains(norm(c.key))) merged.add(c)
            val sorted = ArrayList(merged)
            sorted.sortWith { x, y -> Integer.compare(rank(x.key), rank(y.key)) }
            return sorted
        }

        private fun norm(k: String?): String? = if ("requireUserVerification" == k) "userVerification" else k

        private fun rank(k: String?): Int {
            // !!: 원 Java List.of(..).indexOf(null) 의 NPE 와 같은 자리.
            val i = FIELD_ORDER.indexOf(norm(k)!!)
            return if (i < 0) FIELD_ORDER.size else i
        }

        /**
         * 저장 바 요약용: 바뀐 필드 이름을 화면 순서로.
         * internal: 같은 모듈 테스트(WebauthnTabControllerTest)가 부른다.
         */
        internal fun changedFieldNames(keys: List<String>): List<String> {
            val out = ArrayList<String>()
            for (k in FIELD_ORDER) if (keys.contains(k)) out.add(k)
            for (k in keys) if (!FIELD_ORDER.contains(k)) out.add(k)
            return out
        }

        /** DiffRow 한 줄(원시 값 대신 사람이 읽는 값). */
        @Suppress("UNCHECKED_CAST")
        private fun row(c: Change): DiffRow {
            // !!: 원 Java Map.of(..).getOrDefault(null, ..) 의 NPE 와 같은 자리.
            val key = c.key!!
            val label = FIELD_LABELS.getOrDefault(key, key)
            val f = c.from
            val t = c.to
            if (f is List<*> && t is List<*>) {
                val from = f as List<String?>
                val to = t as List<String?>
                val added = to.filter { x -> !from.contains(x) }
                val removed = from.filter { x -> !to.contains(x) }
                return DiffRow(key, label, true, removed, added, null, null, null, null)
            }
            return DiffRow(
                key, label, false, java.util.List.of(), java.util.List.of(), showValue(key, f), showValue(key, t),
                f.toString(), t.toString(),
            )
        }

        /** internal: 원 Java 는 package-private static — 같은 모듈 테스트(WebauthnTabControllerTest)가 부른다. */
        internal fun showValue(key: String?, v: Any?): String? {
            if ("timeoutMs" == key && v is Number) {
                return BigDecimal.valueOf(v.toDouble() / 1000.0).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "초"
            }
            if ("userVerification" == key && v is String) return UV_LABELS.getOrDefault(v, v)
            // 서버 diff 의 boolean: 탭은 true→필수, false→권장 으로 매핑한다(webauthn.ts adaptConfig).
            if ("requireUserVerification" == key && v is Boolean) return UV_LABELS[if (v) "REQUIRED" else "PREFERRED"]
            if ("mdsRequired" == key && v is Boolean) return if (v) "필수" else "필수 아님"
            if ("attestationConveyance" == key && v is String) return AT_LABELS.getOrDefault(v, v)
            return v.toString()
        }

        private fun saveAttrs(v: TenantAdminDto.TenantView): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-put"] = FRAG + v.id + "/webauthn" // 원 Java 문자열 연결 — null 이면 "null"
            m["hx-target"] = "#" + TenantTabs.TARGET_ID
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = TenantTabs.SYNC
            return m
        }

        /** 저장 바 요약(data-savebar-labels): 폼 필드 이름 → 라벨, 화면 순서. */
        private fun saveLabelsJson(): String {
            val sb = StringBuilder("{")
            val defs = arrayOf(
                arrayOf("rpName", "rpName"), arrayOf("timeoutMs", "timeoutMs"), arrayOf("origins", "origins"),
                arrayOf("userVerification", "userVerification"), arrayOf("attestationConveyance", "attestationConveyance"),
            )
            for (i in defs.indices) {
                if (i > 0) sb.append(',')
                sb.append('"').append(defs[i][0]).append("\":\"").append(FIELD_LABELS[defs[i][1]]).append('"')
            }
            return sb.append('}').toString()
        }

        private fun options(values: List<String>, labels: Map<String, String>): List<Map<String, Any?>> {
            val out = ArrayList<Map<String, Any?>>()
            for (v in values) {
                val o = LinkedHashMap<String, Any?>()
                o["value"] = v
                o["label"] = labels[v]
                o["title"] = v
                o["attrs"] = null
                out.add(o)
            }
            return out
        }

        private fun guides(values: List<String>, labels: Map<String, String>, texts: Map<String, String>): List<GuideBox> {
            val out = ArrayList<GuideBox>()
            for (v in values) out.add(GuideBox(v, labels[v], texts[v]))
            return out
        }

        /**
         * timeoutGuide: 권장 범위 60000–120000ms. 벗어나면 warn 톤(ui.js timeoutHint 와 같은 문구).
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(WebauthnTabControllerTest)가 부른다.
         */
        internal fun timeoutGuide(msBox: Int?): Guide {
            val ms = msBox ?: 0
            if (ms <= 0) return Guide("warn", "0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).")
            val totalSec = ms / 1000.0
            val secLabel = (
                if (totalSec == Math.floor(totalSec)) {
                    totalSec.toLong().toString()
                } else {
                    BigDecimal.valueOf(totalSec).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                }
                ) + "초"
            var human = "= $secLabel"
            if (totalSec >= 60) {
                val m = Math.floor(totalSec / 60).toLong()
                val s = Math.round(totalSec - m * 60)
                human = "= " + secLabel + (if (s == 0L) " (${m}분)" else " (" + m + "분 " + s + "초)")
            }
            if (ms < 60000) return Guide("warn", "$human — 권장(60초)보다 짧습니다. 너무 짧으면 사용자가 인증을 끝내기 전에 만료될 수 있습니다.")
            if (ms > 120000) return Guide("warn", "$human — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 서버 자원이 오래 점유됩니다.")
            return Guide("ok", "$human — 권장 범위(60–120초) 안입니다.")
        }

        // ── 도우미 ──────────────────────────────────────────────

        private fun clean(origins: List<String?>?): List<String> {
            val out = ArrayList<String>()
            if (origins != null) for (o in origins) if (o != null && !o.javaIsBlank() && !out.contains(o.javaTrim())) out.add(o.javaTrim())
            return out
        }

        private fun parseMs(raw: String?): Int? {
            try {
                return Integer.valueOf(raw?.javaTrim() ?: "")
            } catch (e: NumberFormatException) {
                return null
            }
        }

        private fun parseCount(raw: String): Int {
            try {
                return Math.max(0, Math.min(99, Integer.parseInt(raw.javaTrim())))
            } catch (e: NumberFormatException) {
                return 0
            }
        }

        private fun text(body: String?, normalized: String?): ResponseEntity<String> {
            val b = ResponseEntity.ok().contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
            if (normalized != null) b.header("X-Normalized-Value", normalized)
            b.header(HttpHeaders.CACHE_CONTROL, "no-store")
            return b.body(body)
        }
    }
}
