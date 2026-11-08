package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.operator.AdminUserController
import com.crosscert.passkey.admin.operator.AdminUserDto
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

private val log = LoggerFactory.getLogger(AdminUsersTabController::class.java)

private const val USER_BASE = "/admin/_/settings/admins/"
private const val SIGNUP_BASE = "/admin/_/settings/signups/"

private const val TAB_VIEW = "admin/settings/admins :: tab"
private const val USERS_VIEW = "admin/settings/admins :: usersResponse"
private const val SIGNUPS_VIEW = "admin/settings/admins :: signupsResponse"
private const val CONTENT_VIEW = "admin/settings/admins :: contentResponse"
private const val PARTIAL_VIEW = "admin/settings/admins :: partialResponse"
private const val UNDO_EVENT = "au-undo-suspend"
private const val DIALOG_VIEW = "admin/settings/admins :: approveDialog"
private const val APPROVE_ERROR_VIEW = "admin/settings/admins :: approveError"

private const val TENANT_ERROR = "Tenant를 최소 1개 선택해야 합니다."
private const val PLATFORM_ROLE = "PLATFORM_OPERATOR"
private const val RP_ROLE = "RP_ADMIN"

/**
 * 설정 › Admin 사용자 탭(← pages/settings/AdminUsersTab.tsx, api/adminUsers.ts, api/signupRequests.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다
 * (RP_ADMIN → 403, REST 미호출). 일반 탭 매핑보다 이 리터럴 경로가 우선한다.
 *
 * 화면: 가입 요청 카드(#au-signups) + 콘솔 운영자 카드(#au-users). 승인·거절은 `SignupRequestController`, 정지·활성화는 `AdminUserController`.
 * 원본에 없는 동작은 만들지 않는다: `addTenant`/`removeTenant` 는 원본 탭이 부르지 않아 화면에도 없다.
 * - 정지: 행의 "정지…" → L1 확인 다이얼로그. 본인·마지막 활성 PLATFORM 은 버튼을 막고 이유를 보인다(원본 suspendBlock). 확인 때 서버가 목록을 다시 읽어
 *   ACTIVE 인지·막힘 규칙(본인·마지막 PLATFORM)을 한 번 더 본다(원본 staleReason/suspendBlock) — 어긋나면 REST 를 부르지 않고 확인창 안에 409 사유.
 * - 활성화: 확인 없이 바로(원본 handleActivate). 행 버튼은 요청 동안 비활성(hx-disabled-elt, hx-sync=this:drop).
 * - 승인: "승인…" → 대화상자(GET) → 제출. RP_ADMIN 인데 tenant 를 안 고르면 422 + 대화상자 안 오류(ruling G). 409(다른 관리자가 먼저)는 경고 토스트 후 목록 갱신.
 * - 거절: L1 확인. 409 는 경고 토스트 후 목록 갱신. 승인·거절 뒤 탭 줄의 가입 요청 수 배지를 OOB 로 갱신한다.
 *
 * 이메일·tenant 이름·가입 사유는 사용자가 정한 값이라 템플릿에서 th:text 로만 그린다.
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 `platform(model)` 판정(403)이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리(try 안팎도 같다).
 */
@Controller
class AdminUsersTabController(
    private val users: AdminUserController,
    private val signups: SignupRequestController,
    private val tenants: TenantAdminController,
    private val clock: Clock,
) {

    // ── 모델 ────────────────────────────────────────────────

    /** Tenant 열(원본 TenantCell): ALL=전체 tenant, NONE=—, NAME=이름(+외 N개), ID=이름을 모를 때 id 꼬리. */
    data class TenantCell(val kind: String?, val text: String?, val title: String?, val more: Int)

    data class OpRow(
        val id: String?,
        val email: String?,
        val initial: String?,
        val idTail: String?,
        val roleKey: String?,
        val roleLabel: String?,
        val platform: Boolean,
        val tenant: TenantCell?,
        val mfa: Boolean,
        val lastLogin: String?,
        val status: String?,
        val statusKey: String?,
        val block: String?,
        val hay: String?,
        val activateAttrs: Map<String, Any?>?,
        val suspendAttrs: Map<String, Any?>?,
        val dialogId: String?,
    )

    data class SignupRow(
        val id: String?,
        val email: String?,
        val initial: String?,
        val reason: String?,
        val ago: String?,
        val rejectDialogId: String?,
        val approveAttrs: Map<String, Any?>?,
        val rejectAttrs: Map<String, Any?>?,
    )

    data class TenantOpt(val id: String?, val name: String?, val slug: String?, val q: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    fun tab(model: Model): String {
        val me = SettingsSupport.platform(model)
        loadSignups(model)
        loadUsers(model, me, loadTenantNames())
        return TAB_VIEW
    }

    /** 승인 대화상자: 활성 tenant 목록을 지금 읽어 그린다(원본 ApproveDialog 가 열릴 때 tenantsApi.list). */
    @GetMapping(SIGNUP_BASE + "{id}/approve")
    fun approveDialog(@PathVariable id: UUID, model: Model): String {
        SettingsSupport.platform(model)
        val req = signups.list().firstOrNull { id == it.id }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found")
        val opts = ArrayList<TenantOpt>()
        try {
            for (t in tenants.list().data()) {
                if ("ACTIVE" == t.status) {
                    opts.add(
                        TenantOpt(
                            // !!: 원 Java t.getId().toString() 의 NPE 와 같은 자리(try 안 — 빈 목록으로).
                            t.id!!.toString(), t.displayName, t.slug,
                            (t.displayName + " " + t.slug).lowercase(Locale.ROOT),
                        ),
                    )
                }
            }
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            log.debug("[settings] tenants for approve dialog failed: {}", e.javaClass.simpleName) // non-critical: 빈 목록
        }
        model.addAttribute("req", signupRow(req, Instant.now(clock)))
        model.addAttribute("tenantOpts", opts)
        model.addAttribute("approveUrl", "$SIGNUP_BASE$id/approve")
        model.addAttribute("rolePlatformAttrs", roleAttrs(PLATFORM_ROLE))
        model.addAttribute("roleRpAttrs", roleAttrs(RP_ROLE))
        return DIALOG_VIEW
    }

    // ── 정지·활성화 ─────────────────────────────────────────

    @PostMapping(USER_BASE + "{id}/suspend")
    fun suspend(
        @PathVariable id: UUID,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = SettingsSupport.platform(model)
        val list = users.list()
        val row = list.firstOrNull { id == it.id }
        // 원본 confirmSuspend: 목록이 낡았거나(상태 바뀜) 막힌 대상이면 REST 를 부르지 않고 사유를 확인창 안에 보인다.
        if (row == null || "ACTIVE" != row.status) {
            return SettingsSupport.blocked(res, "운영자 상태가 바뀌었습니다. $STALE_REASON", Toasts.isConfirm(req))
        }
        val why = suspendBlock(row, list, me.email)
        if (why != null) return SettingsSupport.blocked(res, why, Toasts.isConfirm(req))
        users.suspend(id, auth!!)
        val undo = LinkedHashMap<String, Any?>()
        undo["url"] = "$USER_BASE$id/activate?undo=1"
        Toasts.successTitledAction(res, row.email + " 운영자를 정지했습니다.", "다음 요청부터 콘솔에 접근할 수 없습니다.", "되돌리기", UNDO_EVENT, undo)
        return usersResult(model, res, me)
    }

    /**
     * 활성화. 행의 "활성화" 버튼은 확인 없이 바로 호출한다(원본 handleActivate — 호출 전에 목록을 읽지 않는다; 토스트의 이메일은 버튼이 보낸 그 행의 이메일).
     * `undo=1` 은 정지 토스트의 "되돌리기"(원본 undoSuspend): 지금 정지 상태인지 확인하고(아니면 REST 미호출 + 경고), 원본의 성공·실패 문구를 쓴다.
     */
    @PostMapping(USER_BASE + "{id}/activate")
    fun activate(
        @PathVariable id: UUID,
        @RequestParam(name = "undo", defaultValue = "") undo: String,
        @RequestParam(name = "email", defaultValue = "") rowEmail: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = SettingsSupport.platform(model)
        val isUndo = "1" == undo
        var known: String? = "" // 이미 아는 이메일(되돌리기는 사전 확인에서 읽은 행)
        try {
            if (isUndo) {
                val row = users.list().firstOrNull { id == it.id }
                if (row == null || "SUSPENDED" != row.status) {
                    Toasts.warnTitled(res, "되돌릴 수 없습니다", "운영자 상태가 바뀌었습니다. $STALE_REASON")
                    res.setHeader("HX-Reswap", "none")
                    return SettingsSupport.EMPTY_VIEW
                }
                known = row.email
            }
            users.activate(id, auth!!)
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            if (!isUndo) return SettingsSupport.fail(res, "활성화하지 못했습니다", e, Toasts.isConfirm(req))
            // 되돌리기 실패(사전 확인·활성화): 원본은 실패 뒤에도 목록을 다시 읽는다 → 오류 토스트 + 갱신된 카드(200)
            val info = ErrorMessages.describe(e)
            Toasts.errorTitled(
                res, "정지를 되돌리지 못했습니다", info.title + ". 목록에서 “활성화”를 눌러 다시 시도하세요.",
                "HTTP " + SettingsSupport.statusOf(e), MDC.get("traceId"),
            )
            return usersResult(model, res, me)
        }
        val ok = loadUsers(model, me, loadTenantNames())
        // 토스트의 이메일(원본 u.email): 되돌리기는 사전 확인에서 읽은 행, 행의 "활성화"는 버튼이 함께 보낸 그 행의 이메일(활성화 전에 화면이 알던 값 —
        // 다시 읽기에 실패해도 남는다). 둘 다 없으면 다시 읽은 목록에서 찾는다. 토스트 문구에만 쓰고 th:text/x-text 로만 그린다.
        // !!: 원 Java 의 known.isEmpty() 는 행 이메일이 null 이면 NPE(try 밖) — 같은 자리.
        var email: String = if (known!!.isEmpty()) rowEmail.javaTrim() else known
        if (email.isEmpty() && ok) {
            @Suppress("UNCHECKED_CAST")
            val ops = model.getAttribute("ops") as List<OpRow>
            val match = ops.firstOrNull { id.toString() == it.id }
            // !!: 원 Java Stream.map(OpRow::email).findFirst() 는 첫 일치 행의 이메일이 null 이면 NPE — 같은 자리.
            email = if (match == null) "" else match.email!!
        }
        if (isUndo) Toasts.successTitled(res, "$email 운영자의 정지를 되돌렸습니다.", "")
        else Toasts.successTitled(res, "운영자를 다시 활성화했습니다.", email)
        return if (ok) USERS_VIEW else warnUsers(model, res)
    }

    /** 변경 성공 뒤 운영자 카드 응답: 다시 읽기에 성공하면 카드를 교체, 실패하면 화면은 그대로 두고 경고 자리만 채운다(원본 StaleWarning). */
    private fun usersResult(model: Model, res: HttpServletResponse, me: CurrentAdmin): String =
        if (loadUsers(model, me, loadTenantNames())) USERS_VIEW else warnUsers(model, res)

    private fun warnUsers(model: Model, res: HttpServletResponse): String {
        res.setHeader("HX-Reswap", "none")
        model.addAttribute("usersWarn", model.getAttribute("usersError"))
        model.addAttribute("oobUsers", false)
        model.addAttribute("oobSignups", false)
        model.addAttribute("signupsWarn", null)
        model.addAttribute("clearSlot", false)
        model.addAttribute("pendingCount", null)
        return PARTIAL_VIEW
    }

    // ── 가입 요청 ───────────────────────────────────────────

    /**
     * 승인. role 은 PLATFORM_OPERATOR | RP_ADMIN, RP_ADMIN 은 tenant 1개 이상(원본 formValid). PLATFORM 은 tenant 를 보내지 않는다(원본 tenantIds: []).
     * 검증 실패는 422 + 대화상자 안 오류(HX-Retarget). 성공·409 는 가입 요청·운영자 카드와 탭 배지를 새로 그리고 대화상자 자리를 비운다.
     */
    @PostMapping(SIGNUP_BASE + "{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @RequestParam(name = "role", defaultValue = "") role: String,
        @RequestParam(name = "tenantId", required = false) tenantIds: List<String?>?,
        auth: Authentication?,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = SettingsSupport.platform(model)
        if (PLATFORM_ROLE != role && RP_ROLE != role) {
            return approveError(res, model, "역할을 선택하세요.")
        }
        val ids = ArrayList<UUID>()
        if (RP_ROLE == role && tenantIds != null) {
            for (t in tenantIds) {
                if (t == null || t.javaIsBlank()) continue
                val u = UUID.fromString(t.javaTrim()) // 잘못된 값은 IllegalArgumentException → 400(원문 없음)
                if (!ids.contains(u)) ids.add(u)
            }
        }
        if (RP_ROLE == role && ids.isEmpty()) return approveError(res, model, TENANT_ERROR)
        val created: AdminUserDto.View
        try {
            created = signups.approve(id, SignupRequestDto.Approve(role, ids), auth!!)
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            if (SettingsSupport.statusOf(e) == 409) {
                log.debug("[settings] signup approve conflict")
                Toasts.warnTitled(res, "이미 처리된 요청입니다.", "다른 관리자가 먼저 처리했습니다.")
                return refreshAll(model, res, me)
            }
            return SettingsSupport.fail(res, "승인 실패", e, false)
        }
        Toasts.successTitled(res, "가입 요청을 승인했습니다.", created.email + " · 즉시 로그인 가능")
        return refreshAll(model, res, me)
    }

    @PostMapping(SIGNUP_BASE + "{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        SettingsSupport.platform(model)
        var email = ""
        try {
            val match = signups.list().firstOrNull { id == it.id }
            // !!: 원 Java Stream.map(View::getEmail).findFirst() 는 이메일이 null 이면 NPE(이 try 가 삼켜 email 은 "") — 같은 자리.
            email = if (match == null) "" else match.email!!
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            // 이메일은 토스트 문구용일 뿐이라 읽기 실패가 거절을 막지 않는다.
        }
        try {
            signups.reject(id, auth!!)
            Toasts.successTitled(res, "가입 요청을 거절했습니다.", email)
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            if (SettingsSupport.statusOf(e) != 409) throw e // 그 밖의 실패는 확인창 안(confirmError)
            Toasts.warnTitled(res, "이미 처리된 요청입니다.", "다른 관리자가 먼저 처리했습니다.")
        }
        if (loadSignups(model)) return SIGNUPS_VIEW
        res.setHeader("HX-Reswap", "none")
        model.addAttribute("signupsWarn", model.getAttribute("signupsError"))
        model.addAttribute("oobSignups", false)
        model.addAttribute("oobUsers", false)
        model.addAttribute("usersWarn", null)
        model.addAttribute("clearSlot", false)
        model.addAttribute("pendingCount", null)
        return PARTIAL_VIEW
    }

    // ── 도우미 ──────────────────────────────────────────────

    private fun approveError(res: HttpServletResponse, model: Model, message: String): String {
        res.status = 422
        res.setHeader("HX-Retarget", "#au-approve-err")
        res.setHeader("HX-Reswap", "innerHTML")
        model.addAttribute("approveError", message)
        return APPROVE_ERROR_VIEW
    }

    /** 승인 뒤: 둘 다 다시 읽으면 #au-content 교체. 하나라도 실패하면 본 대상은 스왑하지 않고(HX-Reswap none) 읽은 쪽은 OOB 로 새로, 못 읽은 쪽은 경고 자리 OOB. */
    private fun refreshAll(model: Model, res: HttpServletResponse, me: CurrentAdmin): String {
        val s = loadSignups(model)
        val u = loadUsers(model, me, loadTenantNames())
        if (s && u) return CONTENT_VIEW
        res.setHeader("HX-Reswap", "none")
        model.addAttribute("oobSignups", s)
        model.addAttribute("oobUsers", u)
        model.addAttribute("signupsWarn", if (s) null else model.getAttribute("signupsError"))
        model.addAttribute("usersWarn", if (u) null else model.getAttribute("usersError"))
        model.addAttribute("clearSlot", true)
        return PARTIAL_VIEW
    }

    private fun loadTenantNames(): Map<String, String?> {
        val names = HashMap<String, String?>()
        try {
            // !!: 원 Java t.getId().toString() 의 NPE 와 같은 자리(try 안 — 이름 없이 id 꼬리로).
            for (t in tenants.list().data()) names[t.id!!.toString()] = t.displayName
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            log.debug("[settings] tenant names failed: {}", e.javaClass.simpleName) // 실패해도 id 꼬리로 보인다
        }
        return names
    }

    private fun loadSignups(model: Model): Boolean {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH)) // 다시 시도 = 탭 전체를 다시 받는다
        model.addAttribute("signupsError", null)
        model.addAttribute("pending", null)
        model.addAttribute("pendingCount", null)
        try {
            val now = Instant.now(clock)
            val rows = ArrayList<SignupRow>()
            for (r in signups.list()) rows.add(signupRow(r, now))
            model.addAttribute("pending", rows)
            model.addAttribute("pendingCount", rows.size)
            return true
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[settings] signup requests load failed: {}", e.javaClass.simpleName)
            model.addAttribute("signupsError", e)
            return false
        }
    }

    private fun loadUsers(model: Model, me: CurrentAdmin, names: Map<String, String?>): Boolean {
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH))
        model.addAttribute("usersError", null)
        model.addAttribute("ops", null)
        try {
            val list = users.list()
            val now = Instant.now(clock)
            val rows = ArrayList<OpRow>()
            val active = list.stream().filter { u -> "ACTIVE" == u.status }.count()
            for (u in list) rows.add(opRow(u, list, me.email, names, now))
            model.addAttribute("ops", rows)
            model.addAttribute("opsSummary", list.size.toString() + "명 · 활성 " + active + "명")
            model.addAttribute(
                "roleOptions",
                java.util.List.of(opt("all", "전체"), opt("PLATFORM", "PLATFORM"), opt("RP_ADMIN", "RP_ADMIN"), opt("SUSPENDED", "정지")),
            )
            return true
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            log.warn("[settings] admin users load failed: {}", e.javaClass.simpleName)
            model.addAttribute("usersError", e)
            return false
        }
    }

    companion object {
        /** internal: 같은 모듈 테스트(AdminUsersTabControllerTest)가 읽는다. */
        internal const val TAB_PATH = "/admin/_/settings/tab/admins"
        internal const val STALE_REASON = "목록을 새로 고친 뒤 다시 시도하세요"

        /** 역할 카드 속성(템플릿 상수만 — JS 평가 속성에 사용자 입력 없음). */
        private fun roleAttrs(role: String): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["x-on:click"] = "role = '$role'; clearErr()"
            m["x-bind:class"] = "{ 'choice--on': role === '$role' }"
            m["x-bind:aria-checked"] = "role === '$role'"
            return m
        }

        private fun signupRow(r: SignupRequestDto.View, now: Instant): SignupRow {
            // !!: 원 Java r.getId().toString() 의 NPE 와 같은 자리.
            val id = r.id!!.toString()
            val approve = LinkedHashMap<String, Any?>()
            approve["hx-get"] = "$SIGNUP_BASE$id/approve"
            approve["hx-target"] = "#au-dialog-slot"
            approve["hx-swap"] = "innerHTML"
            approve["hx-sync"] = "this:drop"
            val reject = LinkedHashMap<String, Any?>()
            reject["hx-post"] = "$SIGNUP_BASE$id/reject"
            reject["hx-target"] = "#au-signups"
            reject["hx-swap"] = "outerHTML"
            reject["hx-sync"] = "this:drop"
            val at = r.requestedAt?.toInstant() // 원 Java: null 이면 null(그대로 timeAgo 로)
            return SignupRow(id, r.email, initial(r.email), r.reason, KstFormat.timeAgo(at, now), "au-reject-$id", approve, reject)
        }

        private fun opt(value: String, label: String): Map<String, Any?> {
            val o = LinkedHashMap<String, Any?>()
            o["value"] = value
            o["label"] = label
            o["title"] = null
            o["attrs"] = null
            return o
        }

        private fun opRow(u: AdminUserDto.View, all: List<AdminUserDto.View>, meEmail: String?, names: Map<String, String?>, now: Instant): OpRow {
            // !!: 원 Java u.getId().toString() 의 NPE 와 같은 자리.
            val id = u.id!!.toString()
            val platform = PLATFORM_ROLE == u.role
            val block = if ("ACTIVE" == u.status) suspendBlock(u, all, meEmail) else null
            val suspend = LinkedHashMap<String, Any?>()
            suspend["hx-post"] = "$USER_BASE$id/suspend"
            suspend["hx-target"] = "#au-users"
            suspend["hx-swap"] = "outerHTML"
            suspend["hx-sync"] = "this:drop"
            val activate = LinkedHashMap<String, Any?>()
            activate["hx-post"] = "$USER_BASE$id/activate"
            activate["hx-target"] = "#au-users"
            activate["hx-swap"] = "outerHTML"
            activate["hx-sync"] = "this:drop"
            activate["hx-disabled-elt"] = "this"
            val last = u.lastLoginAt?.toInstant() // 원 Java: null 이면 null → "미접속"
            return OpRow(
                id, u.email, initial(u.email), tail(id, 10), u.role, if (platform) "PLATFORM" else "RP_ADMIN", platform,
                tenantCell(u, names), u.mfaEnabled, if (last == null) "미접속" else KstFormat.timeAgo(last, now), u.status, filterStatus(u),
                block, (u.email + " " + id).lowercase(Locale.ROOT), activate, suspend, "au-suspend-$id",
            )
        }

        private fun filterStatus(u: AdminUserDto.View): String = if ("SUSPENDED" == u.status) "SUSPENDED" else ""

        /**
         * 원본 suspendBlock: 본인, 또는 마지막 활성 PLATFORM 운영자.
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(AdminUsersTabControllerTest)가 부른다.
         */
        internal fun suspendBlock(u: AdminUserDto.View, all: List<AdminUserDto.View>, meEmail: String?): String? {
            val email = u.email
            if (email != null && meEmail != null && email.equals(meEmail, ignoreCase = true)) return "본인 계정은 정지할 수 없음"
            val activePlatform = all.stream().filter { x -> PLATFORM_ROLE == x.role && "ACTIVE" == x.status }.count()
            if (PLATFORM_ROLE == u.role && "ACTIVE" == u.status && activePlatform <= 1) return "마지막 활성 PLATFORM 운영자"
            return null
        }

        private fun tenantCell(u: AdminUserDto.View, names: Map<String, String?>): TenantCell {
            if (PLATFORM_ROLE == u.role) return TenantCell("ALL", "전체 tenant", null, 0)
            val ids: List<UUID> = u.tenantIds ?: java.util.List.of()
            if (ids.isEmpty()) return TenantCell("NONE", "—", null, 0)
            val first = ids[0].toString()
            val name = names[first]
            val more = ids.size - 1
            return if (name != null) TenantCell("NAME", name, first, more) else TenantCell("ID", "…" + tail(first, 10), first, more)
        }

        /** internal: 같은 모듈 테스트(AdminUsersTabControllerTest)가 부른다. */
        internal fun initial(email: String?): String =
            if (email == null || email.isEmpty()) "" else email.substring(0, 1).uppercase(Locale.ROOT)

        /** internal: 같은 모듈 테스트(AdminUsersTabControllerTest)가 부른다. */
        internal fun tail(s: String, n: Int): String = if (s.length <= n) s else s.substring(s.length - n)
    }
}
