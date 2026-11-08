package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.web.format.ApiKeyExpiry
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.Josa
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

private val log = LoggerFactory.getLogger(ApiKeysTabController::class.java)

/**
 * Tenant 상세의 API 키 탭(← pages/tenant/ApiKeysTab.tsx, apiKeyHelpers.ts, api/apiKeys.ts, ui/SecretBox.tsx, lib/apiKeyExpiry.ts).
 *
 * 접근 경계: 탭·발급은 [TenantTabs.load] 를 먼저 부른다. 회전·회수 주소는 키 id 로 되어 있어(REST 와 같다) 화면이 키의 tenant 를 함께 받는다
 * (`?tenantId=`): 그 tenant 를 [TenantTabs.load] 로 확인하고, 그 tenant 의 키 목록에 이 키가 있어야만 REST 를 부른다
 * (REST 서비스도 `TenantBoundary.assertCanAccessTenant` 로 같은 경계를 지키지만, 화면은 거기에 기대지 않는다).
 *
 * 평문 키: 발급·회전의 평문은 그 응답 조각(발급 완료 다이얼로그의 SecretBox)에만 그린다. 세션·플래시·헤더(토스트 포함)·로그에 싣지 않고
 * 리다이렉트도 쓰지 않는다(ui.js 의 토스트 넘기기가 sessionStorage 를 쓰므로). 응답은 `Cache-Control: no-store`.
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 테넌트 조회·검증이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리(발급은 try 안 — 인라인 오류, 회전·회수는 try 밖).
 */
@Controller
class ApiKeysTabController(
    private val tenants: TenantAdminController,
    private val apiKeys: ApiKeyAdminController,
    private val validator: Validator,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** 만료 열 배지(apiKeyHelpers.expiryBadge). tone: ok | warn | bad | neutral. */
    data class ExpiryBadge(val tone: String?, val label: String?, val sub: String?)

    data class Scope(val value: String?, val label: String?)

    /** 표의 한 줄(api/apiKeys.ts adapt). status: ACTIVE | EXPIRED | REVOKED. */
    data class Row(
        val id: String?,
        val name: String?,
        val prefix: String?,
        val status: String?,
        val scopes: List<Scope>?,
        val lastUsed: String?,
        val lastUsedLong: String?,
        val created: String?,
        val expiry: ExpiryBadge?,
        val rotateAttrs: Map<String, Any?>?,
        val revokeAttrs: Map<String, Any?>?,
        val menuItems: List<Map<String, Any?>>?,
        val menuDanger: List<Map<String, Any?>>?,
    ) {
        fun active(): Boolean = "ACTIVE" == status
    }

    data class Kpis(val total: String?, val totalHint: String?, val latest: String?, val overdueHint: String?, val overdueWarn: Boolean)

    /** 발급 완료 다이얼로그. expiry 가 null 이면 만료 줄을 숨긴다(회전 뒤 목록에서 새 키를 못 찾았을 때). */
    data class Issued(
        val id: String?,
        val name: String?,
        val prefix: String?,
        val expiry: String?,
        val plaintext: String?,
        val oldKeyNotice: String?,
    )

    data class ExpiryOption(val value: String?, val label: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(FRAG + "{id}/tab/apikeys")
    fun tab(@PathVariable id: String, model: Model): String {
        val v = TenantTabs.load(tenants, id)
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        val tid = v.id!!.toString()
        fillForm(model, tid)
        try {
            fillList(model, tid, apiKeys.list(tid).data())
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            listFailed(model, tid, e)
        }
        return TAB_VIEW
    }

    /**
     * 발급(새 키 발급 다이얼로그의 발급). 검증 실패·REST 거절은 다이얼로그 안 인라인 경고(422, 입력 유지, 토스트 없음 — 원본과 같다).
     * 성공하면 발급 완료 다이얼로그(평문은 여기에만) + 목록·탭 배지 OOB 갱신.
     */
    @PostMapping(FRAG + "{id}/api-keys")
    fun issue(
        @PathVariable id: String,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "scopes", required = false) scopes: List<String?>?,
        @RequestParam(name = "expiresInMonths", defaultValue = "") months: String,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val v = TenantTabs.load(tenants, id)
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        val tid = v.id!!.toString()
        noStore(res)

        var expiresInMonths: Int? = null
        var error: String? = null
        if (!months.javaIsBlank()) {
            try {
                expiresInMonths = Integer.valueOf(months.javaTrim())
            } catch (e: NumberFormatException) {
                error = "만료 기간이 올바르지 않습니다"
            }
        }
        val scopeSet = LinkedHashSet<String>()
        if (scopes != null) for (s in scopes) if (s != null && !s.javaIsBlank()) scopeSet.add(s.javaTrim())
        if (error == null && !SCOPES.containsAll(scopeSet)) error = "권한 범위가 올바르지 않습니다"
        val req = ApiKeyAdminDto.ApiKeyCreateRequest(v.id, name, scopeSet, expiresInMonths)
        if (error == null) error = violation(req)
        if (error != null) return issueError(res, model, error, null)

        val created = try {
            apiKeys.issue(req, auth!!).data()
        } catch (e: RuntimeException) {
            if (denied(e)) throw e
            log.warn("[apikeys-tab] issue failed: {}", e.javaClass.simpleName)
            val info = ErrorMessages.describe(e)
            return issueError(res, model, info.title, info.detail)
        }
        val expiresAt = created.expiresAt
        model.addAttribute(
            "issued",
            Issued(
                created.id.toString(), name, created.prefix, // id: 원 Java String.valueOf — null 이면 "null"
                if (expiresAt == null) "무기한" else KstFormat.kstDate(expiresAt), created.plainText, null,
            ),
        )
        refreshAfterChange(model, tid)
        model.addAttribute("resetForm", true) // 발급 성공만 새 키 발급 폼을 비운다(원본: 성공했을 때만 초기화)
        return ISSUED_VIEW
    }

    /** 회전(L2 확인 뒤). 새 키의 평문과 이전 키 만료 안내를 발급 완료 다이얼로그로 보인다. 실패는 확인 다이얼로그 안 인라인(confirmError). */
    @PostMapping(KEY_FRAG + "{keyId}/rotate")
    fun rotate(
        @PathVariable keyId: UUID,
        @RequestParam(name = "tenantId") tenantId: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requireConfirm(req)
        val o = owned(keyId, tenantId)
        noStore(res)
        val r = apiKeys.rotate(keyId, auth!!).data()
        val fresh = refreshAfterChange(model, o.tid)
        // 회전 응답에는 새 키의 만료가 없다 — 다시 읽은 목록에서 새 키를 찾으면 그 만료, 못 찾으면 만료 줄을 숨긴다(이전 키의 만료를 보이지 않음).
        var expiry: String? = null
        if (fresh != null) {
            for (k in fresh) {
                val exp = k.expiresAt
                if (k.id != null && k.id == r.id) expiry = if (exp == null) "무기한" else KstFormat.kstDate(exp)
            }
        }
        model.addAttribute(
            "issued",
            Issued(
                r.id.toString(), o.key.name, r.prefix, expiry, r.plaintextKey, // id: 원 Java String.valueOf — null 이면 "null"
                oldKeyNotice(r.oldKeyExpiresAt, clock.instant()),
            ),
        )
        return ISSUED_VIEW
    }

    /** 회수(L2 확인 뒤). 성공하면 목록을 다시 그리고 성공 토스트. 실패는 확인 다이얼로그 안 인라인(confirmError). */
    @DeleteMapping(KEY_FRAG + "{keyId}")
    fun revoke(
        @PathVariable keyId: UUID,
        @RequestParam(name = "tenantId") tenantId: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        requireConfirm(req)
        val o = owned(keyId, tenantId)
        apiKeys.revoke(keyId, auth!!)
        val name = o.key.name ?: ""
        Toasts.successTitled(res, "API 키 “" + name + "”" + Josa.eulReul(name) + " 회수했습니다.", o.key.keyPrefix)
        val ok = refreshAfterChange(model, o.tid) != null
        model.addAttribute("listOob", false) // 이 응답에서는 목록이 본 대상(#ak-list outerHTML)이다
        model.addAttribute("badgeOob", ok)
        return LIST_VIEW
    }

    // ── 경계 ────────────────────────────────────────────────

    private data class Owned(val tid: String, val key: ApiKeyAdminDto.ApiKeyView)

    /** 키가 속한 tenant 를 경계 확인한 뒤(RP 다른 tenant → 403) 그 tenant 의 목록에서 키를 찾는다. 없으면 404(API_KEY_NOT_FOUND). */
    private fun owned(keyId: UUID, tenantId: String): Owned {
        val v = TenantTabs.load(tenants, tenantId)
        // !!: 원 Java v.getId().toString() 의 NPE 와 같은 자리.
        val tid = v.id!!.toString()
        for (k in apiKeys.list(tid).data()) {
            if (keyId == k.id && v.id == k.tenantId) return Owned(tid, k)
        }
        throw BusinessException(ErrorCode.API_KEY_NOT_FOUND)
    }

    // ── 응답 도우미 ─────────────────────────────────────────

    private fun issueError(res: HttpServletResponse, model: Model, title: String?, detail: String?): String {
        res.status = 422
        model.addAttribute("issueError", issueErrorText(title, detail))
        return ISSUE_ERROR_VIEW
    }

    private fun violation(req: ApiKeyAdminDto.ApiKeyCreateRequest): String? {
        val vs = validator.validate(req)
        if (vs.isEmpty()) return null
        val path = vs.iterator().next().propertyPath.toString()
        for (cv in vs) {
            val p = cv.propertyPath.toString()
            if (p.startsWith("name")) {
                val n = req.name
                return if (n == null || n.javaIsBlank()) "용도(이름)를 입력하세요" else "용도(이름)는 " + NAME_MAX + "자 이하로 입력하세요"
            }
        }
        if (path.startsWith("scopes")) return "권한 범위를 하나 이상 선택하세요"
        if (path.startsWith("expiresInMonths")) return "만료 기간이 올바르지 않습니다"
        return "입력 값이 올바르지 않습니다"
    }

    /** 변경 뒤 목록을 다시 읽어 목록·탭 배지(OOB)를 채운다. 다시 읽기가 실패하면 목록 자리에 LoadError. */
    private fun refreshAfterChange(model: Model, tid: String): List<ApiKeyAdminDto.ApiKeyView>? {
        fillForm(model, tid)
        refreshKeyCount(model, tid)
        model.addAttribute("listOob", true)
        model.addAttribute("badgeOob", true)
        try {
            val keys = apiKeys.list(tid).data()
            fillList(model, tid, keys)
            return keys
        } catch (e: RuntimeException) {
            listFailed(model, tid, e)
            model.addAttribute("badgeOob", false)
            return null
        }
    }

    /**
     * 정지 확인창의 "유효한 API 키 N개"(원본 onChanged → tenant 재조회). tenant 를 다시 읽어 `keyCount` 로 넘기고, 응답 조각이
     * `#tenant-apikey-count` 를 OOB 로 바꾼다. 실패하면 조용히 생략(변경 자체는 성공했다).
     */
    private fun refreshKeyCount(model: Model, tid: String) {
        var n: Long? = null
        try {
            n = TenantTabs.load(tenants, tid).apiKeys
        } catch (e: RuntimeException) {
            log.warn("[apikeys-tab] tenant reload failed: {}", e.javaClass.simpleName)
        }
        model.addAttribute("keyCount", n)
    }

    private fun listFailed(model: Model, tid: String, e: RuntimeException) {
        log.warn("[apikeys-tab] list failed: {}", e.javaClass.simpleName)
        model.addAttribute("loadError", e)
        model.addAttribute("retryAttrs", TenantTabs.requesterAttrs("$FRAG$tid/tab/apikeys"))
        model.addAttribute("rows", java.util.List.of<Row>())
        model.addAttribute("kpis", Kpis("—", null, "—", null, false))
    }

    private fun fillForm(model: Model, tid: String) {
        model.addAttribute("tid", tid)
        model.addAttribute("issueUrl", "$FRAG$tid/api-keys")
        model.addAttribute("nameMax", NAME_MAX)
        model.addAttribute("rotationDays", ROTATION_DAYS)
        model.addAttribute("scopeOptions", SCOPES.map { s -> Scope(s, SCOPE_DESC[s]) })
        val opts = ArrayList<ExpiryOption>()
        val previews = LinkedHashMap<String, String>()
        for (m in EXPIRY_MONTHS) {
            opts.add(ExpiryOption(if (m == null) "" else m.toString(), if (m == null) "무기한" else m.toString() + "개월"))
            if (m != null) previews[m.toString()] = previewExpiry(m, clock.instant())
        }
        model.addAttribute("expiryOptions", opts)
        model.addAttribute("expiryPreviews", previews)
        model.addAttribute("loadError", null)
    }

    private fun fillList(model: Model, tid: String, keys: List<ApiKeyAdminDto.ApiKeyView>) {
        val now = clock.instant()
        val rows = ArrayList<Row>()
        var active = 0
        var expired = 0
        var revoked = 0
        var latest: OffsetDateTime? = null
        for (k in keys) {
            val st = status(k, now)
            when (st) {
                "ACTIVE" -> active++
                "EXPIRED" -> expired++
                else -> revoked++
            }
            val created = k.createdAt
            if (created != null && (latest == null || created.isAfter(latest))) latest = created
            rows.add(row(tid, k, st, now))
        }
        val overdue = rotationOverdueCount(keys, now)
        model.addAttribute("rows", rows)
        model.addAttribute(
            "kpis",
            Kpis(
                keys.size.toString(), "활성 $active · 만료 $expired · 회수 $revoked",
                if (latest == null) "—" else KstFormat.timeAgo(latest.toInstant(), now), "초과 키 " + overdue + "개", overdue > 0,
            ),
        )
        val expiring = keys.count { k -> ApiKeyExpiry.isExpiringSoon(k.revokedAt, k.expiresAt, now) }
        model.addAttribute("badgeText", if (expiring > 0) "확인 필요 $expiring" else null)
        model.addAttribute("badgeReason", if (expiring > 0) "30일 안에 만료되는 키 " + expiring + "개" else null)
    }

    private fun row(tid: String, k: ApiKeyAdminDto.ApiKeyView, st: String, now: Instant): Row {
        val name = k.name ?: ""
        val prefix = k.keyPrefix ?: ""
        val scopes = ArrayList<Scope>()
        val ks = k.scopes
        if (ks != null) for (s in ks) scopes.add(Scope(s, SCOPE_LABEL.getOrDefault(s, s)))
        val keyId = k.id.toString() // 원 Java String.valueOf — null 이면 "null"
        val q = "?tenantId=$tid"
        val rotateAttrs = LinkedHashMap<String, Any?>()
        rotateAttrs["hx-post"] = "$KEY_FRAG$keyId/rotate$q"
        rotateAttrs["hx-target"] = "#ak-issued-slot"
        rotateAttrs["hx-swap"] = "innerHTML"
        rotateAttrs["hx-sync"] = "#ak-root:drop"
        val revokeAttrs = LinkedHashMap<String, Any?>()
        revokeAttrs["hx-delete"] = "$KEY_FRAG$keyId$q"
        revokeAttrs["hx-target"] = "#ak-list"
        revokeAttrs["hx-swap"] = "outerHTML"
        revokeAttrs["hx-sync"] = "#ak-root:drop"
        val items: List<Map<String, Any?>> = java.util.List.of(
            menuItem("회전", "refresh", false, java.util.Map.of("data-dialog", "ak-rotate-$keyId", "x-on:click.capture", OPEN_DIALOG)),
            menuItem("접두사 복사", "copy", false, java.util.Map.of("data-prefix", prefix, "x-on:click.capture", COPY_PREFIX)),
        )
        val danger: List<Map<String, Any?>> = java.util.List.of(
            menuItem("회수", "trash", true, java.util.Map.of("data-dialog", "ak-revoke-$keyId", "x-on:click.capture", OPEN_DIALOG)),
        )
        val lastUsedAt = k.lastUsedAt
        val lastUsed = if (lastUsedAt == null) null else KstFormat.timeAgo(lastUsedAt.toInstant(), now)
        return Row(
            keyId, name, prefix, st, scopes, lastUsed ?: "미사용", lastUsed ?: "사용 안 함",
            KstFormat.kstDate(k.createdAt), expiryBadge(st, k.expiresAt, now), rotateAttrs, revokeAttrs, items, danger,
        )
    }

    companion object {
        private const val FRAG = "/admin/_/tenants/"
        private const val KEY_FRAG = "/admin/_/api-keys/"
        private const val TAB_VIEW = "admin/tenant/api-keys :: tab"
        private const val LIST_VIEW = "admin/tenant/api-keys :: listResponse"
        private const val ISSUE_ERROR_VIEW = "admin/tenant/api-keys :: issueError"
        private const val ISSUED_VIEW = "admin/tenant/api-key-issued :: issued"

        const val NAME_MAX = 64
        const val ROTATION_DAYS = 90
        private const val DAY_MS = 86_400_000L
        private val SCOPES: List<String> = java.util.List.of("registration", "authentication")
        private val SCOPE_LABEL: Map<String, String> = java.util.Map.of("registration", "패스키 등록", "authentication", "패스키 인증")
        private val SCOPE_DESC: Map<String, String> =
            java.util.Map.of("registration", "패스키 등록 · 목록 · 이름 변경 · 삭제", "authentication", "패스키 인증")

        /** 만료 프리셋(개월). null = 무기한. 기본 24개월. */
        private val EXPIRY_MONTHS: List<Int?> = java.util.Arrays.asList(6, 12, 24, 36, null)
        internal const val DEFAULT_MONTHS = 24
        internal const val ISSUE_FAIL_TITLE = "API 키를 발급하지 못했습니다."

        /** 메뉴 항목이 여는 확인 다이얼로그(id 는 data-dialog). 템플릿 상수. */
        const val OPEN_DIALOG = "\$dispatch('dialog:open', { id: \$el.dataset.dialog })"

        /** 접두사 복사 + 토스트(원본 copyPrefix). 접두사는 data-prefix 로 넘긴다. 템플릿 상수. 같은 패키지 Java 테스트가 읽는다 — const 라 정적 필드로 남는다. */
        const val COPY_PREFIX = "window.adminCopy(\$el.dataset.prefix).then((ok) => \$dispatch('toast', ok" +
            " ? { kind: 'success', title: '접두사를 복사했습니다.', message: \$el.dataset.prefix }" +
            " : { kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' }))"

        /** 권한 거부는 화면 안 안내가 아니라 WebExceptionAdvice 의 403 으로(데이터 없음). */
        private fun denied(e: RuntimeException): Boolean =
            e is AccessDeniedException || (e is BusinessException && e.errorCode == ErrorCode.ACCESS_DENIED)

        /** 회전·회수는 확인 다이얼로그의 확인 버튼(X-Confirm)으로만 받는다. */
        private fun requireConfirm(req: HttpServletRequest) {
            if (!Toasts.isConfirm(req)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "확인 후 다시 시도하세요.")
        }

        private fun noStore(res: HttpServletResponse) {
            res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
            res.setHeader(HttpHeaders.PRAGMA, "no-cache")
        }

        /**
         * `[title, detail.trim().replace(/[.。]$/, '')].filter(Boolean).join('. ') + '. 입력한 내용은 그대로 있습니다.'`
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(ApiKeysTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun issueErrorText(title: String?, detail: String?): String {
            val parts = ArrayList<String>()
            if (title != null && title.isNotEmpty()) parts.add(title)
            val d = if (detail == null) "" else detail.javaTrim().replace(TRAILING_DOT, "")
            if (d.isNotEmpty()) parts.add(d)
            return parts.joinToString(". ") + ". 입력한 내용은 그대로 있습니다."
        }

        private val TRAILING_DOT = Regex("[.。]$")

        private fun menuItem(label: String, icon: String, danger: Boolean, attrs: Map<String, Any?>): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["label"] = label
            m["icon"] = icon
            m["danger"] = danger
            m["attrs"] = attrs
            return m
        }

        // ── apiKeyHelpers.ts ────────────────────────────────────

        /** api/apiKeys.ts adapt: revokedAt → REVOKED, 만료 시각이 지났으면 EXPIRED, 그 밖 ACTIVE. */
        private fun status(k: ApiKeyAdminDto.ApiKeyView, now: Instant): String {
            if (k.revokedAt != null) return "REVOKED"
            val exp = k.expiresAt
            if (exp != null && !exp.toInstant().isAfter(now)) return "EXPIRED"
            return "ACTIVE"
        }

        /** none | expired | soon | ok */
        private fun expiryState(expiresAt: OffsetDateTime?, now: Instant): String {
            if (expiresAt == null) return "none"
            val exp = expiresAt.toInstant().toEpochMilli()
            val n = now.toEpochMilli()
            if (exp <= n) return "expired"
            if (exp - n <= ApiKeyExpiry.EXPIRY_WARN_DAYS * DAY_MS) return "soon"
            return "ok"
        }

        private fun daysLeft(expiresAt: OffsetDateTime, now: Instant): Long =
            Math.ceil((expiresAt.toInstant().toEpochMilli() - now.toEpochMilli()) / DAY_MS.toDouble()).toLong()

        /** 만료 열 배지 — 색만이 아니라 글로 상태를 말한다(정상 · 만료 임박 N일 · 만료됨 · 무기한 · 해당 없음). */
        @JvmStatic
        fun expiryBadge(status: String?, expiresAt: OffsetDateTime?, now: Instant): ExpiryBadge {
            if (expiresAt == null) return ExpiryBadge("neutral", "무기한", "만료일 없음")
            val sub = KstFormat.kstDate(expiresAt)
            if ("REVOKED" == status) return ExpiryBadge("neutral", "해당 없음", sub)
            val st = if ("EXPIRED" == status) "expired" else expiryState(expiresAt, now)
            if ("expired" == st) return ExpiryBadge("bad", "만료됨", sub)
            if ("soon" == st) return ExpiryBadge("warn", "만료 임박 · " + daysLeft(expiresAt, now) + "일 남음", sub)
            return ExpiryBadge("ok", "정상", sub)
        }

        /**
         * 권장 회전 주기(90일)를 넘긴 ACTIVE 키 수. createdAt 기준(정확히 90일은 초과 아님).
         * public `@JvmStatic`: 원 Java 는 package-private static — 같은 패키지 Java 테스트(ApiKeysTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun rotationOverdueCount(keys: List<ApiKeyAdminDto.ApiKeyView>, now: Instant): Int {
            var n = 0
            for (k in keys) {
                val created = k.createdAt
                if ("ACTIVE" != status(k, now) || created == null) continue
                if (now.toEpochMilli() - created.toInstant().toEpochMilli() > ROTATION_DAYS * DAY_MS) n++
            }
            return n
        }

        /**
         * now + N개월의 KST 날짜(YYYY-MM-DD). 다음 달에 같은 일자가 없으면 그 달 말일(plusMonths 의 말일 보정).
         * public `@JvmStatic`: 같은 패키지 Java 테스트(ApiKeysTabControllerTest)가 부른다.
         */
        @JvmStatic
        fun previewExpiry(months: Int, now: Instant): String = now.atZone(KstFormat.KST).plusMonths(months.toLong()).toLocalDate().toString()

        /** 회전 뒤 이전 키 만료 안내. 시간은 반올림, 음수는 0. */
        private fun oldKeyNotice(oldKeyExpiresAt: OffsetDateTime?, now: Instant): String? {
            if (oldKeyExpiresAt == null) return null
            val hours = Math.max(0L, Math.round(Duration.between(now, oldKeyExpiresAt.toInstant()).toMillis() / 3_600_000.0))
            return "이전 키는 " + hours + "시간 후(" + KstFormat.kstDateTimeMin(oldKeyExpiresAt) + ") 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요."
        }
    }
}
