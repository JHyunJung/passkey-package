package com.crosscert.passkey.admin.web.format

import com.crosscert.passkey.admin.web.support.StandaloneJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 템플릿에서 `${@fmt.status(x)}`, `${@fmt.kst(t)}`, `${@fmt.josa(name,'을/를')}` 처럼 쓰는 표시 도우미 빈.
 * 현재 시각이 필요한 것은 주입된 [Clock] 만 쓴다.
 *
 * 템플릿(SpEL)이 이름·인자 수·인자 타입으로 고르므로 오버로드는 원 Java 와 같은 순서·같은 JVM 시그니처로 둔다
 * (기본 인자·`@JvmOverloads` 없음, 가변 인자는 `vararg`).
 */
@Component("fmt")
class ViewHelpers {

    // 주 생성자를 두지 않는다: 스프링은 Kotlin 주 생성자가 있으면 그것만 후보로 본다(BeanUtils.findPrimaryConstructor).
    // 원 Java 처럼 공개 생성자 두 개를 후보로 두어야 ObjectMapper 빈이 없는 컨텍스트(registerBean)에서 (Clock) 생성자로 뜬다.
    private val clock: Clock
    private val json: ObjectMapper

    /** 스프링이 구성한 ObjectMapper(java.time 지원 포함)를 쓴다. */
    @Autowired
    constructor(clock: Clock, json: ObjectMapper) {
        this.clock = clock
        this.json = json
    }

    /**
     * 스프링 컨텍스트 없는 단위 테스트용. Jackson 2 시절 `new ObjectMapper().findAndRegisterModules()` 와 같은 의미 —
     * Jackson 3 는 java.time 지원이 내장이고, `configureForJackson2()` 가 날짜를 timestamp 로 쓰는 Jackson 2 기본값을 되돌린다.
     */
    constructor(clock: Clock) : this(clock, StandaloneJson.jackson2Like().build())

    // ── 라벨 ──
    /** 상태 enum → 한국어(미매핑은 원문). */
    fun status(v: String?): String? = Labels.statusLabel(v)

    /** AAGUID 정책 모드 enum → 한국어. */
    fun aaguidMode(v: String?): String? = Labels.aaguidMode(v)

    fun mdsTrust(v: String?): Labels.MdsTrustMode? = if (v == null) null else Labels.MDS_TRUST_MODES[v]

    /** 상태 배지 톤(StatusBadge 의 MAP). */
    fun statusTone(v: String?): String {
        if (v == null) return "neutral"
        return when (v) {
            "ACTIVE" -> "ok"
            "REVOKED", "SUSPENDED" -> "bad"
            "PENDING" -> "warn"
            else -> "neutral"
        }
    }

    // ── 시각 (모두 Asia/Seoul) ──
    /** `YYYY-MM-DD HH:mm:ss`. null 은 —. */
    fun kst(t: OffsetDateTime?): String = KstFormat.kstDateTime(t)
    fun kst(t: Instant?): String = KstFormat.kstDateTime(t)
    fun kst(iso: String?): String? = KstFormat.kstDateTime(iso)

    /** `YYYY-MM-DD HH:mm`. */
    fun kstMin(t: OffsetDateTime?): String = KstFormat.kstDateTimeMin(t)
    fun kstMin(t: Instant?): String = KstFormat.kstDateTimeMin(t)

    /** `YYYY-MM-DD`. */
    fun kstDate(t: OffsetDateTime?): String = KstFormat.kstDate(t)
    fun kstDate(t: Instant?): String = KstFormat.kstDate(t)
    fun kstDate(iso: String?): String? = KstFormat.kstDate(iso)

    /** `HH:mm`. */
    fun kstTime(t: OffsetDateTime?): String = KstFormat.kstTime(t)
    fun kstTime(t: Instant?): String = KstFormat.kstTime(t)

    /** ko-KR `2026. 05. 27. 12:00:00` (formatDateTime.ts). */
    fun dateTime(t: OffsetDateTime?): String = KstFormat.formatDateTime(t)
    fun dateTime(t: Instant?): String = KstFormat.formatDateTime(t)
    fun dateTime(iso: String?): String? = KstFormat.formatDateTime(iso)

    /** LocalDate → `2026. 05. 27.` (formatDate). */
    fun date(d: LocalDate?): String = KstFormat.formatDate(d)
    fun date(s: String?): String? = KstFormat.formatDate(s)

    fun utcIso(iso: String?): String? = KstFormat.utcIso(iso)

    /** "5분 전" / "55분 후". */
    fun relative(t: Instant?): String = KstFormat.relative(t, clock.instant())
    fun relative(t: OffsetDateTime?): String = if (t == null) "—" else relative(t.toInstant())

    /** "방금 전" / "5분 전" / "2일 전" / "1개월 전". */
    fun timeAgo(t: Instant?): String = KstFormat.timeAgo(t, clock.instant())
    fun timeAgo(t: OffsetDateTime?): String = if (t == null) "—" else timeAgo(t.toInstant())

    /** KST 기준 오늘 날짜(파일 이름 등). */
    fun today(): LocalDate = KstFormat.kstTodayDate(clock.instant())

    // ── 문구 ──
    /** 한글 조사. pair 는 "받침 있을 때/없을 때"(예: '을/를', '이/가'). */
    fun josa(word: String?, pair: String): String = Josa.pick(word, pair)

    /** 단어 + 조사. */
    fun withJosa(word: String?, pair: String): String = word + Josa.pick(word, pair)

    // ── API 키·플랫폼 ──
    /** 만료 임박(30일 이내) 여부. 회수 여부는 고려하지 않는다(회수된 키는 따로 거른다). */
    fun apiKeyExpiry(expiresAt: OffsetDateTime?): Boolean =
        ApiKeyExpiry.isExpiringSoon(null, expiresAt, clock.instant())

    /** 회수 여부까지 반영한 만료 임박 판정. */
    fun isExpiringSoon(revokedAt: OffsetDateTime?, expiresAt: OffsetDateTime?): Boolean =
        ApiKeyExpiry.isExpiringSoon(revokedAt, expiresAt, clock.instant())

    /** User-Agent 로 판단한 명령 팔레트 단축키 표기(⌘K / Ctrl K). */
    fun platform(userAgent: String?): String =
        PlatformLabels.paletteShortcutLabel(PlatformLabels.isMacPlatform(null, userAgent))

    // ── 오류 ──
    /** 화면에 보일 오류 한 줄("제목. 설명"). */
    fun error(e: Throwable?): String? {
        val i = ErrorMessages.describe(e)
        val detail = i.detail
        return if (detail == null || detail.isEmpty()) i.title else i.title + ". " + detail
    }

    fun errorInfo(e: Throwable?): ErrorMessages.Info = ErrorMessages.describe(e)

    /** 오류 정보 복사용 여러 줄 문구: 대상, 제목, 설명, HTTP 상태(LoadError 의 복사 내용). */
    fun errorText(what: String, e: Throwable?): String {
        val i = ErrorMessages.describe(e)
        val sb = StringBuilder(what).append('\n').append(i.title)
        val detail = i.detail
        if (detail != null && detail.isNotEmpty()) sb.append('\n').append(detail)
        if (i.httpStatus != null) sb.append("\nHTTP ").append(i.httpStatus)
        return sb.toString()
    }

    // ── 파일명 ──
    fun recoveryCodeFilename(email: String?): String = RecoveryCodeFilename.of(email, today())

    // ── Alpine 설정 ──
    /** 값을 JSON 으로. 속성 값에 넣으면 Thymeleaf 가 이스케이프한다(th:x-data 등). */
    fun json(value: Any?): String {
        try {
            return json.writeValueAsString(value)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("JSON 직렬화 실패", e)
        }
    }

    /** `xdata('chipInput', 'values', v)` → `chipInput({"values":…})`. `ui:xdata` 속성에 쓴다. */
    fun xdata(component: String?, vararg keyValues: Any?): String = component + "(" + cfg(*keyValues) + ")"

    /** tabs 조각: items(각 Map 의 'id') 중 active 와 같은 것이 있는지. */
    fun hasTab(items: List<Map<String, *>>?, active: Any?): Boolean =
        items != null && items.stream().anyMatch { t -> t["id"] == active }

    /** 조각의 선택 옵션 Map 을 null 안전하게 읽는다(opts 가 null 이거나 키가 없으면 null). */
    fun opt(opts: Map<String, *>?, key: String): Any? = if (opts == null) null else opts[key]

    /** `cfg('values', v, 'mono', true)` → `{"values":…,"mono":true}`. Alpine.data 팩토리의 인자로 쓴다. */
    fun cfg(vararg keyValues: Any?): String {
        val m = LinkedHashMap<String, Any?>()
        var i = 0
        while (i + 1 < keyValues.size) {
            m[keyValues[i].toString()] = keyValues[i + 1]
            i += 2
        }
        return json(m)
    }

    // ── 페이지네이션 ──
    /** 보정된 현재 페이지(1..last)와 표시 범위. page 가 범위를 넘으면 마지막 페이지로 맞춘다. */
    data class Pager(val page: Int, val last: Int, val from: Long, val to: Long)

    fun pager(page: Int, pageSize: Int, total: Long): Pager {
        val size = if (pageSize <= 0) 1 else pageSize // 0 나눗셈 방지
        val last = maxOf(1L, (total + size - 1) / size).toInt()
        val p = minOf(maxOf(1, page), last)
        val from = if (total == 0L) 0L else (p - 1).toLong() * size + 1
        val to = minOf(total, p.toLong() * size)
        return Pager(p, last, from, to)
    }

    /** `base` 에 page(·size) 쿼리를 붙인 주소. base 는 page·size 를 뺀 경로+쿼리. */
    fun pageUrl(base: String, page: Int, size: Int?): String {
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + "page=" + page + (if (size == null) "" else "&size=$size")
    }

    /** 페이지 크기 select 가 값을 이어 붙일 주소(첫 페이지로). */
    fun pageSizeBase(base: String): String = pageUrl(base, 1, null) + "&size="
}
